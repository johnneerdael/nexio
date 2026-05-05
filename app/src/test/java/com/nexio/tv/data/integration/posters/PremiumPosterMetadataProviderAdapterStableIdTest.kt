package com.nexio.tv.data.integration.posters

import com.nexio.tv.core.image.PosterIntegrationRequest
import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.core.integration.PosterApiShapes
import com.nexio.tv.core.metadata.router.MetadataDecisionReason
import com.nexio.tv.core.metadata.router.MetadataMediaKind
import com.nexio.tv.core.metadata.router.MetadataPrimaryProvider
import com.nexio.tv.core.metadata.router.MetadataRoute
import com.nexio.tv.core.metadata.router.MetadataRouteTrace
import com.nexio.tv.core.metadata.router.MetadataSourceContext
import com.nexio.tv.core.metadata.router.ProviderPlanRole
import com.nexio.tv.core.metadata.router.ProviderPlanStep
import com.nexio.tv.core.metadata.router.ResolvedField
import com.nexio.tv.core.poster.PosterRatingsUrlResolver
import com.nexio.tv.data.local.PosterRatingsSettingsDataStore
import com.nexio.tv.domain.model.PosterRatingsSettings
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class PremiumPosterMetadataProviderAdapterStableIdTest {

    @Test
    fun `top posters candidate request uses provider native tmdb target id instead of route parent id`() = runTest {
        val adapter = TopPostersMetadataProviderAdapter(
            posterResolver = resolver(
                PosterRatingsSettings(
                    topPostersEnabled = true,
                    topPostersApiKey = "top-key"
                )
            ),
            animeSeasonProjectionResolver = mockk(relaxed = true),
        )
        val route = route(
            provider = MetadataPrimaryProvider.TMDB,
            mediaKind = MetadataMediaKind.MOVIE,
            parentId = "catalog-row-item-42",
            targetIds = mapOf(
                MetadataPrimaryProvider.TMDB to "tmdb:550",
                MetadataPrimaryProvider.IMDB to "tt0137523"
            )
        )

        val result = adapter.execute(
            route = route,
            step = posterStep(MetadataPrimaryProvider.TOP_POSTERS, PosterApiShapes.TOP_POSTERS_POSTER_TEMPLATE)
        )

        val request = posterRequest(result.candidate?.fields?.get(ResolvedField.POSTER)?.value)
        assertEquals(IntegrationProvider.TOP_POSTERS, request?.provider)
        assertEquals("tmdb/poster/movie-550.jpg", request?.path)
        assertFalse(request?.path.orEmpty().contains(route.parentId))
    }

    @Test
    fun `rpdb candidate request uses stable tmdb target id instead of route parent id`() = runTest {
        val adapter = RpdbMetadataProviderAdapter(
            posterResolver = resolver(
                PosterRatingsSettings(
                    rpdbEnabled = true,
                    rpdbApiKey = "rpdb-key"
                )
            )
        )
        val route = route(
            provider = MetadataPrimaryProvider.TMDB,
            mediaKind = MetadataMediaKind.MOVIE,
            parentId = "catalog-row-item-99",
            targetIds = mapOf(MetadataPrimaryProvider.TMDB to "tmdb:550")
        )

        val result = adapter.execute(
            route = route,
            step = posterStep(MetadataPrimaryProvider.RPDB, PosterApiShapes.RPDB_POSTER_TEMPLATE)
        )

        val request = posterRequest(result.candidate?.fields?.get(ResolvedField.POSTER)?.value)
        assertEquals(IntegrationProvider.RPDB, request?.provider)
        assertEquals("tmdb/poster-default/movie-550.jpg", request?.path)
        assertFalse(request?.path.orEmpty().contains(route.parentId))
    }

    @Test
    fun `kitsu only anime route uses top posters kitsu id but not rpdb`() = runTest {
        val route = route(
            provider = MetadataPrimaryProvider.KITSU,
            mediaKind = MetadataMediaKind.ANIME,
            parentId = "kitsu:7442",
            targetIds = mapOf(MetadataPrimaryProvider.KITSU to "kitsu:7442")
        )

        val rpdbResult = RpdbMetadataProviderAdapter(
            posterResolver = resolver(
                PosterRatingsSettings(
                    rpdbEnabled = true,
                    rpdbApiKey = "rpdb-key"
                )
            )
        ).execute(
            route = route,
            step = posterStep(MetadataPrimaryProvider.RPDB, PosterApiShapes.RPDB_POSTER_TEMPLATE)
        )
        val topPostersResult = TopPostersMetadataProviderAdapter(
            posterResolver = resolver(
                PosterRatingsSettings(
                    topPostersEnabled = true,
                    topPostersApiKey = "top-key"
                )
            ),
            animeSeasonProjectionResolver = mockk(relaxed = true),
        ).execute(
            route = route,
            step = posterStep(MetadataPrimaryProvider.TOP_POSTERS, PosterApiShapes.TOP_POSTERS_POSTER_TEMPLATE)
        )

        val topPostersRequest = posterRequest(topPostersResult.candidate?.fields?.get(ResolvedField.POSTER)?.value)
        assertNull(rpdbResult.candidate?.fields?.get(ResolvedField.POSTER))
        assertEquals(IntegrationProvider.TOP_POSTERS, topPostersRequest?.provider)
        assertEquals("kitsu/poster/7442.jpg", topPostersRequest?.path)
    }

    private fun resolver(settings: PosterRatingsSettings): PosterRatingsUrlResolver {
        val dataStore = mockk<PosterRatingsSettingsDataStore>()
        every { dataStore.settings } returns flowOf(settings)
        return PosterRatingsUrlResolver(dataStore)
    }

    private fun route(
        provider: MetadataPrimaryProvider,
        mediaKind: MetadataMediaKind,
        parentId: String,
        targetIds: Map<MetadataPrimaryProvider, String>
    ): MetadataRoute = MetadataRoute(
        provider = provider,
        parentId = parentId,
        mediaKind = mediaKind,
        reason = MetadataDecisionReason.PROVIDER_NATIVE_DIRECT,
        sourceContext = MetadataSourceContext(),
        targetIds = targetIds,
        trace = listOf(
            MetadataRouteTrace(
                reason = MetadataDecisionReason.PROVIDER_NATIVE_DIRECT,
                detail = "test route"
            )
        )
    )

    private fun posterStep(
        provider: MetadataPrimaryProvider,
        apiShapeId: String
    ): ProviderPlanStep = ProviderPlanStep(
        apiShapeId = apiShapeId,
        provider = provider,
        role = ProviderPlanRole.ARTWORK,
        required = false
    )

    private fun posterRequest(value: Any?): PosterIntegrationRequest? =
        PosterIntegrationRequest.fromModel(value as String)
}
