package com.nexio.tv.core.artwork

import com.nexio.tv.core.integration.IntegrationProvider
import org.junit.Assert.assertEquals
import org.junit.Test

class ArtworkLegacyProjectionTest {
    @Test
    fun `null ref projects to null`() {
        val ref: ArtworkDisplayRef? = null

        assertEquals(null, ref.toLegacyArtworkString())
    }

    @Test
    fun `runtime asset projects to decision URI even when asset key is known`() {
        // Asset URIs are read-only at the Coil fetcher and would dangle if the
        // originating decision was never materialized. The legacy projection now
        // always emits a decision URI so the self-healing fetcher path runs.
        val ref = ArtworkDisplayRef.RuntimeAsset(
            decisionKey = ArtworkDecisionKey("artwork-decision:poster:imdb:tt0137523"),
            assetKey = ArtworkAssetKey("artwork-asset:rpdb:poster:imdb:tt0137523"),
            imageType = ArtworkType.POSTER,
            selectedProvider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.RPDB),
            sourceRole = ArtworkSourceRole.PREMIUM,
            trace = ArtworkTrace.empty()
        )

        assertEquals(
            "nexio-artwork://decision/artwork-decision:poster:imdb:tt0137523",
            ref.toLegacyArtworkString()
        )
    }

    @Test
    fun `runtime asset projection always emits decision URI regardless of asset key presence`() {
        val ref = ArtworkDisplayRef.RuntimeAsset(
            decisionKey = ArtworkDecisionKey("artwork-decision:poster:canonical:imdb:tt123"),
            assetKey = ArtworkAssetKey("artwork-asset:RPDB:poster:imdb:tt123:settings:s:credential:c:imageLang:en:policy:1"),
            imageType = ArtworkType.POSTER,
            selectedProvider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.RPDB),
            sourceRole = ArtworkSourceRole.PREMIUM,
            trace = ArtworkTrace.empty()
        )

        assertEquals(
            "nexio-artwork://decision/artwork-decision:poster:canonical:imdb:tt123",
            ref.toLegacyArtworkString()
        )
    }

    @Test
    fun `runtime asset display hints default to no rating overlay`() {
        val ref = ArtworkDisplayRef.RuntimeAsset(
            decisionKey = ArtworkDecisionKey("artwork-decision:thumbnail:imdb:tt0137523:S1E1"),
            assetKey = ArtworkAssetKey("artwork-asset:tmdb:thumbnail:imdb:tt0137523:S1E1"),
            imageType = ArtworkType.THUMBNAIL,
            selectedProvider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.TMDB),
            sourceRole = ArtworkSourceRole.PRIMARY,
            trace = ArtworkTrace.empty()
        )

        assertEquals(false, ref.displayHints.embedsRatingOverlay)
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
    fun `runtime asset projection uses decision key when asset key absent`() {
        val ref = ArtworkDisplayRef.RuntimeAsset(
            decisionKey = ArtworkDecisionKey("artwork-decision:poster:canonical:imdb:tt123"),
            assetKey = null,
            imageType = ArtworkType.POSTER,
            selectedProvider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.RPDB),
            sourceRole = ArtworkSourceRole.PREMIUM,
            trace = ArtworkTrace.empty()
        )

        assertEquals(
            "nexio-artwork://decision/artwork-decision:poster:canonical:imdb:tt123",
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

    @Test
    fun `placeholder display hints default to no rating overlay`() {
        val ref = ArtworkDisplayRef.Placeholder(
            placeholderType = PlaceholderType.THUMBNAIL,
            imageType = ArtworkType.THUMBNAIL,
            trace = ArtworkTrace.empty()
        )

        assertEquals(false, ref.displayHints.embedsRatingOverlay)
    }

    @Test
    fun `display hints do not affect legacy projection`() {
        val ref = ArtworkDisplayRef.RuntimeAsset(
            decisionKey = ArtworkDecisionKey("artwork-decision:thumbnail:imdb:tt0137523:S1E1"),
            assetKey = ArtworkAssetKey("artwork-asset:TOP_POSTERS:thumbnail:imdb:tt0137523:S1E1"),
            imageType = ArtworkType.THUMBNAIL,
            selectedProvider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.TOP_POSTERS),
            sourceRole = ArtworkSourceRole.PREMIUM,
            trace = ArtworkTrace.empty(),
            displayHints = ArtworkDisplayHints(embedsRatingOverlay = true)
        )

        assertEquals(
            "nexio-artwork://decision/artwork-decision:thumbnail:imdb:tt0137523:S1E1",
            ref.toLegacyArtworkString()
        )
    }
}
