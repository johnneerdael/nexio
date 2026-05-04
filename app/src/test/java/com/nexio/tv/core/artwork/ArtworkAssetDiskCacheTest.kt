package com.nexio.tv.core.artwork

import com.nexio.tv.core.integration.IntegrationProvider
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ArtworkAssetDiskCacheTest {
    private val temp = TemporaryFolder().also { it.create() }

    @Test
    fun `writes asset bytes to deterministic cache relative file`() {
        val cache = ArtworkAssetDiskCache(temp.root)
        val assetKey = ArtworkAssetKey("artwork-asset:RPDB:poster:imdb:tt0137523")
        val record = record(assetKey)

        val written = cache.write(record, "image-bytes".toByteArray())
        val existing = cache.getExistingFile(assetKey)

        assertTrue(written.exists())
        assertEquals(written, existing)
        assertEquals("artwork-assets/RPDB/poster/artwork-asset_RPDB_poster_imdb_tt0137523.bin", record.relativePath)
        assertArrayEquals("image-bytes".toByteArray(), written.readBytes())
    }

    @Test
    fun `missing asset returns null`() {
        val cache = ArtworkAssetDiskCache(temp.root)

        assertNull(cache.getExistingFile(ArtworkAssetKey("missing-asset")))
    }

    @Test
    fun `record builder uses cache relative deterministic location`() {
        val cache = ArtworkAssetDiskCache(temp.root)
        val assetKey = ArtworkAssetKey("artwork-asset:TMDB:poster:urlHash:abc123:variant:w500")

        val record = cache.recordFor(
            assetKey = assetKey,
            decision = decision(),
            provider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.TMDB),
            sourceHash = "abc123",
            mimeType = "image/jpeg",
            byteCount = 5L,
            fetchedAtMs = 123L
        )

        assertEquals("artwork-assets/TMDB/poster/artwork-asset_TMDB_poster_urlHash_abc123_variant_w500.bin", record.relativePath)
        assertEquals(assetKey, record.assetKey)
        assertEquals(ArtworkDecisionKey("decision-key"), record.decisionKey)
        assertEquals(ArtworkType.POSTER, record.imageType)
        assertNotNull(File(temp.root, record.relativePath).parentFile)
    }

    private fun record(assetKey: ArtworkAssetKey): ArtworkAssetRecord =
        ArtworkAssetRecord(
            assetKey = assetKey,
            decisionKey = ArtworkDecisionKey("decision-key"),
            provider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.RPDB),
            imageType = ArtworkType.POSTER,
            relativePath = "artwork-assets/RPDB/poster/artwork-asset_RPDB_poster_imdb_tt0137523.bin",
            mimeType = "image/jpeg",
            byteCount = 11L,
            sourceHash = "source-hash",
            policyVersion = 1,
            fetchedAtMs = 100L,
            expiresAtMs = 200L,
            staleUntilMs = 300L
        )

    private fun decision(): ArtworkDecision =
        ArtworkDecision(
            decisionKey = ArtworkDecisionKey("decision-key"),
            ownerKey = ArtworkOwnerKey.CanonicalContent("imdb:tt0137523"),
            canonicalContentId = "imdb:tt0137523",
            imageType = ArtworkType.POSTER,
            selectedCandidate = PersistedArtworkCandidate(
                provider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.TMDB),
                sourceRole = ArtworkSourceRole.PRIMARY,
                sourceHash = "abc123",
                redactedSourceForTrace = "https://image.tmdb.org/t/p/w500/<redacted>",
                providerTemplate = null,
                priority = 1
            ),
            rejectedCandidates = emptyList(),
            policyVersion = 1,
            settingsHash = null,
            credentialHash = null,
            createdAtMs = 100L,
            expiresAtMs = 200L,
            staleUntilMs = 300L
        )
}
