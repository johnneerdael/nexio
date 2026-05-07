package com.nexio.tv.core.artwork

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeNoException
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.nio.file.Files

class ArtworkAssetRecordStoreTest {
    private val temp = TemporaryFolder().also { it.create() }

    @Test
    fun `findLatestAssetForDecision returns newest valid record after restart`() {
        val file = temp.newFile("artwork-asset-records.json")
        val decisionKey = ArtworkDecisionKey("decision-a")
        val older = record("asset-old", decisionKey, fetchedAtMs = 100)
        val newer = record("asset-new", decisionKey, fetchedAtMs = 200)

        DurableArtworkAssetRecordStore(file, Gson()).put(older)
        DurableArtworkAssetRecordStore(file, Gson()).put(newer)

        val restarted = DurableArtworkAssetRecordStore(file, Gson())

        assertEquals(newer, restarted.findLatestAssetForDecision(decisionKey))
        assertEquals(newer, restarted.get(ArtworkAssetKey("asset-new")))
        assertEquals(older, restarted.get(ArtworkAssetKey("asset-old")))
    }

    @Test
    fun `record without decision key is stored by asset but excluded from decision lookup`() {
        val file = temp.newFile("artwork-asset-records.json")
        val record = record("asset-only", decisionKey = null, fetchedAtMs = 300)

        val store = DurableArtworkAssetRecordStore(file, Gson())
        store.put(record)

        assertEquals(record, store.get(ArtworkAssetKey("asset-only")))
        assertNull(store.findLatestAssetForDecision(ArtworkDecisionKey("decision-a")))
    }

    @Test
    fun `malformed asset record is quarantined without dropping valid records`() {
        val file = temp.newFile("artwork-asset-records.json")
        file.writeText(
            """
            {
              "schemaVersion": 1,
              "records": [
                {
                  "assetKey": "asset-valid",
                  "decisionKey": "decision-valid",
                  "provider": "PLACEHOLDER",
                  "imageType": "POSTER",
                  "imageLanguage": "en",
                  "relativePath": "artwork-assets/test/asset-valid.bin",
                  "mimeType": "image/jpeg",
                  "byteCount": 4,
                  "sourceHash": "source-valid",
                  "policyVersion": 1,
                  "fetchedAtMs": 100,
                  "expiresAtMs": 200,
                  "staleUntilMs": 300
                },
                {
                  "assetKey": "asset-bad",
                  "decisionKey": "decision-bad",
                  "provider": "PLACEHOLDER",
                  "imageType": "NOT_A_REAL_IMAGE_TYPE",
                  "imageLanguage": "en",
                  "relativePath": "artwork-assets/test/asset-bad.bin",
                  "mimeType": "image/jpeg",
                  "byteCount": 4,
                  "sourceHash": "source-bad",
                  "policyVersion": 1,
                  "fetchedAtMs": 100,
                  "expiresAtMs": 200,
                  "staleUntilMs": 300
                }
              ]
            }
            """.trimIndent()
        )

        val store = DurableArtworkAssetRecordStore(file, Gson())

        assertEquals(ArtworkAssetKey("asset-valid"), store.get(ArtworkAssetKey("asset-valid"))?.assetKey)
        assertNull(store.get(ArtworkAssetKey("asset-bad")))
        assertEquals(1, store.quarantinedRecordCount())
    }

    @Test
    fun `write failure does not update in-memory state`() {
        val parentFile = temp.newFile("not-a-directory")
        val file = parentFile.resolve("artwork-asset-records.json")
        val assetKey = ArtworkAssetKey("asset-write-failure")
        val store = DurableArtworkAssetRecordStore(file, Gson())

        assertThrows(IOException::class.java) {
            store.put(record(assetKey.value, ArtworkDecisionKey("decision-write-failure"), fetchedAtMs = 400))
        }

        assertNull(store.get(assetKey))
    }

    @Test
    fun `malformed top-level json followed by put does not overwrite existing file`() {
        val file = temp.newFile("artwork-asset-records.json")
        val original = """{"schemaVersion":1,"records":["""
        file.writeText(original)
        val store = DurableArtworkAssetRecordStore(file, Gson())

        assertThrows(IllegalStateException::class.java) {
            store.put(record("asset-new", ArtworkDecisionKey("decision-new"), fetchedAtMs = 500))
        }

        assertEquals(original, file.readText())
    }

    @Test
    fun `unsupported future schema followed by put does not overwrite existing file`() {
        val file = temp.newFile("artwork-asset-records.json")
        val original = """{"schemaVersion":2,"records":[]}"""
        file.writeText(original)
        val store = DurableArtworkAssetRecordStore(file, Gson())

        assertThrows(IllegalStateException::class.java) {
            store.put(record("asset-new", ArtworkDecisionKey("decision-new"), fetchedAtMs = 600))
        }

        assertEquals(original, file.readText())
    }

