package com.nexio.tv.data.integration.posters

import com.nexio.tv.core.anime.AnimeIdSource
import com.nexio.tv.core.anime.AnimeStremioId
import com.nexio.tv.core.anime.projection.AnimeEpisodeProjection
import com.nexio.tv.core.anime.projection.AnimeGroupingConfidence
import com.nexio.tv.core.anime.projection.AnimeSeasonProjectionResolver
import com.nexio.tv.core.anime.projection.AnimeSourceIdentity
import com.nexio.tv.core.anime.projection.AnimeWorkGroupKey
import com.nexio.tv.core.anime.projection.AnimeWorkIdentity
import com.nexio.tv.core.anime.projection.CoordinateConfidence
import com.nexio.tv.core.anime.projection.EpisodeCoordinate
import com.nexio.tv.core.anime.projection.EpisodeProjectionTarget
import com.nexio.tv.core.anime.projection.SourceEpisodeCoordinate
import com.nexio.tv.core.image.PosterIntegrationRequest
import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.core.metadata.router.MetadataDecisionReason
import com.nexio.tv.core.metadata.router.MetadataMediaKind
import com.nexio.tv.core.metadata.router.MetadataPrimaryProvider
import com.nexio.tv.core.metadata.router.MetadataRoute
import com.nexio.tv.core.metadata.router.MetadataRouteTrace
import com.nexio.tv.core.metadata.router.MetadataSourceContext
import com.nexio.tv.core.poster.PosterRatingsUrlResolver
import com.nexio.tv.data.local.PosterRatingsSettingsDataStore
import com.nexio.tv.domain.model.ProviderId
import com.nexio.tv.domain.model.ProviderIds
import com.nexio.tv.domain.model.PosterRatingsSettings
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests that [TopPostersMetadataProviderAdapter.resolveEpisodeThumbnailUrl] routes anime
 * episode thumbnail requests through [AnimeSeasonProjectionResolver] and applies the correct
 * projected coordinates for the Top-Posters call.
 *
 * Trigger condition (Correction 7): BOTH signals are checked:
 *   route?.provider == MetadataPrimaryProvider.KITSU
 *   OR AnimeStremioId.parse(sourceContentId) != null
 */
class TopPostersMetadataProviderAdapterAnimeProjectionTest {

    // region HIGH-confidence — projected TVDB coordinates are used

    @Test
    fun `kitsu anime with HIGH confidence projection uses TVDB season and episode for thumbnail`() = runTest {
        val tvdbSeriesId = "305074"
        val projectionResolver = mockk<AnimeSeasonProjectionResolver>()

        val work = animeWorkIdentity(tvdbSeriesId = tvdbSeriesId)
        val projection = animeProjection(
            sourceKitsuId = "13881",
            season = 3, episode = 1,
            tvdbSeriesId = tvdbSeriesId,
            confidence = CoordinateConfidence.HIGH,
        )

        coEvery {
            projectionResolver.resolveWork(
                AnimeSourceIdentity(
                    sourceKitsuId = "13881",
                    animeStremioId = AnimeStremioId(source = AnimeIdSource.KITSU, value = "13881"),
                )
            )
        } returns work
        coEvery {
            projectionResolver.resolveEpisodeProjection(
                work = work,
                sourceEpisode = SourceEpisodeCoordinate(sourceKitsuId = "13881", season = 3, episode = 1),
                target = EpisodeProjectionTarget.PREMIUM_THUMBNAIL,
            )
        } returns projection

        val adapter = TopPostersMetadataProviderAdapter(
            posterResolver = resolver(PosterRatingsSettings(topPostersEnabled = true, topPostersApiKey = "test-key")),
            animeSeasonProjectionResolver = projectionResolver,
        )
        val route = kitsuRoute(parentId = "kitsu:13881", tvdbId = tvdbSeriesId)

        val thumbnailUrl = adapter.resolveEpisodeThumbnailUrl(
            route = route,
            sourceContentId = "kitsu:13881",
            season = 3,
            episode = 1,
        )

        assertNotNull("Thumbnail URL must not be null for HIGH-confidence anime", thumbnailUrl)
        val request = PosterIntegrationRequest.fromModel(thumbnailUrl!!)
        assertNotNull("Thumbnail URL must encode a PosterIntegrationRequest", request)
        assertEquals(IntegrationProvider.TOP_POSTERS, request!!.provider)
        // Projected coordinates: TVDB seriesId=305074, season=3, episode=1
        assertEquals(
            "tvdb/thumbnail/$tvdbSeriesId/s3e1.jpg",
            request.path
        )
    }

    // endregion

    // region LOW-confidence flat-franchise — adapter returns null so caller uses primary thumbnail

    @Test
    fun `One Piece flat-franchise with LOW confidence projection returns null thumbnail`() = runTest {
        val tvdbSeriesId = "81797"
        val projectionResolver = mockk<AnimeSeasonProjectionResolver>()

        val work = animeWorkIdentity(tvdbSeriesId = tvdbSeriesId)
        // LOW-confidence projection has premiumArtworkCoordinate == null
        val projection = animeProjection(
            sourceKitsuId = "12",
            season = 1, episode = 1,
            tvdbSeriesId = tvdbSeriesId,
            confidence = CoordinateConfidence.LOW,
            premiumArtworkCoordinate = null,
        )

        coEvery {
            projectionResolver.resolveWork(
                AnimeSourceIdentity(
                    sourceKitsuId = "12",
                    animeStremioId = AnimeStremioId(source = AnimeIdSource.KITSU, value = "12"),
                )
            )
        } returns work
        coEvery {
            projectionResolver.resolveEpisodeProjection(
                work = work,
                sourceEpisode = SourceEpisodeCoordinate(sourceKitsuId = "12", season = 1, episode = 1),
                target = EpisodeProjectionTarget.PREMIUM_THUMBNAIL,
            )
        } returns projection

        val adapter = TopPostersMetadataProviderAdapter(
            posterResolver = resolver(PosterRatingsSettings(topPostersEnabled = true, topPostersApiKey = "test-key")),
            animeSeasonProjectionResolver = projectionResolver,
        )
        val route = kitsuRoute(parentId = "kitsu:12", tvdbId = tvdbSeriesId)

        val thumbnailUrl = adapter.resolveEpisodeThumbnailUrl(
            route = route,
            sourceContentId = "kitsu:12",
            season = 1,
            episode = 1,
        )

        assertNull(
            "Flat-franchise with LOW-confidence projection must return null so caller falls back to primary thumbnail",
            thumbnailUrl
        )
    }

