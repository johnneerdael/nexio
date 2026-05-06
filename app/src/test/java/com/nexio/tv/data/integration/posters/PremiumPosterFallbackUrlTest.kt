package com.nexio.tv.data.integration.posters

import com.nexio.tv.core.artwork.ArtworkBundle
import com.nexio.tv.core.artwork.ArtworkDisplayRef
import com.nexio.tv.core.artwork.ArtworkTrace
import com.nexio.tv.core.artwork.ArtworkType
import com.nexio.tv.core.metadata.router.MetadataDecisionReason
import com.nexio.tv.core.metadata.router.MetadataMediaKind
import com.nexio.tv.core.metadata.router.MetadataPrimaryProvider
import com.nexio.tv.core.metadata.router.MetadataRoute
import com.nexio.tv.core.metadata.router.MetadataRouteTrace
import com.nexio.tv.core.metadata.router.MetadataSourceContext
import com.nexio.tv.domain.model.HomeDisplayMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PremiumPosterFallbackUrlTest {

    @Test
    fun `route returns safe remote source poster as premium fallback`() {
        val fallback = "https://image.tmdb.org/t/p/w500/fallback.jpg"
        val route = route(HomeDisplayMetadata(poster = fallback))

        assertEquals(fallback, route.nonPremiumPosterFallbackUrl())
    }

    @Test
    fun `route uses raw poster when display poster is an internal artwork ref`() {
        val fallback = "https://image.tmdb.org/t/p/w500/raw-fallback.jpg"
        val route = route(
            HomeDisplayMetadata(
                poster = fallback,
                artwork = ArtworkBundle(
                    poster = ArtworkDisplayRef.LegacyString(
                        value = "nexio-artwork://decision/premium-decision",
                        imageType = ArtworkType.POSTER,
                        trace = ArtworkTrace.empty()
                    )
                )
            )
        )

        assertEquals(fallback, route.nonPremiumPosterFallbackUrl())
    }

    @Test
    fun `route rejects premium provider urls as fallback candidates`() {
        assertNull(route(HomeDisplayMetadata(
            poster = "https://api.ratingposterdb.com/rpdb-key/imdb/poster-default/tt0137523.jpg"
        )).nonPremiumPosterFallbackUrl())

        assertNull(route(HomeDisplayMetadata(
            poster = "https://api.top-posters.com/top-key/tmdb/poster/movie-550.jpg"
        )).nonPremiumPosterFallbackUrl())
    }

    @Test
    fun `route rejects blank internal and non remote fallback candidates`() {
        assertNull(route(HomeDisplayMetadata(poster = " ")).nonPremiumPosterFallbackUrl())
        assertNull(route(HomeDisplayMetadata(poster = "nexio-artwork://decision/already-internal")).nonPremiumPosterFallbackUrl())
        assertNull(route(HomeDisplayMetadata(poster = "file:///sdcard/poster.jpg")).nonPremiumPosterFallbackUrl())
    }

    private fun route(metadata: HomeDisplayMetadata?): MetadataRoute =
        MetadataRoute(
            provider = MetadataPrimaryProvider.TMDB,
            parentId = "tmdb:550",
            mediaKind = MetadataMediaKind.MOVIE,
            reason = MetadataDecisionReason.PROVIDER_NATIVE_DIRECT,
            sourceContext = MetadataSourceContext(addonMetadata = metadata),
            targetIds = mapOf(MetadataPrimaryProvider.TMDB to "tmdb:550"),
            trace = listOf(
                MetadataRouteTrace(
                    reason = MetadataDecisionReason.PROVIDER_NATIVE_DIRECT,
                    detail = "test route"
                )
            )
        )
}