    @Test
    fun `disk cache resolves readable file from persisted record`() {
        val diskCache = ArtworkAssetDiskCache(temp.root)
        val record = record("asset-file", ArtworkDecisionKey("decision-file"), fetchedAtMs = 400)
        val written = diskCache.write(record, byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x00, 0x01))

        assertEquals(written.file, diskCache.getExistingFile(written.record))
        assertEquals(true, diskCache.hasReadableImageBytes(written.record))
    }

    @Test
    fun `disk cache rejects missing record file`() {
        val diskCache = ArtworkAssetDiskCache(temp.root)
        val record = record("missing-file", ArtworkDecisionKey("decision-file"), fetchedAtMs = 500)

        assertNull(diskCache.getExistingFile(record))
        assertEquals(false, diskCache.hasReadableImageBytes(record))
    }

    @Test
    fun `disk cache rejects unreadable generated asset key file`() {
        val diskCache = ArtworkAssetDiskCache(temp.root)
        val assetKey = ArtworkAssetKey("asset:provider:poster")
        val record = record(assetKey.value, ArtworkDecisionKey("decision-unreadable"), fetchedAtMs = 550)
            .copy(assetKey = assetKey)
        val written = diskCache.write(record, byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x00, 0x01))

        written.file.setReadable(false, false)
        try {
            assumeFalse(written.file.canRead())
            assertNull(diskCache.getExistingFile(assetKey))
        } finally {
            written.file.setReadable(true, false)
        }
    }

    @Test
    fun `disk cache rejects persisted record path outside cache root`() {
        val diskCache = ArtworkAssetDiskCache(temp.root)
        val outsideFile = requireNotNull(temp.root.parentFile).resolve("outside-cache.bin")
        outsideFile.writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x00, 0x01))
        outsideFile.setReadable(true, false)
        val unsafe = record("unsafe-file", ArtworkDecisionKey("decision-file"), fetchedAtMs = 600)
            .copy(relativePath = "../outside-cache.bin")

        assertNull(diskCache.getExistingFile(unsafe))
        assertEquals(false, diskCache.hasReadableImageBytes(unsafe))
    }

    @Test
    fun `disk cache rejects generated asset key path segments that would escape cache root`() {
        val diskCache = ArtworkAssetDiskCache(temp.root)
        val assetKey = ArtworkAssetKey("asset:..:..")
        val record = record(assetKey.value, ArtworkDecisionKey("decision-generated-path"), fetchedAtMs = 700)
            .copy(assetKey = assetKey)
        val outsideFile = requireNotNull(temp.root.parentFile).resolve("${assetKey.value.safeTestPathSegment()}.bin")
        outsideFile.delete()

        assertThrows(IllegalArgumentException::class.java) {
            diskCache.write(record, byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
        }

        assertNull(diskCache.getExistingFile(assetKey))
        assertEquals(false, outsideFile.exists())
    }

    @Test
    fun `disk cache rejects persisted record symlink path outside cache root`() {
        val diskCache = ArtworkAssetDiskCache(temp.root)
        val outsideFile = requireNotNull(temp.root.parentFile).resolve("outside-symlink-target.jpg")
        outsideFile.writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x00, 0x01))
        val symlink = temp.root.resolve("artwork-assets/test/symlink.jpg")
        requireNotNull(symlink.parentFile).mkdirs()
        try {
            Files.createSymbolicLink(symlink.toPath(), outsideFile.toPath())
        } catch (exception: UnsupportedOperationException) {
            assumeNoException(exception)
        } catch (exception: IOException) {
            assumeNoException(exception)
        } catch (exception: SecurityException) {
            assumeNoException(exception)
        }
        val record = record("symlink-file", ArtworkDecisionKey("decision-symlink"), fetchedAtMs = 800)
            .copy(relativePath = "artwork-assets/test/symlink.jpg")

        assertNull(diskCache.getExistingFile(record))
        assertEquals(false, diskCache.hasReadableImageBytes(record))
    }

    private fun record(
        assetKey: String,
        decisionKey: ArtworkDecisionKey?,
        fetchedAtMs: Long
    ): ArtworkAssetRecord =
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
            fetchedAtMs = fetchedAtMs,
            expiresAtMs = fetchedAtMs + 1_000,
            staleUntilMs = fetchedAtMs + 2_000
        )

    private fun String.safeTestPathSegment(): String =
        replace(Regex("[^A-Za-z0-9._-]"), "_")
}