    // endregion

    // region Non-anime content — anime projection is not triggered

    @Test
    fun `non-anime TMDB route bypasses projection resolver and returns null`() = runTest {
        val projectionResolver = mockk<AnimeSeasonProjectionResolver>(relaxed = true)

        val adapter = TopPostersMetadataProviderAdapter(
            posterResolver = resolver(PosterRatingsSettings(topPostersEnabled = true, topPostersApiKey = "test-key")),
            animeSeasonProjectionResolver = projectionResolver,
        )
        val route = tmdbRoute(parentId = "tt0944947", tmdbId = "tmdb:1234")

        val thumbnailUrl = adapter.resolveEpisodeThumbnailUrl(
            route = route,
            sourceContentId = "tt0944947",
            season = 1,
            episode = 1,
        )

        // Non-anime route: no projection, return null (no Top-Posters episode thumbnail for non-anime)
        assertNull("Non-anime route must not use projection resolver and must return null", thumbnailUrl)
    }

    // endregion

    // region Helpers

    private fun resolver(settings: PosterRatingsSettings): PosterRatingsUrlResolver {
        val dataStore = mockk<PosterRatingsSettingsDataStore>()
        io.mockk.every { dataStore.settings } returns flowOf(settings)
        return PosterRatingsUrlResolver(dataStore)
    }

    private fun kitsuRoute(parentId: String, tvdbId: String): MetadataRoute = MetadataRoute(
        provider = MetadataPrimaryProvider.KITSU,
        parentId = parentId,
        mediaKind = MetadataMediaKind.ANIME,
        reason = MetadataDecisionReason.KITSU_PREFIX_DIRECT,
        sourceContext = MetadataSourceContext(),
        targetIds = mapOf(
            MetadataPrimaryProvider.KITSU to parentId,
            MetadataPrimaryProvider.TVDB to tvdbId,
        ),
        trace = listOf(MetadataRouteTrace(MetadataDecisionReason.KITSU_PREFIX_DIRECT, "test")),
    )

    private fun tmdbRoute(parentId: String, tmdbId: String): MetadataRoute = MetadataRoute(
        provider = MetadataPrimaryProvider.TMDB,
        parentId = parentId,
        mediaKind = MetadataMediaKind.SERIES,
        reason = MetadataDecisionReason.PROVIDER_NATIVE_DIRECT,
        sourceContext = MetadataSourceContext(),
        targetIds = mapOf(MetadataPrimaryProvider.TMDB to tmdbId),
        trace = listOf(MetadataRouteTrace(MetadataDecisionReason.PROVIDER_NATIVE_DIRECT, "test")),
    )

    private fun animeWorkIdentity(tvdbSeriesId: String): AnimeWorkIdentity = AnimeWorkIdentity(
        groupKey = AnimeWorkGroupKey("anime-work:tvdb:$tvdbSeriesId"),
        primaryKitsuId = null,
        memberKitsuIds = emptySet(),
        providerIds = ProviderIds(tvdb = tvdbSeriesId),
        confidence = AnimeGroupingConfidence.HIGH,
        evidence = listOf("kitsu.tvdb=$tvdbSeriesId"),
    )

    private fun animeProjection(
        sourceKitsuId: String,
        season: Int,
        episode: Int,
        tvdbSeriesId: String,
        confidence: CoordinateConfidence,
        premiumArtworkCoordinate: EpisodeCoordinate? = EpisodeCoordinate(
            provider = ProviderId.TVDB,
            seriesId = tvdbSeriesId,
            season = season,
            episode = episode,
        ),
    ): AnimeEpisodeProjection {
        val sourceCoord = EpisodeCoordinate(
            provider = ProviderId.KITSU,
            seriesId = sourceKitsuId,
            season = season,
            episode = episode,
        )
        val tvdbCoord = if (confidence != CoordinateConfidence.LOW) {
            EpisodeCoordinate(ProviderId.TVDB, tvdbSeriesId, season, episode)
        } else null
        return AnimeEpisodeProjection(
            sourceKitsuId = sourceKitsuId,
            sourceKitsuCoordinate = sourceCoord,
            displayCoordinate = tvdbCoord ?: sourceCoord,
            targetCoordinate = premiumArtworkCoordinate,
            scrobbleCoordinate = tvdbCoord,
            premiumArtworkCoordinate = premiumArtworkCoordinate,
            tvdbCoordinate = tvdbCoord,
            tmdbCoordinate = null,
            confidence = confidence,
            fallbackReason = if (confidence == CoordinateConfidence.LOW)
                com.nexio.tv.core.anime.projection.FallbackReason.LOW_CONFIDENCE_FLAT_KITSU else null,
            evidence = listOfNotNull(tvdbCoord?.let { "kitsu.tvdb=$tvdbSeriesId" }),
        )
    }

    // endregion
}
