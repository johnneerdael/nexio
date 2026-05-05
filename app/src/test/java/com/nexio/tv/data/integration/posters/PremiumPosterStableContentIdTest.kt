package com.nexio.tv.data.integration.posters

import com.nexio.tv.core.metadata.router.MetadataDecisionReason
import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.core.metadata.router.MetadataMediaKind
import com.nexio.tv.core.metadata.router.MetadataPrimaryProvider
import com.nexio.tv.core.metadata.router.MetadataRoute
import com.nexio.tv.core.metadata.router.MetadataRouteTrace
import com.nexio.tv.core.metadata.router.MetadataSourceContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PremiumPosterStableContentIdTest {

    @Test
    fun `movie poster stable id prefers imdb target id over route parent id`() {
        val route = route(
            provider = MetadataPrimaryProvider.TMDB,
            mediaKind = MetadataMediaKind.MOVIE,
            parentId = "catalog-row-item-42",
            targetIds = mapOf(
                MetadataPrimaryProvider.TMDB to "tmdb:550",
                MetadataPrimaryProvider.IMDB to "tt0137523"
            )
        )

        assertEquals("imdb:tt0137523", route.premiumPosterStableContentId(IntegrationProvider.RPDB))
    }

    @Test
    fun `poster stable id accepts prefixed imdb target id as bare imdb id`() {
        val route = route(
            provider = MetadataPrimaryProvider.TMDB,
            mediaKind = MetadataMediaKind.MOVIE,
            parentId = "catalog-row-item-42",
            targetIds = mapOf(MetadataPrimaryProvider.IMDB to "imdb:tt0137523")
        )

        assertEquals("imdb:tt0137523", route.premiumPosterStableContentId(IntegrationProvider.RPDB))
    }

    @Test
    fun `poster stable id uses prefixed tmdb target id instead of route parent id`() {
        val route = route(
            provider = MetadataPrimaryProvider.TMDB,
            mediaKind = MetadataMediaKind.MOVIE,
            parentId = "catalog-row-item-99",
            targetIds = mapOf(MetadataPrimaryProvider.TMDB to "550")
        )

        assertEquals("tmdb:movie-550", route.premiumPosterStableContentId(IntegrationProvider.RPDB))
    }

    @Test
    fun `poster stable id rejects malformed imdb target id`() {
        val route = route(
            provider = MetadataPrimaryProvider.TMDB,
            mediaKind = MetadataMediaKind.MOVIE,
            parentId = "catalog-row-item-42",
            targetIds = mapOf(MetadataPrimaryProvider.IMDB to "imdb:not-a-title-id")
        )

        assertNull(route.premiumPosterStableContentId(IntegrationProvider.RPDB))
    }

    @Test
    fun `poster stable id rejects malformed tmdb target id`() {
        val route = route(
            provider = MetadataPrimaryProvider.TMDB,
            mediaKind = MetadataMediaKind.MOVIE,
            parentId = "catalog-row-item-99",
            targetIds = mapOf(MetadataPrimaryProvider.TMDB to "spotify:abc123")
        )

        assertNull(route.premiumPosterStableContentId(IntegrationProvider.RPDB))
    }

    @Test
    fun `poster stable id rejects malformed tvdb target id`() {
        val route = route(
            provider = MetadataPrimaryProvider.TVDB,
            mediaKind = MetadataMediaKind.SERIES,
            parentId = "catalog-row-item-100",
            targetIds = mapOf(MetadataPrimaryProvider.TVDB to "tvdb:series-121361")
        )

        assertNull(route.premiumPosterStableContentId(IntegrationProvider.RPDB))
    }

    @Test
    fun `poster stable id falls back to valid tmdb target id when imdb is malformed`() {
        val route = route(
            provider = MetadataPrimaryProvider.TMDB,
            mediaKind = MetadataMediaKind.MOVIE,
            parentId = "catalog-row-item-42",
            targetIds = mapOf(
                MetadataPrimaryProvider.IMDB to "tmdb:550",
                MetadataPrimaryProvider.TMDB to "550"
            )
        )

        assertEquals("tmdb:movie-550", route.premiumPosterStableContentId(IntegrationProvider.RPDB))
    }

    @Test
    fun `poster stable id falls back to valid tvdb target id when earlier supported ids are malformed`() {
        val route = route(
            provider = MetadataPrimaryProvider.TVDB,
            mediaKind = MetadataMediaKind.SERIES,
            parentId = "catalog-row-item-100",
            targetIds = mapOf(
                MetadataPrimaryProvider.IMDB to "imdb:bad",
                MetadataPrimaryProvider.TMDB to "tmdb:movie-550",
                MetadataPrimaryProvider.TVDB to "tvdb:121361"
            )
        )

        assertEquals("tvdb:series-121361", route.premiumPosterStableContentId(IntegrationProvider.RPDB))
    }

    @Test
    fun `kitsu only anime route resolves for top posters but not rpdb`() {
        val route = route(
            provider = MetadataPrimaryProvider.KITSU,
            mediaKind = MetadataMediaKind.ANIME,
            parentId = "kitsu:7442",
            targetIds = mapOf(MetadataPrimaryProvider.KITSU to "kitsu:7442")
        )

        assertEquals("kitsu:7442", route.premiumPosterStableContentId(IntegrationProvider.TOP_POSTERS))
        assertNull(route.premiumPosterStableContentId(IntegrationProvider.RPDB))
    }

    @Test
    fun `anime route can use premium poster only when supported stable id exists`() {
        val route = route(
            provider = MetadataPrimaryProvider.KITSU,
            mediaKind = MetadataMediaKind.ANIME,
            parentId = "kitsu:7442",
            targetIds = mapOf(
                MetadataPrimaryProvider.KITSU to "kitsu:7442",
                MetadataPrimaryProvider.IMDB to "tt0388629"
            )
        )

        assertEquals("kitsu:7442", route.premiumPosterStableContentId(IntegrationProvider.TOP_POSTERS))
        assertEquals("imdb:tt0388629", route.premiumPosterStableContentId(IntegrationProvider.RPDB))
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
}
