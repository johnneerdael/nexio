package com.nexio.tv.ui.components

import com.nexio.tv.core.artwork.ArtworkAssetKey
import com.nexio.tv.core.artwork.ArtworkBundle
import com.nexio.tv.core.artwork.ArtworkDecisionKey
import com.nexio.tv.core.artwork.ArtworkDisplayRef
import com.nexio.tv.core.artwork.ArtworkProviderId
import com.nexio.tv.core.artwork.ArtworkSourceRole
import com.nexio.tv.core.artwork.ArtworkTrace
import com.nexio.tv.core.artwork.ArtworkType
import com.nexio.tv.core.image.ArtworkImageCacheKeys
import com.nexio.tv.core.image.LegacyRemoteArtworkModel
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.PosterShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentCardArtworkSelectionTest {
    @Test
    fun `expanded poster fallback uses poster cache key`() {
        val item = preview(
            ArtworkBundle(
                poster = artworkRef("posterAsset", ArtworkType.POSTER),
                backdrop = null
            )
        )

        val selection = resolveContentCardArtworkSelection(
            item = item,
            expandedBackdropRequested = true
        )

        assertEquals("nexio-artwork://asset/posterAsset", selection.model)
        assertEquals(
            ArtworkImageCacheKeys.poster(item.id, item.posterProviderTag, "nexio-artwork://asset/posterAsset"),
            selection.diskCacheKey
        )
    }

    @Test
    fun `expanded backdrop uses backdrop cache key when actual selection is backdrop`() {
        val item = preview(
            ArtworkBundle(
                poster = artworkRef("posterAsset", ArtworkType.POSTER),
                backdrop = artworkRef("backdropAsset", ArtworkType.BACKDROP)
            )
        )

        val selection = resolveContentCardArtworkSelection(
            item = item,
            expandedBackdropRequested = true
        )

        assertEquals("nexio-artwork://asset/backdropAsset", selection.model)
        assertEquals(ArtworkImageCacheKeys.backdrop(item.id), selection.diskCacheKey)
    }

    @Test
    fun `raw only poster fallback uses safe legacy model`() {
        val item = preview(
            artwork = null,
            poster = "https://image.tmdb.org/t/p/w500/raw-poster.jpg?token=secret",
            background = null
        )

        val selection = resolveContentCardArtworkSelection(
            item = item,
            expandedBackdropRequested = false
        )

        val model = selection.model
        assertTrue(model is LegacyRemoteArtworkModel)
        assertFalse(model is String)
        assertFalse(model.toString().contains("https://"))
        assertFalse((model as LegacyRemoteArtworkModel).key.contains("secret"))
        assertEquals(
            ArtworkImageCacheKeys.poster(item.id, item.posterProviderTag, model.toString()),
            selection.diskCacheKey
        )
    }

    @Test
    fun `raw only expanded background fallback uses safe legacy model`() {
        val item = preview(
            artwork = null,
            poster = null,
            background = "https://image.tmdb.org/t/p/w780/raw-backdrop.jpg?token=secret"
        )

        val selection = resolveContentCardArtworkSelection(
            item = item,
            expandedBackdropRequested = true
        )

        val model = selection.model
        assertTrue(model is LegacyRemoteArtworkModel)
        assertFalse(model is String)
        assertFalse(model.toString().contains("https://"))
        assertFalse((model as LegacyRemoteArtworkModel).key.contains("secret"))
        assertEquals(ArtworkImageCacheKeys.backdrop(item.id), selection.diskCacheKey)
    }

    private fun preview(
        artwork: ArtworkBundle?,
        poster: String? = null,
        background: String? = null
    ): MetaPreview =
        MetaPreview(
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
            posterProviderTag = "native",
            artwork = artwork
        )

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
