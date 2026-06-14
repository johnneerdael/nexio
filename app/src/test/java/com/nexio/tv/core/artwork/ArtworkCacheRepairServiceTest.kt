package com.nexio.tv.core.artwork

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ArtworkCacheRepairServiceTest {
    private val temp = TemporaryFolder().also { it.create() }

    @Test
    fun `decision ref repair returns asset uri when latest record file is readable`() {
        val diskCache = ArtworkAssetDiskCache(temp.root)
        val decisionKey = ArtworkDecisionKey("repair-decision")
        val record = record("repair-asset", decisionKey)
        val written = diskCache.write(record, byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x00, 0x01))
        val store = RecordingStore().apply { put(written.record) }
        val service = ArtworkCacheRepairService(diskCache, store)

        val repaired = service.repairDecisionRef(decisionKey)

        assertEquals("nexio-artwork://asset/${written.record.assetKey.value}", repaired)
    }

    @Test
    fun `decision ref repair returns null when record file is missing`() {
        val diskCache = ArtworkAssetDiskCache(temp.root)
        val decisionKey = ArtworkDecisionKey("repair-missing")
        val store = RecordingStore().apply { put(record("missing-asset", decisionKey)) }
        val service = ArtworkCacheRepairService(diskCache, store)

        assertNull(service.repairDecisionRef(decisionKey))
    }

    private fun record(assetKey: String, decisionKey: ArtworkDecisionKey): ArtworkAssetRecord =
        ArtworkAssetRecord(
            assetKey = ArtworkAssetKey(assetKey),
            decisionKey = decisionKey,
            provider = ArtworkProviderId.Placeholder,
            imageType = ArtworkType.POSTER,
            imageLanguage = "en",
            relativePath = "artwork-assets/test/$assetKey.bin",
            mimeType = "image/jpeg",
            byteCount = 4,
            sourceHash = "source-$assetKey",
            policyVersion = 1,
            fetchedAtMs = 100,
            expiresAtMs = 200,
            staleUntilMs = 300
        )

    private class RecordingStore : ArtworkAssetRecordStore {
        private val records = linkedMapOf<ArtworkAssetKey, ArtworkAssetRecord>()

        override fun put(record: ArtworkAssetRecord) {
            records[record.assetKey] = record
        }

        override fun get(assetKey: ArtworkAssetKey): ArtworkAssetRecord? =
            records[assetKey]

        override fun findLatestAssetForDecision(decisionKey: ArtworkDecisionKey): ArtworkAssetRecord? =
            records.values
                .filter { it.decisionKey == decisionKey }
                .maxByOrNull { it.fetchedAtMs }
    }
}
