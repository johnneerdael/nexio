package com.nexio.tv.data.repository

import com.nexio.tv.core.anime.AnimeIdMappingService
import com.nexio.tv.core.anime.projection.AnimeEpisodeProjection
import com.nexio.tv.core.anime.projection.AnimeGroupingConfidence
import com.nexio.tv.core.anime.projection.AnimeSeasonProjectionResolver
import com.nexio.tv.core.anime.projection.AnimeWorkGroupKey
import com.nexio.tv.core.anime.projection.AnimeWorkIdentity
import com.nexio.tv.core.anime.projection.CoordinateConfidence
import com.nexio.tv.core.anime.projection.EpisodeCoordinate
import com.nexio.tv.core.anime.projection.EpisodeProjectionTarget
import com.nexio.tv.core.anime.projection.SourceEpisodeCoordinate
import com.nexio.tv.core.playback.PlaybackOwnerContext
import com.nexio.tv.domain.model.ProviderId
import com.nexio.tv.domain.model.ProviderIds
import com.nexio.tv.domain.model.TrackingProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Test

class TrackingScrobbleServiceAnimeProjectionTest {

    @Test
    fun `kitsu MHA S3E1 scrobbles with TVDB season 3 episode 1`() = runBlocking {
        val trakt = mockk<TraktScrobbleService>(relaxed = true)
        val simkl = mockk<SimklScrobbleService>(relaxed = true)
        val providerState = mockk<TrackingProviderStateService>()
        val rejection = mockk<ScrobbleRejectionReporter>(relaxed = true)
        val resolver = mockk<AnimeSeasonProjectionResolver>()

        coEvery { providerState.currentState(any<Int>()) } returns EffectiveTrackingProviderState(
            effectiveProvider = TrackingProvider.TRAKT, traktAuthenticated = true, simklAuthenticated = false
        )
        val work = AnimeWorkIdentity(
            groupKey = AnimeWorkGroupKey("anime-work:tvdb:305074"),
            primaryKitsuId = "11469",
            memberKitsuIds = setOf("11469", "13881"),
            providerIds = ProviderIds(tvdb = "305074", imdb = "tt5626028"),
            confidence = AnimeGroupingConfidence.HIGH,
            evidence = emptyList(),
        )
        coEvery { resolver.resolveWork(any()) } returns work
        coEvery {
            resolver.resolveEpisodeProjection(work, SourceEpisodeCoordinate("13881", 3, 1), EpisodeProjectionTarget.TRAKT_SCROBBLE)
        } returns AnimeEpisodeProjection(
            sourceKitsuId = "13881",
            sourceKitsuCoordinate = EpisodeCoordinate(ProviderId.KITSU, "13881", 3, 1),
            displayCoordinate = EpisodeCoordinate(ProviderId.TVDB, "305074", 3, 1),
            targetCoordinate = EpisodeCoordinate(ProviderId.TVDB, "305074", 3, 1),
            scrobbleCoordinate = EpisodeCoordinate(ProviderId.TVDB, "305074", 3, 1),
            premiumArtworkCoordinate = null,
            tvdbCoordinate = EpisodeCoordinate(ProviderId.TVDB, "305074", 3, 1),
            tmdbCoordinate = null,
            confidence = CoordinateConfidence.HIGH,
            fallbackReason = null,
            evidence = emptyList(),
        )

        val service = buildService(trakt, simkl, providerState, rejection, resolver)

        service.scrobbleStart(
            item = TrackingScrobbleItem.Episode(
                contentId = "kitsu:13881",
                showTitle = "Boku no Hero Academia 3",
                showYear = 2018,
                season = 3,
                number = 1,
                episodeTitle = "Game Start",
            ),
            progressPercent = 5f,
            owner = PlaybackOwnerContext(ownerProfileId = 1, ownerSessionId = "s1", startedAtEpochMs = 1L),
        )

        coVerify {
            trakt.scrobbleStart(
                match { it is TraktScrobbleItem.Episode && it.season == 3 && it.number == 1 && it.showIds.tvdb == 305074 },
                5f, 1, any()
            )
        }
        coVerify(exactly = 0) { rejection.reportRejection(any(), any(), any()) }
    }

    @Test
    fun `One Piece flat-franchise emits ANIME_COORDINATE_UNRESOLVED not silent noop`() = runBlocking {
        val trakt = mockk<TraktScrobbleService>(relaxed = true)
        val simkl = mockk<SimklScrobbleService>(relaxed = true)
        val providerState = mockk<TrackingProviderStateService>()
        val rejection = mockk<ScrobbleRejectionReporter>(relaxed = true)
        val resolver = mockk<AnimeSeasonProjectionResolver>()

        coEvery { providerState.currentState(any<Int>()) } returns EffectiveTrackingProviderState(
            effectiveProvider = TrackingProvider.TRAKT, traktAuthenticated = true, simklAuthenticated = false
        )
        val work = AnimeWorkIdentity(
            groupKey = AnimeWorkGroupKey("anime-work:tvdb:81797"),
            primaryKitsuId = "12",
            memberKitsuIds = setOf("12"),
            providerIds = ProviderIds(tvdb = "81797"),
            confidence = AnimeGroupingConfidence.HIGH,
            evidence = emptyList(),
        )
        coEvery { resolver.resolveWork(any()) } returns work
        coEvery {
            resolver.resolveEpisodeProjection(work, SourceEpisodeCoordinate("12", 1, 850), EpisodeProjectionTarget.TRAKT_SCROBBLE)
        } returns AnimeEpisodeProjection(
            sourceKitsuId = "12",
            sourceKitsuCoordinate = EpisodeCoordinate(ProviderId.KITSU, "12", 1, 850),
            displayCoordinate = EpisodeCoordinate(ProviderId.KITSU, "12", 1, 850),
            targetCoordinate = null,
            scrobbleCoordinate = null,
            premiumArtworkCoordinate = null,
            tvdbCoordinate = null,
            tmdbCoordinate = null,
            confidence = CoordinateConfidence.LOW,
            fallbackReason = com.nexio.tv.core.anime.projection.FallbackReason.NO_CURATED_SEASON,
            evidence = emptyList(),
        )

        val service = buildService(trakt, simkl, providerState, rejection, resolver)

        service.scrobbleStart(
            item = TrackingScrobbleItem.Episode(
                contentId = "kitsu:12",
                showTitle = "One Piece",
                showYear = 1999,
                season = 1,
                number = 850,
                episodeTitle = null,
            ),
            progressPercent = 50f,
            owner = PlaybackOwnerContext(ownerProfileId = 1, ownerSessionId = "s1", startedAtEpochMs = 1L),
        )

        coVerify(exactly = 0) { trakt.scrobbleStart(any(), any(), any(), any()) }
        coVerify(exactly = 1) {
            rejection.reportRejection(
                contentId = "kitsu:12",
                reason = ScrobbleRejectionReason.ANIME_COORDINATE_UNRESOLVED,
                provider = TrackingProvider.TRAKT,
            )
        }
    }

    private fun buildService(
        trakt: TraktScrobbleService,
        simkl: SimklScrobbleService,
        providerState: TrackingProviderStateService,
        rejection: ScrobbleRejectionReporter,
        resolver: AnimeSeasonProjectionResolver,
    ) = DefaultTrackingScrobbleService(
        traktScrobbleService = trakt,
        simklScrobbleService = simkl,
        trackingProviderStateService = providerState,
        rejectionReporter = rejection,
        animeSeasonProjectionResolver = resolver,
        idMappingService = mockk(relaxed = true),
    )
}
