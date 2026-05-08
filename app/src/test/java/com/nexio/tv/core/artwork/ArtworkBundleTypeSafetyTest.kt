package com.nexio.tv.core.artwork

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ArtworkBundleTypeSafetyTest {
    @Test
    fun `enforce type boundaries drops refs in wrong slots`() {
        val posterRef = artworkRef("poster", ArtworkType.POSTER)
        val backdropRef = artworkRef("backdrop", ArtworkType.BACKDROP)
        val logoRef = artworkRef("logo", ArtworkType.LOGO)

        val sanitized = ArtworkBundle(
            poster = backdropRef,
            backdrop = logoRef,
            logo = posterRef,
            thumbnail = posterRef
        ).enforceArtworkTypeBoundaries()

        assertNull(sanitized.poster)
        assertNull(sanitized.backdrop)
        assertNull(sanitized.logo)
        assertNull(sanitized.thumbnail)
    }

    @Test
    fun `enforce type boundaries preserves refs in matching slots`() {
        val posterRef = artworkRef("poster", ArtworkType.POSTER)
        val backdropRef = artworkRef("backdrop", ArtworkType.BACKDROP)
        val logoRef = artworkRef("logo", ArtworkType.LOGO)
        val thumbnailRef = artworkRef("thumbnail", ArtworkType.THUMBNAIL)

        val sanitized = ArtworkBundle(
            poster = posterRef,
            backdrop = backdropRef,
            logo = logoRef,
            thumbnail = thumbnailRef
        ).enforceArtworkTypeBoundaries()

        assertEquals(posterRef, sanitized.poster)
        assertEquals(backdropRef, sanitized.backdrop)
        assertEquals(logoRef, sanitized.logo)
        assertEquals(thumbnailRef, sanitized.thumbnail)
    }

    @Test
    fun `emptyOrNull returns null for an empty bundle`() {
        assertNull(ArtworkBundle().emptyOrNull())
    }

    @Test
    fun `emptyOrNull returns bundle for non-empty bundle`() {
        val posterRef = artworkRef("poster", ArtworkType.POSTER)
        val bundle = ArtworkBundle(poster = posterRef)

        assertSame(bundle, bundle.emptyOrNull())
    }

    private fun artworkRef(key: String, imageType: ArtworkType): ArtworkDisplayRef.RuntimeAsset =
        ArtworkDisplayRef.RuntimeAsset(
            decisionKey = ArtworkDecisionKey("decision-$key"),
            assetKey = ArtworkAssetKey("asset-$key"),
            imageType = imageType,
            selectedProvider = ArtworkProviderId.RailPreview,
            sourceRole = ArtworkSourceRole.RAIL_PREVIEW,
            trace = ArtworkTrace.empty()
        )
}
