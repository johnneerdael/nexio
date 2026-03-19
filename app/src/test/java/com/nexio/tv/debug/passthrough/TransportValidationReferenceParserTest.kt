package com.nexio.tv.debug.passthrough

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransportValidationReferenceParserTest {

    private val manifestLoader = TransportValidationManifestLoader()

    @Test
    fun `parse AC3 reference bursts from bundled golden asset`() {
        val sample = sample("ac3")

        val bursts = TransportValidationReferenceParser.parseReferenceBursts(
            sample = sample,
            bytes = assetFile(sample.referenceAssetPath).readBytes()
        )

        assertTrue(bursts.size >= 2)
        assertEquals(0, bursts[0].burstIndex)
        assertEquals(6144, bursts[0].burstSizeBytes)
        assertEquals(0x01, bursts[0].normalizedPc)
        assertEquals(0x5000, bursts[0].pd)
        assertEquals(6144, bursts[1].burstSizeBytes)
    }

    @Test
    fun `parse EAC3 reference bursts from bundled golden asset`() {
        val sample = sample("eac3")

        val bursts = TransportValidationReferenceParser.parseReferenceBursts(
            sample = sample,
            bytes = assetFile(sample.referenceAssetPath).readBytes()
        )

        assertTrue(bursts.size >= 2)
        assertEquals(24576, bursts[0].burstSizeBytes)
        assertEquals(0x15, bursts[0].normalizedPc)
        assertEquals(0x1400, bursts[0].pd)
        assertEquals(24576, bursts[1].burstSizeBytes)
    }

    @Test
    fun `parse TrueHD MAT reference bursts from bundled golden asset`() {
        val sample = sample("truehd")

        val bursts = TransportValidationReferenceParser.parseReferenceBursts(
            sample = sample,
            bytes = assetFile(sample.referenceAssetPath).readBytes()
        )

        assertTrue(bursts.size >= 2)
        assertEquals(61440, bursts[0].burstSizeBytes)
        assertEquals(0x16, bursts[0].normalizedPc)
        assertEquals(61424, bursts[0].pd)
        assertEquals(61440, bursts[1].burstSizeBytes)
    }

    @Test
    fun `parse DTSHD reference bursts with wrapper metadata`() {
        val sample = sample("dtshd")

        val bursts = TransportValidationReferenceParser.parseReferenceBursts(
            sample = sample,
            bytes = assetFile(sample.referenceAssetPath).readBytes()
        )

        assertTrue(bursts.isNotEmpty())
        assertEquals(0x11, bursts[0].normalizedPc)
        assertTrue(bursts[0].dtsHdWrapperPresent == true)
        assertNotNull(bursts[0].dtsHdWrapperPayloadSize)
        assertNotNull(bursts[0].dtsHdSubtype)
        assertEquals(
            TransportValidationDtsPayloadClassification.HD_PAYLOAD,
            bursts[0].dtsHdPayloadClassification
        )
    }

    @Test
    fun `parse wrapped DTSHD burst as likely core only payload when wrapper payload is small`() {
        val sample = sample("dtshd")
        val bytes = dtsHdBurst(rawPc = 0x0111, wrapperPayloadSize = 0x0800)

        val burst = TransportValidationReferenceParser.parseBurst(
            sample = sample,
            bytes = bytes,
            burstIndex = 0,
            sourcePtsUs = 0L
        )

        assertEquals(
            TransportValidationDtsPayloadClassification.LIKELY_CORE_ONLY_PAYLOAD,
            burst.dtsHdPayloadClassification
        )
    }

    @Test
    fun `parse wrapped DTSHD burst as unknown payload when wrapper payload is mid band`() {
        val sample = sample("dtshd")
        val bytes = dtsHdBurst(rawPc = 0x0111, wrapperPayloadSize = 0x1800)

        val burst = TransportValidationReferenceParser.parseBurst(
            sample = sample,
            bytes = bytes,
            burstIndex = 0,
            sourcePtsUs = 0L
        )

        assertEquals(
            TransportValidationDtsPayloadClassification.UNKNOWN_PAYLOAD,
            burst.dtsHdPayloadClassification
        )
    }

    private fun sample(id: String): TransportValidationSample =
        manifestLoader.parseManifest(assetFile("transport_validation_manifest.json").readText())
            .samples
            .first { it.id == id }

    private fun assetFile(name: String): File {
        val direct = File("assets/$name")
        if (direct.exists()) {
            return direct
        }
        val parent = File("../assets/$name")
        if (parent.exists()) {
            return parent
        }
        throw IllegalStateException("Unable to locate $name from test runtime")
    }

    private fun dtsHdBurst(rawPc: Int, wrapperPayloadSize: Int): ByteArray {
        val bytes = ByteArray(16_384)
        bytes[0] = 0x72
        bytes[1] = 0xF8.toByte()
        bytes[2] = 0x1F
        bytes[3] = 0x4E
        bytes[4] = (rawPc and 0xFF).toByte()
        bytes[5] = ((rawPc ushr 8) and 0xFF).toByte()
        bytes[6] = 0x68
        bytes[7] = 0x08
        bytes[8] = 0x00
        bytes[9] = 0x01
        bytes[10] = 0x00
        bytes[11] = 0x00
        bytes[12] = 0x00
        bytes[13] = 0x00
        bytes[14] = 0x00
        bytes[15] = 0x00
        bytes[16] = 0xFE.toByte()
        bytes[17] = 0xFE.toByte()
        bytes[18] = ((wrapperPayloadSize ushr 8) and 0xFF).toByte()
        bytes[19] = (wrapperPayloadSize and 0xFF).toByte()
        bytes[20] = 0xFE.toByte()
        bytes[21] = 0x7F.toByte()
        bytes[22] = 0x01
        bytes[23] = 0x80.toByte()
        return bytes
    }
}
