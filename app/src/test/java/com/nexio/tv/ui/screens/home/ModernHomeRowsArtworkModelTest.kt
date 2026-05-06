package com.nexio.tv.ui.screens.home

import com.nexio.tv.core.artwork.ArtworkAssetKey
import com.nexio.tv.core.artwork.ArtworkBundle
import com.nexio.tv.core.artwork.ArtworkDecisionKey
import com.nexio.tv.core.artwork.ArtworkDisplayRef
import com.nexio.tv.core.artwork.ArtworkProviderId
import com.nexio.tv.core.artwork.ArtworkSourceRole
import com.nexio.tv.core.artwork.ArtworkTrace
import com.nexio.tv.core.artwork.ArtworkType
import com.nexio.tv.core.image.LegacyRemoteArtworkModel
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.PosterShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModernHomeRowsArtworkModelTest {
    @Test
    fun `landscape cards prefer typed backdrop over poster`() {
        val item = carouselItem(
            ArtworkBundle(
                poster = artworkRef("posterAsset", ArtworkType.POSTER),
                backdrop = artworkRef("backdropAsset", ArtworkType.BACKDROP)
            )
        )

        val model = resolveModernCarouselCardArtworkModel(
            item = item,
            useLandscapePosters = true,
            focusedPosterBackdropExpandEnabled = false,
            isBackdropExpanded = false,
            fallbackModel = item.imageUrl
        )

        assertEquals("nexio-artwork://asset/backdropAsset", model)
    }

    @Test
    fun `expanded cards preserve typed backdrop preference`() {
        val item = carouselItem(
            ArtworkBundle(
                poster = artworkRef("posterAsset", ArtworkType.POSTER),
                backdrop = artworkRef("backdropAsset", ArtworkType.BACKDROP)
            )
        )

        val model = resolveModernCarouselCardArtworkModel(
            item = item,
            useLandscapePosters = false,
            focusedPosterBackdropExpandEnabled = true,
            isBackdropExpanded = true,
            fallbackModel = item.imageUrl
        )

        assertEquals("nexio-artwork://asset/backdropAsset", model)
    }

    @Test
    fun `portrait cards prefer typed poster`() {
        val item = carouselItem(
            ArtworkBundle(
                poster = artworkRef("posterAsset", ArtworkType.POSTER),
                backdrop = artworkRef("backdropAsset", ArtworkType.BACKDROP)
            )
        )

        val model = resolveModernCarouselCardArtworkModel(
            item = item,
            useLandscapePosters = false,
            focusedPosterBackdropExpandEnabled = false,
            isBackdropExpanded = false,
            fallbackModel = item.imageUrl
        )

        assertEquals("nexio-artwork://asset/posterAsset", model)
    }

    @Test
    fun `raw only landscape fallback uses safe legacy model`() {
        val item = carouselItem(
            artwork = null,
            poster = "https://image.tmdb.org/t/p/w500/raw-poster.jpg?token=secret",
            background = "https://image.tmdb.org/t/p/w780/raw-backdrop.jpg?token=secret"
        )

        val model = resolveModernCarouselCardArtworkModel(
            item = item,
            useLandscapePosters = true,
            focusedPosterBackdropExpandEnabled = false,
            isBackdropExpanded = false,
            fallbackModel = item.imageUrl
        )

        assertTrue(model is LegacyRemoteArtworkModel)
        assertFalse(model is String)
        assertFalse(model.toString().contains("https://"))
        assertFalse((model as LegacyRemoteArtworkModel).key.contains("secret"))
    }

    @Test
    fun `raw only portrait fallback uses safe legacy model`() {
        val item = carouselItem(
            artwork = null,
            poster = "https://image.tmdb.org/t/p/w500/raw-poster.jpg?token=secret",
            background = "https://image.tmdb.org/t/p/w780/raw-backdrop.jpg?token=secret"
        )

        val model = resolveModernCarouselCardArtworkModel(
            item = item,
            useLandscapePosters = false,
            focusedPosterBackdropExpandEnabled = false,
            isBackdropExpanded = false,
            fallbackModel = item.heroPreview.poster
        )

        assertTrue(model is LegacyRemoteArtworkModel)
        assertFalse(model is String)
        assertFalse(model.toString().contains("https://"))
        assertFalse((model as LegacyRemoteArtworkModel).key.contains("secret"))
    }

    @Test
    fun `portrait fallback model uses raw poster when typed poster is primary`() {
        val typedArtwork = ArtworkBundle(
            poster = artworkRef("posterDecision", ArtworkType.POSTER)
        )
        val baseItem = carouselItem(
            artwork = typedArtwork,
            poster = "https://image.tmdb.org/t/p/w500/raw-poster.jpg?token=secret"
        )
        val item = baseItem.copy(
            imageUrl = "nexio-artwork://decision/posterDecision",
            heroPreview = baseItem.heroPreview.copy(
                poster = "nexio-artwork://decision/posterDecision",
                imageUrl = "nexio-artwork://decision/posterDecision"
            )
        )

        val primary = resolveModernCarouselCardArtworkModel(
            item = item,
            useLandscapePosters = false,
            focusedPosterBackdropExpandEnabled = false,
            isBackdropExpanded = false,
            fallbackModel = item.imageUrl
        )
        val fallback = resolveModernCarouselCardFallbackArtworkModel(
            item = item,
            useLandscapePosters = false,
            focusedPosterBackdropExpandEnabled = false,
            isBackdropExpanded = false
        )

        assertEquals("nexio-artwork://asset/posterDecision", primary)
        assertTrue(fallback is LegacyRemoteArtworkModel)
        assertFalse(fallback is String)
        assertFalse(fallback.toString().contains("https://"))
        assertFalse((fallback as LegacyRemoteArtworkModel).key.contains("secret"))
    }

    private fun carouselItem(
        artwork: ArtworkBundle?,
        poster: String = "https://image.tmdb.org/t/p/w500/raw-poster.jpg",
        background: String = "https://image.tmdb.org/t/p/w780/raw-backdrop.jpg"
    ): ModernCarouselItem {
        val preview = MetaPreview(
            id = "tt123",
            type = ContentType.MOVIE,
            name = "Movie",
            poster = poster,
            posterShape = PosterShape.POSTER,
            background = background,
            logo = null,
            description = null,
            releaseInfo = null,
            imdbRating = null,
            genres = emptyList(),
            artwork = artwork
        )
        return ModernCarouselItem(
            key = "item",
            title = preview.name,
            subtitle = null,
            imageUrl = preview.displayBackground,
            heroPreview = HeroPreview(
                title = preview.name,
                logo = null,
                description = null,
                contentTypeText = null,
                yearText = null,
                imdbText = null,
                tomatoesText = null,
                genres = emptyList(),
                poster = preview.displayPoster,
                backdrop = preview.displayBackground,
                imageUrl = preview.displayBackground
            ),
            payload = ModernPayload.Catalog(
                focusKey = "focus",
                itemId = preview.id,
                itemType = preview.apiType,
                addonBaseUrl = "addon",
                trailerTitle = preview.name,
                trailerReleaseInfo = null,
                trailerApiType = preview.apiType
            ),
            metaPreview = preview
        )
    }

    private fun artworkRef(key: String, type: ArtworkType): ArtworkDisplayRef.RuntimeAsset =
        ArtworkDisplayRef.RuntimeAsset(
            decisionKey = ArtworkDecisionKey("decision-$key"),
            assetKey = ArtworkAssetKey(key),
            imageType = type,
            selectedProvider = ArtworkProviderId.RailPreview,
            sourceRole = ArtworkSourceRole.RAIL_PREVIEW,
            trace = ArtworkTrace.empty()
        )
}
