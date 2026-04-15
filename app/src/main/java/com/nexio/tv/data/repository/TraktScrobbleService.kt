package com.nexio.tv.data.repository

import com.nexio.tv.data.remote.dto.trakt.TraktIdsDto
import com.nexio.tv.data.repository.trakt.TraktScrobbleMutationAdapter
import com.nexio.tv.data.repository.trakt.TraktWatchingNowStateController
import com.nexio.tv.data.trakt.outbox.TraktMutationOutboxCoordinator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
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
    private val traktAuthService: TraktAuthService,
    private val watchingNowStateController: TraktWatchingNowStateController,
    private val traktMutationOutboxCoordinator: TraktMutationOutboxCoordinator
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

    private val watchingNowState = watchingNowStateController.observe()
        .map { snapshot ->
            WatchingNowState(
                active = snapshot.active,
                title = snapshot.title,
                contentType = snapshot.contentType,
                progressPercent = snapshot.progressPercent,
                updatedAtMs = snapshot.updatedAtMs
            )
        }
        .stateIn(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            started = SharingStarted.Eagerly,
            initialValue = WatchingNowState()
        )
    private val mutationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pendingMutationMutex = Mutex()
    private var lastScrobbleStamp: ScrobbleStamp? = null
    private var pendingMutation: QueuedWatchingMutation? = null
    private var pendingMutationDrainJob: Job? = null
    private val minSendIntervalMs = 8_000L
    private val progressWindow = 1.5f

    suspend fun scrobbleStart(item: TraktScrobbleItem, progressPercent: Float) {
        if (!canMutateWatchingState()) return
        val optimisticVersion = watchingNowStateController.nextOptimisticVersion()
        submitMutation(
            request = WatchingMutationRequest.Scrobble(
                action = "start",
                item = item,
                progressPercent = progressPercent,
                optimisticVersion = optimisticVersion
            )
        )
    }

    suspend fun scrobbleStop(item: TraktScrobbleItem, progressPercent: Float) {
        if (!canMutateWatchingState()) return
        val optimisticVersion = watchingNowStateController.nextOptimisticVersion()
        submitMutation(
            request = WatchingMutationRequest.Scrobble(
                action = "stop",
                item = item,
                progressPercent = progressPercent,
                optimisticVersion = optimisticVersion
            )
        )
    }

    suspend fun scrobblePause(item: TraktScrobbleItem, progressPercent: Float) {
        if (!canMutateWatchingState()) return
        val optimisticVersion = watchingNowStateController.nextOptimisticVersion()
        submitMutation(
            request = WatchingMutationRequest.Scrobble(
                action = "pause",
                item = item,
                progressPercent = progressPercent,
                optimisticVersion = optimisticVersion
            )
        )
    }

    suspend fun checkin(item: TraktScrobbleItem, message: String? = null): Boolean {
        if (!canMutateWatchingState()) return false
        val optimisticVersion = watchingNowStateController.nextOptimisticVersion()
        return submitMutation(
            request = WatchingMutationRequest.CheckIn(
                item = item,
                message = message,
                optimisticVersion = optimisticVersion
            )
        ) == MutationResult.Success
    }

    fun observeWatchingNowState(): StateFlow<WatchingNowState> = watchingNowState

    private suspend fun canMutateWatchingState(): Boolean {
        if (!traktAuthService.getCurrentAuthState().isAuthenticated) return false
        if (!traktAuthService.hasRequiredCredentials()) return false
        return true
    }

    private suspend fun submitMutation(request: WatchingMutationRequest): MutationResult {
        val result = CompletableDeferred<MutationResult>()
        pendingMutationMutex.withLock {
            val incoming = QueuedWatchingMutation(
                mutation = PendingWatchingMutation(
                    request = request,
                    rollbackState = watchingNowStateController.current(),
                    optimisticVersion = request.optimisticVersion
                ),
                result = result
            )
            watchingNowStateController.publish(request.toWatchingNowState())
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
            val result = executeMutation(next.mutation)
            if (result == MutationResult.Failed) {
                watchingNowStateController.rollbackIfCurrent(
                    expectedVersion = next.mutation.optimisticVersion,
                    rollbackState = next.mutation.rollbackState
                )
            }
            next.result.complete(result)
        }
    }

    private suspend fun executeMutation(mutation: PendingWatchingMutation): MutationResult {
        return when (val request = mutation.request) {
            is WatchingMutationRequest.CheckIn -> enqueueCheckin(request, mutation.rollbackState)
            is WatchingMutationRequest.Scrobble -> enqueueScrobble(request, mutation.rollbackState)
        }
    }

    private suspend fun enqueueCheckin(
        request: WatchingMutationRequest.CheckIn,
        rollbackState: TraktWatchingNowStateController.Snapshot
    ): MutationResult {
        return runCatching {
            val session = traktAuthService.currentAuthSession()
            traktMutationOutboxCoordinator.enqueueAndDrain(
                TraktScrobbleMutationAdapter.buildCheckinEnvelope(
                    item = request.item,
                    message = request.message,
                    rollbackState = rollbackState,
                    optimisticVersion = request.optimisticVersion,
                    profileId = session.profileId
                )
            )
            MutationResult.Success
        }.getOrElse {
            MutationResult.Failed
        }
    }

    private suspend fun enqueueScrobble(
        request: WatchingMutationRequest.Scrobble,
        rollbackState: TraktWatchingNowStateController.Snapshot
    ): MutationResult {
        val action = request.action
        val item = request.item
        val clampedProgress = request.progressPercent.coerceIn(0f, 100f)
        if (shouldSkip(action, item.itemKey, clampedProgress)) return MutationResult.Success
        return runCatching {
            val session = traktAuthService.currentAuthSession()
            traktMutationOutboxCoordinator.enqueueAndDrain(
                TraktScrobbleMutationAdapter.buildScrobbleEnvelope(
                    item = item,
                    action = action,
                    progressPercent = clampedProgress,
                    rollbackState = rollbackState,
                    optimisticVersion = request.optimisticVersion,
                    profileId = session.profileId
                )
            )
            lastScrobbleStamp = ScrobbleStamp(
                action = action,
                itemKey = item.itemKey,
                progress = clampedProgress,
                timestampMs = System.currentTimeMillis()
            )
            MutationResult.Success
        }.getOrElse {
            MutationResult.Failed
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

}

internal sealed interface WatchingMutationRequest {
    val optimisticVersion: Long
    fun toWatchingNowState(): TraktWatchingNowStateController.Snapshot

    data class Scrobble(
        val action: String,
        val item: TraktScrobbleItem,
        val progressPercent: Float,
        override val optimisticVersion: Long
    ) : WatchingMutationRequest {
        override fun toWatchingNowState(): TraktWatchingNowStateController.Snapshot {
            val progress = progressPercent.coerceIn(0f, 100f)
            return TraktWatchingNowStateController.Snapshot(
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
        val message: String?,
        override val optimisticVersion: Long
    ) : WatchingMutationRequest {
        override fun toWatchingNowState(): TraktWatchingNowStateController.Snapshot {
            return TraktWatchingNowStateController.Snapshot(
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
    val rollbackState: TraktWatchingNowStateController.Snapshot,
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
