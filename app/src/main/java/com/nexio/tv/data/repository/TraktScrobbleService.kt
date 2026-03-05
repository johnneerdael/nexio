package com.nexio.tv.data.repository

import com.nexio.tv.BuildConfig
import com.nexio.tv.data.remote.api.TraktApi
import com.nexio.tv.data.remote.dto.trakt.TraktEpisodeDto
import com.nexio.tv.data.remote.dto.trakt.TraktCheckinRequestDto
import com.nexio.tv.data.remote.dto.trakt.TraktIdsDto
import com.nexio.tv.data.remote.dto.trakt.TraktMovieDto
import com.nexio.tv.data.remote.dto.trakt.TraktScrobbleRequestDto
import com.nexio.tv.data.remote.dto.trakt.TraktShowDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    private var lastScrobbleStamp: ScrobbleStamp? = null
    private val minSendIntervalMs = 8_000L
    private val progressWindow = 1.5f

    suspend fun scrobbleStart(item: TraktScrobbleItem, progressPercent: Float) {
        sendScrobble(action = "start", item = item, progressPercent = progressPercent)
    }

    suspend fun scrobbleStop(item: TraktScrobbleItem, progressPercent: Float) {
        sendScrobble(action = "stop", item = item, progressPercent = progressPercent)
    }

    suspend fun scrobblePause(item: TraktScrobbleItem, progressPercent: Float) {
        sendScrobble(action = "pause", item = item, progressPercent = progressPercent)
    }

    suspend fun checkin(item: TraktScrobbleItem, message: String? = null): Boolean {
        if (!traktAuthService.getCurrentAuthState().isAuthenticated) return false
        if (!traktAuthService.hasRequiredCredentials()) return false

        val requestBody = buildCheckinRequestBody(item = item, message = message)
        val response = traktAuthService.executeAuthorizedWriteRequest { authHeader ->
            traktApi.checkin(authHeader, requestBody)
        } ?: return false

        if (response.isSuccessful || response.code() == 409) {
            traktProgressService.refreshNow()
            updateWatchingNowState(
                active = true,
                item = item,
                progressPercent = null
            )
            return true
        }
        return false
    }

    fun observeWatchingNowState(): StateFlow<WatchingNowState> = watchingNowState.asStateFlow()

    private suspend fun sendScrobble(
        action: String,
        item: TraktScrobbleItem,
        progressPercent: Float
    ) {
        if (!traktAuthService.getCurrentAuthState().isAuthenticated) return
        if (!traktAuthService.hasRequiredCredentials()) return

        val clampedProgress = progressPercent.coerceIn(0f, 100f)
        if (shouldSkip(action, item.itemKey, clampedProgress)) return

        val requestBody = buildRequestBody(item, clampedProgress)

        val response = traktAuthService.executeAuthorizedWriteRequest { authHeader ->
            when (action) {
                "start" -> traktApi.scrobbleStart(authHeader, requestBody)
                "pause" -> traktApi.scrobblePause(authHeader, requestBody)
                else -> traktApi.scrobbleStop(authHeader, requestBody)
            }
        } ?: return

        if (response.isSuccessful || response.code() == 409) {
            lastScrobbleStamp = ScrobbleStamp(
                action = action,
                itemKey = item.itemKey,
                progress = clampedProgress,
                timestampMs = System.currentTimeMillis()
            )
            when (action) {
                "start" -> updateWatchingNowState(
                    active = true,
                    item = item,
                    progressPercent = clampedProgress
                )

                "pause", "stop" -> updateWatchingNowState(
                    active = false,
                    item = item,
                    progressPercent = clampedProgress
                )
            }
            if (action == "stop") {
                traktProgressService.refreshNow()
            }
        }
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
        val (title, contentType) = when (item) {
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
        }
        watchingNowState.value = WatchingNowState(
            active = active,
            title = title?.takeIf { it.isNotBlank() },
            contentType = contentType,
            progressPercent = progressPercent,
            updatedAtMs = System.currentTimeMillis()
        )
    }
}
