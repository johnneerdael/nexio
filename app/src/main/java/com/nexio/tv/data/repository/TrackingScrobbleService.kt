package com.nexio.tv.data.repository

import com.nexio.tv.core.playback.PlaybackOwnerContext
import com.nexio.tv.domain.model.TrackingProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

sealed interface TrackingScrobbleItem {
    data class Movie(
        val contentId: String,
        val title: String?,
        val year: Int?
    ) : TrackingScrobbleItem

    data class Episode(
        val contentId: String,
        val showTitle: String?,
        val showYear: Int?,
        val season: Int,
        val number: Int,
        val episodeTitle: String?
    ) : TrackingScrobbleItem
}

data class TrackingWatchingNowState(
    val active: Boolean = false,
    val title: String? = null,
    val contentType: String? = null,
    val progressPercent: Float? = null,
    val updatedAtMs: Long = 0L
)

interface TrackingScrobbleService {
    suspend fun scrobbleStart(item: TrackingScrobbleItem, progressPercent: Float, owner: PlaybackOwnerContext)
    suspend fun scrobbleStop(item: TrackingScrobbleItem, progressPercent: Float, owner: PlaybackOwnerContext)
    suspend fun scrobblePause(item: TrackingScrobbleItem, progressPercent: Float, owner: PlaybackOwnerContext)
    suspend fun checkin(item: TrackingScrobbleItem, message: String? = null, owner: PlaybackOwnerContext): Boolean
    fun observeWatchingNowState(): Flow<TrackingWatchingNowState>
}

@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class DefaultTrackingScrobbleService @Inject constructor(
    private val traktScrobbleService: TraktScrobbleService,
    private val simklScrobbleService: SimklScrobbleService,
    private val trackingProviderStateService: TrackingProviderStateService
) : TrackingScrobbleService {

    override suspend fun scrobbleStart(item: TrackingScrobbleItem, progressPercent: Float, owner: PlaybackOwnerContext) {
        val providerState = providerState(owner)
        when (providerState.effectiveProvider) {
            TrackingProvider.SIMKL -> {
                if (!providerState.simklAuthenticated) return
                simklScrobbleService.scrobbleStart(item, progressPercent, owner.ownerProfileId)
            }
            TrackingProvider.TRAKT -> {
                if (!providerState.traktAuthenticated) return
                toTraktItem(item)?.let { traktScrobbleService.scrobbleStart(it, progressPercent, owner.ownerProfileId) }
            }
        }
    }

    override suspend fun scrobbleStop(item: TrackingScrobbleItem, progressPercent: Float, owner: PlaybackOwnerContext) {
        val providerState = providerState(owner)
        when (providerState.effectiveProvider) {
            TrackingProvider.SIMKL -> {
                if (!providerState.simklAuthenticated) return
                simklScrobbleService.scrobbleStop(item, progressPercent, owner.ownerProfileId)
            }
            TrackingProvider.TRAKT -> {
                if (!providerState.traktAuthenticated) return
                toTraktItem(item)?.let { traktScrobbleService.scrobbleStop(it, progressPercent, owner.ownerProfileId) }
            }
        }
    }

    override suspend fun scrobblePause(item: TrackingScrobbleItem, progressPercent: Float, owner: PlaybackOwnerContext) {
        val providerState = providerState(owner)
        when (providerState.effectiveProvider) {
            TrackingProvider.SIMKL -> {
                if (!providerState.simklAuthenticated) return
                simklScrobbleService.scrobblePause(item, progressPercent, owner.ownerProfileId)
            }
            TrackingProvider.TRAKT -> {
                if (!providerState.traktAuthenticated) return
                toTraktItem(item)?.let { traktScrobbleService.scrobblePause(it, progressPercent, owner.ownerProfileId) }
            }
        }
    }

    override suspend fun checkin(item: TrackingScrobbleItem, message: String?, owner: PlaybackOwnerContext): Boolean {
        val providerState = providerState(owner)
        return when (providerState.effectiveProvider) {
            TrackingProvider.SIMKL -> {
                if (!providerState.simklAuthenticated) return false
                simklScrobbleService.checkin(item, message, owner.ownerProfileId)
            }
            TrackingProvider.TRAKT -> {
                if (!providerState.traktAuthenticated) return false
                val traktItem = toTraktItem(item) ?: return false
                traktScrobbleService.checkin(traktItem, message, owner.ownerProfileId)
            }
        }
    }

    override fun observeWatchingNowState(): Flow<TrackingWatchingNowState> {
        return trackingProviderStateService.state.flatMapLatest { state ->
            when (state.effectiveProvider) {
                TrackingProvider.SIMKL -> simklScrobbleService.observeWatchingNowState().map { it.toTrackingState() }
                TrackingProvider.TRAKT -> traktScrobbleService.observeWatchingNowState().map { it.toTrackingState() }
            }
        }
    }

    private fun TraktScrobbleService.WatchingNowState.toTrackingState(): TrackingWatchingNowState {
        return TrackingWatchingNowState(
            active = active,
            title = title,
            contentType = contentType,
            progressPercent = progressPercent,
            updatedAtMs = updatedAtMs
        )
    }

    private fun SimklScrobbleService.WatchingNowState.toTrackingState(): TrackingWatchingNowState {
        return TrackingWatchingNowState(
            active = active,
            title = title,
            contentType = contentType,
            progressPercent = progressPercent,
            updatedAtMs = updatedAtMs
        )
    }

    private fun toTraktItem(item: TrackingScrobbleItem): TraktScrobbleItem? {
        val ids = toTraktIds(parseContentIds(item.contentId()))
        if (!ids.hasAnyId()) return null
        return when (item) {
            is TrackingScrobbleItem.Movie -> TraktScrobbleItem.Movie(
                title = item.title,
                year = item.year,
                ids = ids
            )

            is TrackingScrobbleItem.Episode -> TraktScrobbleItem.Episode(
                showTitle = item.showTitle,
                showYear = item.showYear,
                showIds = ids,
                season = item.season,
                number = item.number,
                episodeTitle = item.episodeTitle
            )
        }
    }

    private fun TrackingScrobbleItem.contentId(): String = when (this) {
        is TrackingScrobbleItem.Movie -> contentId
        is TrackingScrobbleItem.Episode -> contentId
    }

    private suspend fun providerState(owner: PlaybackOwnerContext): EffectiveTrackingProviderState =
        trackingProviderStateService.currentState(owner.ownerProfileId)
}
