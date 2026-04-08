package com.nexio.tv.data.repository

import com.nexio.tv.BuildConfig
import com.nexio.tv.data.remote.api.TraktApi
import com.nexio.tv.data.remote.dto.trakt.TraktEpisodeDto
import com.nexio.tv.data.remote.dto.trakt.TraktCheckinRequestDto
import com.nexio.tv.data.remote.dto.trakt.TraktIdsDto
import com.nexio.tv.data.remote.dto.trakt.TraktMovieDto
import com.nexio.tv.data.remote.dto.trakt.TraktScrobbleRequestDto
import com.nexio.tv.data.remote.dto.trakt.TraktShowDto
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

sealed interface TraktScrobbleItem {
    val itemKey: String

    data class Movie(
        val title: String?,
        val year: Int?,
        val ids: TraktIdsDto
    ) : TraktScrobbleItem {
        override val itemKey: String =
            "movie:${ids.imdb ?: ids.tmdb ?: ids.trakt ?: title.orEmpty()}:${year ?: 0}"
    }

    data class Episode(
        val showTitle: String?,
        val showYear: Int?,
        val showIds: TraktIdsDto,
        val season: Int,
        val number: Int,
        val episodeTitle: String?
    ) : TraktScrobbleItem {
        override val itemKey: String =
            "episode:${showIds.imdb ?: showIds.tmdb ?: showIds.trakt ?: showTitle.orEmpty()}:$season:$number"
    }
}

