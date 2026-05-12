package com.nexio.tv.data.repository

import com.nexio.tv.core.anime.AnimeIdMappingService
import com.nexio.tv.core.anime.AnimeIdSource
import com.nexio.tv.core.anime.AnimeStremioId
import com.nexio.tv.core.anime.ContentMediaKind
import com.nexio.tv.core.anime.projection.AnimeSeasonProjectionResolver
import com.nexio.tv.core.anime.projection.AnimeSourceIdentity
import com.nexio.tv.core.anime.projection.EpisodeProjectionTarget
import com.nexio.tv.core.anime.projection.SourceEpisodeCoordinate
import com.nexio.tv.core.playback.PlaybackOwnerContext
import com.nexio.tv.data.remote.dto.trakt.TraktIdsDto
import com.nexio.tv.domain.model.ProviderId
import com.nexio.tv.domain.model.ProviderIds
import com.nexio.tv.domain.model.TrackingProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

sealed interface TrackingScrobbleItem {
    val contentId: String
    /**
     * Full provider-id bundle resolved at scrobble-emit time. When present, the per-provider
     * mutation adapter prefers this over re-parsing [contentId]. Null when the player path
     * has not yet called [ScrobbleIdBundleHydrator] for this item.
     */
    val hydratedIds: com.nexio.tv.domain.model.ProviderIds?

    data class Movie(
        override val contentId: String,
        val title: String?,
        val year: Int?,
        override val hydratedIds: com.nexio.tv.domain.model.ProviderIds? = null,
    ) : TrackingScrobbleItem

