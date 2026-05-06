package com.nexio.tv.data.repository

import com.nexio.tv.core.anime.projection.AnimeEpisodeProjection
import com.nexio.tv.core.anime.projection.AnimeGroupingConfidence
import com.nexio.tv.core.anime.projection.AnimeSeasonProjectionResolver
import com.nexio.tv.core.anime.projection.AnimeWorkGroupKey
import com.nexio.tv.core.anime.projection.AnimeWorkIdentity
import com.nexio.tv.core.anime.projection.CoordinateConfidence
import com.nexio.tv.core.anime.projection.EpisodeCoordinate
import com.nexio.tv.core.anime.projection.FallbackReason
import com.nexio.tv.core.playback.PlaybackOwnerContext
import com.nexio.tv.domain.model.ProviderId
import com.nexio.tv.domain.model.ProviderIds
import com.nexio.tv.domain.model.TrackingProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test

class TrackingScrobbleServiceAnimeRejectionTest {

    private fun owner(profileId: Int = 1): PlaybackOwnerContext =
        PlaybackOwnerContext(
            ownerProfileId = profileId,
            ownerSessionId = "session-$profileId",
            startedAtEpochMs = 1L
        )

    private fun traktOnlyStateService(): TrackingProviderStateService =
        mockk {
            val state = EffectiveTrackingProviderState(
                effectiveProvider = TrackingProvider.TRAKT,
                traktAuthenticated = true,
                simklAuthenticated = false
            )
            coEvery { currentState() } returns state
            coEvery { currentState(any()) } returns state
            every { currentProfileId() } returns 1
            every { this@mockk.state } returns flowOf(state)
        }

    @Test
    fun `scrobbleStart with kitsu content id does not call traktScrobbleService and reports visible rejection`() = runTest {
        val traktService = mockk<TraktScrobbleService>(relaxed = true)
        val simklService = mockk<SimklScrobbleService>(relaxed = true)
        val rejectionReporter = mockk<ScrobbleRejectionReporter>(relaxed = true)
        val resolver = mockk<AnimeSeasonProjectionResolver>()

        // kitsu:13881 is now routed through the projection resolver;
        // resolver returns a projection with no scrobble coordinate → ANIME_COORDINATE_UNRESOLVED
        val work = AnimeWorkIdentity(
            groupKey = AnimeWorkGroupKey("anime-work:kitsu:13881"),
            primaryKitsuId = "13881",
            memberKitsuIds = setOf("13881"),
            providerIds = ProviderIds(),
            confidence = AnimeGroupingConfidence.LOW,
            evidence = emptyList(),
        )
        coEvery { resolver.resolveWork(any()) } returns work
        coEvery { resolver.resolveEpisodeProjection(any(), any(), any()) } returns AnimeEpisodeProjection(
            sourceKitsuId = "13881",
            sourceKitsuCoordinate = EpisodeCoordinate(ProviderId.KITSU, "13881", 1, 1),
            displayCoordinate = EpisodeCoordinate(ProviderId.KITSU, "13881", 1, 1),
            targetCoordinate = null,
            scrobbleCoordinate = null,
            premiumArtworkCoordinate = null,
            tvdbCoordinate = null,
            tmdbCoordinate = null,
            confidence = CoordinateConfidence.LOW,
            fallbackReason = FallbackReason.NO_CURATED_SEASON,
            evidence = emptyList(),
        )

        val service = DefaultTrackingScrobbleService(
            traktScrobbleService = traktService,
            simklScrobbleService = simklService,
            trackingProviderStateService = traktOnlyStateService(),
            rejectionReporter = rejectionReporter,
            animeSeasonProjectionResolver = resolver,
            idMappingService = mockk(relaxed = true),
        )

        service.scrobbleStart(
            TrackingScrobbleItem.Episode(
                contentId = "kitsu:13881",
                showTitle = "Some Anime",
                showYear = 2023,
                season = 1,
                number = 1,
                episodeTitle = "Episode 1"
            ),
            progressPercent = 10f,
            owner = owner()
        )

        coVerify(exactly = 0) { traktService.scrobbleStart(any(), any(), any()) }
        // kitsu: ids now flow through the projection resolver; unresolvable coordinates emit
        // ANIME_COORDINATE_UNRESOLVED (not NO_PARSEABLE_IDS) to preserve the invariant that
        // anime-native ids never silently no-op
        verify(exactly = 1) {
            rejectionReporter.reportRejection(
                contentId = "kitsu:13881",
                reason = ScrobbleRejectionReason.ANIME_COORDINATE_UNRESOLVED,
                provider = TrackingProvider.TRAKT
            )
        }
    }
}