@Singleton
class TraktScrobbleService @Inject constructor(
    private val traktApi: TraktApi,
    private val traktAuthService: TraktAuthService,
    private val traktProgressService: TraktProgressService
) {
    internal sealed interface MutationResult {
        data object Success : MutationResult
        data object Failed : MutationResult
        data object Collapsed : MutationResult
    }

    data class WatchingNowState(
        val active: Boolean = false,
        val title: String? = null,
        val contentType: String? = null,
        val progressPercent: Float? = null,
        val updatedAtMs: Long = 0L
    )

    private data class ScrobbleStamp(
        val action: String,
        val itemKey: String,
        val progress: Float,
        val timestampMs: Long
    )

    private val watchingNowState = MutableStateFlow(WatchingNowState())
    private val mutationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pendingMutationMutex = Mutex()
    private var lastScrobbleStamp: ScrobbleStamp? = null
    private var pendingMutation: QueuedWatchingMutation? = null
    private var pendingMutationDrainJob: Job? = null
    private var watchingNowOptimisticVersion: Long = 0L
    private val minSendIntervalMs = 8_000L
    private val progressWindow = 1.5f

    suspend fun scrobbleStart(item: TraktScrobbleItem, progressPercent: Float) {
        if (!canMutateWatchingState()) return
        submitMutation(
            request = WatchingMutationRequest.Scrobble(
                action = "start",
                item = item,
                progressPercent = progressPercent
            )
        )
    }

    suspend fun scrobbleStop(item: TraktScrobbleItem, progressPercent: Float) {
        if (!canMutateWatchingState()) return
        submitMutation(
            request = WatchingMutationRequest.Scrobble(
                action = "stop",
                item = item,
                progressPercent = progressPercent
            )
        )
    }

    suspend fun scrobblePause(item: TraktScrobbleItem, progressPercent: Float) {
        if (!canMutateWatchingState()) return
        submitMutation(
            request = WatchingMutationRequest.Scrobble(
                action = "pause",
                item = item,
                progressPercent = progressPercent
            )
        )
    }

    suspend fun checkin(item: TraktScrobbleItem, message: String? = null): Boolean {
        if (!canMutateWatchingState()) return false
        return submitMutation(
            request = WatchingMutationRequest.CheckIn(
                item = item,
                message = message
            )
        ) == MutationResult.Success
    }

    fun observeWatchingNowState(): StateFlow<WatchingNowState> = watchingNowState.asStateFlow()

    private suspend fun canMutateWatchingState(): Boolean {
        if (!traktAuthService.getCurrentAuthState().isAuthenticated) return false
        if (!traktAuthService.hasRequiredCredentials()) return false
        return true
    }

    private suspend fun submitMutation(request: WatchingMutationRequest): MutationResult {
        val result = CompletableDeferred<MutationResult>()
        pendingMutationMutex.withLock {
            val optimisticVersion = watchingNowOptimisticVersion + 1L
            watchingNowOptimisticVersion = optimisticVersion
            val incoming = QueuedWatchingMutation(
                mutation = PendingWatchingMutation(
                    request = request,
                    rollbackState = watchingNowState.value,
                    optimisticVersion = optimisticVersion
                ),
                result = result
            )
            publishWatchingNowState(request.toWatchingNowState())
            val existing = pendingMutation
            if (existing != null) {
                existing.result.complete(MutationResult.Collapsed)
            }
            pendingMutation = replacePendingWatchingMutation(
                existing = existing?.mutation,
                incoming = incoming.mutation
            ).let { merged ->
                incoming.copy(mutation = merged)
            }
            if (pendingMutationDrainJob?.isActive != true) {
                pendingMutationDrainJob = mutationScope.launch {
                    drainPendingMutations()
                }
            }
        }
        return result.await()
    }

    private suspend fun drainPendingMutations() {
        while (true) {
            val next = pendingMutationMutex.withLock {
                val queued = pendingMutation ?: run {
                    pendingMutationDrainJob = null
                    return
                }
                pendingMutation = null
                queued
            }
            val result = executeMutation(next.mutation.request)
            if (result == MutationResult.Failed) {
                pendingMutationMutex.withLock {
                    if (shouldRollbackWatchingMutation(watchingNowOptimisticVersion, next.mutation.optimisticVersion)) {
                        publishWatchingNowState(next.mutation.rollbackState)
                    }
                }
            }
            next.result.complete(result)
        }
    }

    private suspend fun executeMutation(request: WatchingMutationRequest): MutationResult {
        return when (request) {
            is WatchingMutationRequest.CheckIn -> executeCheckin(request)
            is WatchingMutationRequest.Scrobble -> executeScrobble(request)
        }
    }

    private suspend fun executeCheckin(request: WatchingMutationRequest.CheckIn): MutationResult {
        val requestBody = buildCheckinRequestBody(item = request.item, message = request.message)
        val response = traktAuthService.executeAuthorizedWriteRequest { authHeader ->
            traktApi.checkin(authHeader, requestBody)
        } ?: return MutationResult.Failed

        if (response.isSuccessful || response.code() == 409) {
            traktProgressService.refreshNow()
            return MutationResult.Success
        }
        return MutationResult.Failed
    }

    private suspend fun executeScrobble(request: WatchingMutationRequest.Scrobble): MutationResult {
        val action = request.action
        val item = request.item
        val progressPercent = request.progressPercent
        val clampedProgress = progressPercent.coerceIn(0f, 100f)
        if (shouldSkip(action, item.itemKey, clampedProgress)) return MutationResult.Success

        val requestBody = buildRequestBody(item, clampedProgress)

        val response = traktAuthService.executeAuthorizedWriteRequest { authHeader ->
            when (action) {
                "start" -> traktApi.scrobbleStart(authHeader, requestBody)
                "pause" -> traktApi.scrobblePause(authHeader, requestBody)
                else -> traktApi.scrobbleStop(authHeader, requestBody)
            }
        } ?: return MutationResult.Failed

        if (response.isSuccessful || response.code() == 409) {
            lastScrobbleStamp = ScrobbleStamp(
                action = action,
                itemKey = item.itemKey,
                progress = clampedProgress,
                timestampMs = System.currentTimeMillis()
            )
            if (action == "stop") {
                traktProgressService.refreshNow()
            }
            return MutationResult.Success
        }
        return MutationResult.Failed
    }

    internal fun buildRequestBody(
        item: TraktScrobbleItem,
        clampedProgress: Float
    ): TraktScrobbleRequestDto {
        return when (item) {
            is TraktScrobbleItem.Movie -> TraktScrobbleRequestDto(
                movie = TraktMovieDto(
                    title = item.title,
                    year = item.year,
                    ids = item.ids
                ),
                progress = clampedProgress,
                appVersion = BuildConfig.VERSION_NAME
            )

            is TraktScrobbleItem.Episode -> TraktScrobbleRequestDto(
                show = TraktShowDto(
                    title = item.showTitle,
                    year = item.showYear,
                    ids = item.showIds
                ),
                episode = TraktEpisodeDto(
                    title = item.episodeTitle,
                    season = item.season,
                    number = item.number
                ),
                progress = clampedProgress,
                appVersion = BuildConfig.VERSION_NAME
            )
        }
    }

    private fun buildCheckinRequestBody(
        item: TraktScrobbleItem,
        message: String?
    ): TraktCheckinRequestDto {
        return when (item) {
            is TraktScrobbleItem.Movie -> TraktCheckinRequestDto(
                movie = TraktMovieDto(
                    title = item.title,
                    year = item.year,
                    ids = item.ids
                ),
                appVersion = BuildConfig.VERSION_NAME,
                message = message
            )

            is TraktScrobbleItem.Episode -> TraktCheckinRequestDto(
                show = TraktShowDto(
                    title = item.showTitle,
                    year = item.showYear,
                    ids = item.showIds
                ),
                episode = TraktEpisodeDto(
                    title = item.episodeTitle,
                    season = item.season,
                    number = item.number
                ),
                appVersion = BuildConfig.VERSION_NAME,
                message = message
            )
        }
    }

    private fun shouldSkip(action: String, itemKey: String, progress: Float): Boolean {
        val last = lastScrobbleStamp ?: return false
        val now = System.currentTimeMillis()
        val isSameWindow = now - last.timestampMs < minSendIntervalMs
        val isSameAction = last.action == action
        val isSameItem = last.itemKey == itemKey
        val isNearProgress = abs(last.progress - progress) <= progressWindow
        return isSameWindow && isSameAction && isSameItem && isNearProgress
    }

    private fun updateWatchingNowState(
        active: Boolean,
        item: TraktScrobbleItem,
        progressPercent: Float?
    ) {
        publishWatchingNowState(
            WatchingNowState(
                active = active,
                title = buildWatchingTitle(item),
                contentType = when (item) {
                    is TraktScrobbleItem.Movie -> "movie"
                    is TraktScrobbleItem.Episode -> "episode"
                },
                progressPercent = progressPercent
            )
        )
    }

    private fun buildWatchingTitle(item: TraktScrobbleItem): String? {
        val title = when (item) {
            is TraktScrobbleItem.Movie -> item.title to "movie"
            is TraktScrobbleItem.Episode -> {
                val label = buildString {
                    append(item.showTitle.orEmpty())
                    append(" S")
                    append(item.season)
                    append("E")
                    append(item.number)
                    if (!item.episodeTitle.isNullOrBlank()) {
                        append(" ")
                        append(item.episodeTitle)
                    }
                }.trim()
                label to "episode"
            }
        }.first
        return title?.takeIf { it.isNotBlank() }
    }

    private fun publishWatchingNowState(state: WatchingNowState) {
        watchingNowState.value = state.copy(
            updatedAtMs = System.currentTimeMillis()
        )
    }
}

