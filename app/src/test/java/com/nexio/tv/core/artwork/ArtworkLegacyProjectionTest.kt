package com.nexio.tv.core.artwork

import com.nexio.tv.core.integration.IntegrationProvider
import org.junit.Assert.assertEquals
import org.junit.Test

class ArtworkLegacyProjectionTest {
    @Test
    fun `runtime asset projects to asset URI when asset key is known`() {
        val ref = ArtworkDisplayRef.RuntimeAsset(
            decisionKey = ArtworkDecisionKey("artwork-decision:poster:imdb:tt0137523"),
            assetKey = ArtworkAssetKey("artwork-asset:rpdb:poster:imdb:tt0137523"),
            imageType = ArtworkType.POSTER,
            selectedProvider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.RPDB),
            sourceRole = ArtworkSourceRole.PREMIUM,
            trace = ArtworkTrace.empty()
        )

        assertEquals(
            "nexio-artwork://asset/artwork-asset:rpdb:poster:imdb:tt0137523",
            ref.toLegacyArtworkString()
        )
    }

    @Test
    fun `runtime asset projects to decision URI when asset key is missing`() {
        val ref = ArtworkDisplayRef.RuntimeAsset(
            decisionKey = ArtworkDecisionKey("artwork-decision:poster:preview:row1"),
            assetKey = null,
            imageType = ArtworkType.POSTER,
            selectedProvider = ArtworkProviderId.RailPreview,
            sourceRole = ArtworkSourceRole.CURRENT_PREVIEW,
            trace = ArtworkTrace.empty()
        )

        assertEquals(
            "nexio-artwork://decision/artwork-decision:poster:preview:row1",
            ref.toLegacyArtworkString()
        )
    }

    @Test
    fun `placeholder projects to placeholder URI`() {
        val ref = ArtworkDisplayRef.Placeholder(
            placeholderType = PlaceholderType.POSTER,
            imageType = ArtworkType.POSTER,
            trace = ArtworkTrace.empty()
        )

        assertEquals("nexio-placeholder://poster", ref.toLegacyArtworkString())
    }
}