    data class Episode(
        override val contentId: String,
        val showTitle: String?,
        val showYear: Int?,
        val season: Int,
        val number: Int,
        val episodeTitle: String?,
        override val hydratedIds: com.nexio.tv.domain.model.ProviderIds? = null,
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
    suspend fun checkin(item: TrackingScrobbleItem, message: String? = null, ownerProfileId: Int? = null): Boolean
    fun observeWatchingNowState(): Flow<TrackingWatchingNowState>
}

@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class DefaultTrackingScrobbleService @Inject constructor(
    private val traktScrobbleService: TraktScrobbleService,
    private val simklScrobbleService: SimklScrobbleService,
    private val trackingProviderStateService: TrackingProviderStateService,
    private val rejectionReporter: ScrobbleRejectionReporter,
    private val animeSeasonProjectionResolver: AnimeSeasonProjectionResolver,
    private val idMappingService: AnimeIdMappingService,
) : TrackingScrobbleService {

    override suspend fun scrobbleStart(item: TrackingScrobbleItem, progressPercent: Float, owner: PlaybackOwnerContext) {
        val providerState = providerState(owner)
        coroutineScope {
            if (providerState.traktAuthenticated) {
                launch {
                    toTraktItem(item)?.let { traktItem ->
                        traktScrobbleService.scrobbleStart(traktItem, progressPercent, owner.ownerProfileId)
                    }
                }
            }
            if (providerState.simklAuthenticated) {
                launch {
                    simklScrobbleService.scrobbleStart(item, progressPercent, owner.ownerProfileId)
                }
            }
        }
    }

    override suspend fun scrobbleStop(item: TrackingScrobbleItem, progressPercent: Float, owner: PlaybackOwnerContext) {
        val providerState = providerState(owner)
        coroutineScope {
            if (providerState.traktAuthenticated) {
                launch {
                    toTraktItem(item)?.let { traktItem ->
                        traktScrobbleService.scrobbleStop(traktItem, progressPercent, owner.ownerProfileId)
                    }
                }
            }
            if (providerState.simklAuthenticated) {
                launch {
                    simklScrobbleService.scrobbleStop(item, progressPercent, owner.ownerProfileId)
                }
            }
        }
    }

    override suspend fun scrobblePause(item: TrackingScrobbleItem, progressPercent: Float, owner: PlaybackOwnerContext) {
        val providerState = providerState(owner)
        coroutineScope {
            if (providerState.traktAuthenticated) {
                launch {
                    toTraktItem(item)?.let { traktItem ->
                        traktScrobbleService.scrobblePause(traktItem, progressPercent, owner.ownerProfileId)
                    }
                }
            }
            if (providerState.simklAuthenticated) {
                launch {
                    simklScrobbleService.scrobblePause(item, progressPercent, owner.ownerProfileId)
                }
            }
        }
    }

    override suspend fun checkin(item: TrackingScrobbleItem, message: String?, ownerProfileId: Int?): Boolean {
        // F2-S-04: ownerSessionId is NOT threaded through the checkin path — checkin is an ambient
        // action (from HomeScreen or MetaDetails, outside a playback session). The sub-services fall
        // back to profileManager.activeProfileSession.value.sessionId for the boundary check, which
        // is correct because there is no ownerSessionId to thread here. The scrobble boundary
        // (assertCanWriteProfileState) still guards against cross-profile writes using ownerProfileId.
        // Follow-up: if checkin is ever called from within a PlayerViewModel, thread ownerSessionId
        // through TrackingScrobbleService.checkin() and its implementations. (F2-S-04)
        val providerState = providerState(ownerProfileId)
        val results = coroutineScope {
            listOfNotNull(
                if (providerState.traktAuthenticated) {
                    async {
                        toTraktItem(item)?.let {
                            traktScrobbleService.checkin(it, message, ownerProfileId)
                        } ?: false
                    }
                } else null,
                if (providerState.simklAuthenticated) {
                    async { simklScrobbleService.checkin(item, message, ownerProfileId) }
                } else null,
            ).awaitAll()
        }
        return results.any { it }
    }

    // observeWatchingNowState exposes a single-stream "now playing" badge for the UI.
    // Picking one provider is correct here: only one playback session can be active.
    // effectiveProvider is the right disambiguator until a merged-display Phase ships.
    @Suppress("DEPRECATION")
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

    private suspend fun toTraktItem(item: TrackingScrobbleItem): TraktScrobbleItem? {
        val contentId = item.contentId
        val animeId = AnimeStremioId.parse(contentId)?.takeIf { it.source in ANIME_NATIVE_SOURCES }

        if (animeId != null) {
            val resolvedKitsuId = when (animeId.source) {
                AnimeIdSource.KITSU -> animeId.value
                else -> idMappingService.resolveKitsuId(animeId, ContentMediaKind.SERIES)
            }
            if (resolvedKitsuId == null) {
                rejectionReporter.reportRejection(contentId, ScrobbleRejectionReason.NO_PARSEABLE_IDS, TrackingProvider.TRAKT)
                return null
            }
            return projectAnimeToTraktItem(item, resolvedKitsuId)
        }

        val ids = toTraktIds(parseContentIds(contentId))
        if (!ids.hasAnyId()) {
            rejectionReporter.reportRejection(contentId, ScrobbleRejectionReason.NO_PARSEABLE_IDS, TrackingProvider.TRAKT)
            return null
        }
        return when (item) {
            is TrackingScrobbleItem.Movie -> TraktScrobbleItem.Movie(item.title, item.year, ids)
            is TrackingScrobbleItem.Episode -> TraktScrobbleItem.Episode(
                item.showTitle, item.showYear, ids, item.season, item.number, item.episodeTitle
            )
        }
    }

    private suspend fun projectAnimeToTraktItem(
        item: TrackingScrobbleItem,
        sourceKitsuId: String,
    ): TraktScrobbleItem? {
        val work = animeSeasonProjectionResolver.resolveWork(
            AnimeSourceIdentity(sourceKitsuId = sourceKitsuId, animeStremioId = null)
        )
        return when (item) {
            is TrackingScrobbleItem.Movie -> {
                val ids = work.providerIds.toTraktIds()
                if (!ids.hasAnyId()) {
                    rejectionReporter.reportRejection(item.contentId, ScrobbleRejectionReason.EMPTY_ID_BUNDLE, TrackingProvider.TRAKT)
                    null
                } else TraktScrobbleItem.Movie(item.title, item.year, ids)
            }
            is TrackingScrobbleItem.Episode -> {
                val projection = animeSeasonProjectionResolver.resolveEpisodeProjection(
                    work = work,
                    sourceEpisode = SourceEpisodeCoordinate(sourceKitsuId, item.season, item.number),
                    target = EpisodeProjectionTarget.TRAKT_SCROBBLE,
                )
                val coord = projection.scrobbleCoordinate
                if (coord == null) {
                    rejectionReporter.reportRejection(
                        contentId = item.contentId,
                        reason = ScrobbleRejectionReason.ANIME_COORDINATE_UNRESOLVED,
                        provider = TrackingProvider.TRAKT,
                    )
                    null
                } else {
                    val ids = work.providerIds.toTraktIds().let { base ->
                        if (coord.provider == ProviderId.TVDB) base.copy(tvdb = coord.seriesId.toIntOrNull())
                        else base
                    }
                    TraktScrobbleItem.Episode(
                        showTitle = item.showTitle,
                        showYear = item.showYear,
                        showIds = ids,
                        season = coord.season,
                        number = coord.episode,
                        episodeTitle = item.episodeTitle,
                    )
                }
            }
        }
    }

    private fun ProviderIds.toTraktIds(): TraktIdsDto = TraktIdsDto(
        imdb = imdb,
        tmdb = tmdb?.toIntOrNull(),
        tvdb = tvdb?.toIntOrNull(),
        trakt = trakt?.toIntOrNull(),
    )

private suspend fun providerState(owner: PlaybackOwnerContext): EffectiveTrackingProviderState =
        trackingProviderStateService.currentState(owner.ownerProfileId)

    private suspend fun providerState(ownerProfileId: Int?): EffectiveTrackingProviderState =
        ownerProfileId?.let { trackingProviderStateService.currentState(it) }
            ?: trackingProviderStateService.currentState()

    private companion object {
        /** Sources that are exclusively anime-native; must be projected before scrobbling. */
        val ANIME_NATIVE_SOURCES = setOf(
            AnimeIdSource.KITSU,
            AnimeIdSource.MAL,
            AnimeIdSource.ANILIST,
            AnimeIdSource.ANIDB,
        )
    }
}