internal sealed interface WatchingMutationRequest {
    fun toWatchingNowState(): TraktScrobbleService.WatchingNowState

    data class Scrobble(
        val action: String,
        val item: TraktScrobbleItem,
        val progressPercent: Float
    ) : WatchingMutationRequest {
        override fun toWatchingNowState(): TraktScrobbleService.WatchingNowState {
            val progress = progressPercent.coerceIn(0f, 100f)
            return TraktScrobbleService.WatchingNowState(
                active = action == "start",
                title = when (item) {
                    is TraktScrobbleItem.Movie -> item.title?.takeIf { it.isNotBlank() }
                    is TraktScrobbleItem.Episode -> buildString {
                        append(item.showTitle.orEmpty())
                        append(" S")
                        append(item.season)
                        append("E")
                        append(item.number)
                        if (!item.episodeTitle.isNullOrBlank()) {
                            append(" ")
                            append(item.episodeTitle)
                        }
                    }.trim().takeIf { it.isNotBlank() }
                },
                contentType = when (item) {
                    is TraktScrobbleItem.Movie -> "movie"
                    is TraktScrobbleItem.Episode -> "episode"
                },
                progressPercent = progress
            )
        }
    }

    data class CheckIn(
        val item: TraktScrobbleItem,
        val message: String?
    ) : WatchingMutationRequest {
        override fun toWatchingNowState(): TraktScrobbleService.WatchingNowState {
            return TraktScrobbleService.WatchingNowState(
                active = true,
                title = when (item) {
                    is TraktScrobbleItem.Movie -> item.title?.takeIf { it.isNotBlank() }
                    is TraktScrobbleItem.Episode -> buildString {
                        append(item.showTitle.orEmpty())
                        append(" S")
                        append(item.season)
                        append("E")
                        append(item.number)
                        if (!item.episodeTitle.isNullOrBlank()) {
                            append(" ")
                            append(item.episodeTitle)
                        }
                    }.trim().takeIf { it.isNotBlank() }
                },
                contentType = when (item) {
                    is TraktScrobbleItem.Movie -> "movie"
                    is TraktScrobbleItem.Episode -> "episode"
                },
                progressPercent = null
            )
        }
    }
}

internal data class PendingWatchingMutation(
    val request: WatchingMutationRequest,
    val rollbackState: TraktScrobbleService.WatchingNowState,
    val optimisticVersion: Long
)

private data class QueuedWatchingMutation(
    val mutation: PendingWatchingMutation,
    val result: CompletableDeferred<TraktScrobbleService.MutationResult>
)

internal fun replacePendingWatchingMutation(
    existing: PendingWatchingMutation?,
    incoming: PendingWatchingMutation
): PendingWatchingMutation {
    val rollbackState = existing?.rollbackState ?: incoming.rollbackState
    return incoming.copy(rollbackState = rollbackState)
}

internal fun shouldRollbackWatchingMutation(
    currentOptimisticVersion: Long,
    failedMutationVersion: Long
): Boolean = currentOptimisticVersion == failedMutationVersion
