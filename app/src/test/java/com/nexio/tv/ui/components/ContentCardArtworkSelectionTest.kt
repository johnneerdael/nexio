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
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.PosterShape
import org.junit.Assert.assertEquals
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

    private fun preview(artwork: ArtworkBundle): MetaPreview =
        MetaPreview(
            id = "tt123",
            type = ContentType.MOVIE,
            name = "Movie",
            poster = null,
            posterShape = PosterShape.POSTER,
            background = null,
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
