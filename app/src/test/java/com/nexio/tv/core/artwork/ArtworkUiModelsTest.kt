package com.nexio.tv.core.artwork

import com.nexio.tv.core.image.LegacyRemoteArtworkModel
import com.nexio.tv.core.integration.IntegrationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtworkUiModelsTest {
    @Test
    fun `runtime asset coil model uses decision URI so missing assets can materialize`() {
        val ref = ArtworkDisplayRef.RuntimeAsset(
            decisionKey = ArtworkDecisionKey("artwork-decision:thumbnail:tvdb:355567:S1E1"),
            assetKey = ArtworkAssetKey("artwork-asset:TVDB:thumbnail:urlHash:abc:variant:none:imageLang:en:policy:1"),
            imageType = ArtworkType.THUMBNAIL,
            selectedProvider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.TVDB),
            sourceRole = ArtworkSourceRole.PRIMARY,
            trace = ArtworkTrace.empty()
        )

        assertEquals(
            "nexio-artwork://decision/artwork-decision:thumbnail:tvdb:355567:S1E1",
            ref.toCoilModelOrNull()
        )
    }

    @Test
    fun `placeholder coil model keeps placeholder URI`() {
        val ref = ArtworkDisplayRef.Placeholder(
            placeholderType = PlaceholderType.THUMBNAIL,
            imageType = ArtworkType.THUMBNAIL,
            trace = ArtworkTrace.empty()
        )

        assertEquals("nexio-placeholder://thumbnail", ref.toCoilModelOrNull())
    }

    @Test
    fun `legacy string coil model keeps sanitized remote wrapper`() {
        val ref = ArtworkDisplayRef.LegacyString(
            value = "https://artworks.thetvdb.com/banners/episodes/355567/7140390.jpg",
            imageType = ArtworkType.THUMBNAIL,
            trace = ArtworkTrace.empty()
        )

        assertTrue(ref.toCoilModelOrNull() is LegacyRemoteArtworkModel)
    }
}
