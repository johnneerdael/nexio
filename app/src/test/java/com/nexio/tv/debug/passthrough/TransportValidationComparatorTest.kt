package com.nexio.tv.debug.passthrough

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransportValidationComparatorTest {

    private val json = Json { ignoreUnknownKeys = false }

    @Test
    fun `manifest parses codec family and route tuple`() {
        val manifest = json.decodeFromString<TransportValidationManifest>(
            """
            {
              "version": "2026.03.19",
              "samples": [
                {
                  "id": "truehd",
                  "displayName": "TrueHD Validation",
                  "codecFamily": "TRUEHD",
                  "sourceAssetPath": "validation/truehd/truehd.mkv",
                  "referenceAssetPath": "validation/truehd/reference.truehd",
                  "expectedPc": 22,
                  "pdRule": "TRUEHD_MAT",
                  "expectedBurstModel": "MAT",
                  "expectedRouteTuple": {
                    "encoding": "IEC61937",
                    "sampleRate": 192000,
                    "channelMask": "7.1"
                  },
                  "assetChecksums": {
                    "sourceAssetPath": "sha256-source",
                    "referenceAssetPath": "sha256-reference"
                  },
                  "notes": [
                    "TrueHD MAT expected"
                  ]
                }
              ]
            }
            """.trimIndent()
        )

        val sample = manifest.samples.single()
        assertEquals("2026.03.19", manifest.version)
        assertEquals(TransportValidationCodecFamily.TRUEHD, sample.codecFamily)
        assertEquals(0x16, sample.expectedPc)
        assertEquals(TransportValidationPdRule.TRUEHD_MAT, sample.pdRule)
        assertEquals(TransportValidationBurstModel.MAT, sample.expectedBurstModel)
        assertEquals(192000, sample.expectedRouteTuple.sampleRate)
        assertEquals("7.1", sample.expectedRouteTuple.channelMask)
        assertEquals("sha256-source", sample.assetChecksums["sourceAssetPath"])
    }

    @Test
    fun `capture modes expose supported options`() {
        assertEquals(
            listOf(
                TransportValidationCaptureMode.PREAMBLE_ONLY,
                TransportValidationCaptureMode.FIRST_N_BURSTS,
                TransportValidationCaptureMode.UNTIL_FAILURE
            ),
            TransportValidationCaptureMode.entries
        )
    }

    @Test
    fun `comparator fails when burst indices are not aligned`() {
        val reference = burstRecord(index = 4, codecFamily = TransportValidationCodecFamily.TRUEHD)
        val live = burstRecord(index = 5, codecFamily = TransportValidationCodecFamily.TRUEHD)

        val result = TransportValidationComparator.compareReferenceBurst(
            sample = sample(codecFamily = TransportValidationCodecFamily.TRUEHD),
            reference = reference,
            live = live
        )

        assertFalse(result.passed)
        assertEquals(TransportValidationFailureCode.BURST_ALIGNMENT_FAILED, result.failureCode)
    }

    @Test
    fun `comparator maps preamble mismatch`() {
        val reference = burstRecord(index = 1, codecFamily = TransportValidationCodecFamily.AC3, rawPc = 0x01)
        val live = burstRecord(index = 1, codecFamily = TransportValidationCodecFamily.AC3, rawPc = 0x02)

        val result = TransportValidationComparator.compareReferenceBurst(
            sample = sample(codecFamily = TransportValidationCodecFamily.AC3),
            reference = reference,
            live = live
        )

        assertFalse(result.passed)
        assertEquals(TransportValidationFailureCode.PREAMBLE_MISMATCH, result.failureCode)
    }

    @Test
    fun `comparator maps truehd MAT validation failure`() {
        val reference = burstRecord(index = 2, codecFamily = TransportValidationCodecFamily.TRUEHD, rawPc = 0x16)
        val live = burstRecord(index = 2, codecFamily = TransportValidationCodecFamily.TRUEHD, rawPc = 0x15)

        val result = TransportValidationComparator.compareReferenceBurst(
            sample = sample(codecFamily = TransportValidationCodecFamily.TRUEHD),
            reference = reference,
            live = live
        )

        assertFalse(result.passed)
        assertEquals(TransportValidationFailureCode.TRUEHD_MAT_INVALID, result.failureCode)
    }

    @Test
    fun `comparator maps eac3 aggregation mismatch`() {
        val reference = burstRecord(
            index = 3,
            codecFamily = TransportValidationCodecFamily.E_AC3_JOC,
            pd = 4096,
            payloadBytes = 4096
        )
        val live = burstRecord(
            index = 3,
            codecFamily = TransportValidationCodecFamily.E_AC3_JOC,
            pd = 4096,
            payloadBytes = 2048
        )

        val result = TransportValidationComparator.compareReferenceBurst(
            sample = sample(codecFamily = TransportValidationCodecFamily.E_AC3_JOC),
            reference = reference,
            live = live
        )

        assertFalse(result.passed)
        assertEquals(TransportValidationFailureCode.EAC3_AGGREGATION_MISMATCH, result.failureCode)
    }

    @Test
    fun `comparator maps dtshd core-only fallback`() {
        val live = burstRecord(
            index = 6,
            codecFamily = TransportValidationCodecFamily.DTS_HD,
            rawPc = 0x0B,
            codecFailureHint = TransportValidationFailureCode.DTSHD_CORE_ONLY_FALLBACK
        )

        val result = TransportValidationComparator.compareReferenceBurst(
            sample = sample(codecFamily = TransportValidationCodecFamily.DTS_HD),
            reference = burstRecord(index = 6, codecFamily = TransportValidationCodecFamily.DTS_HD),
            live = live
        )

        assertFalse(result.passed)
        assertEquals(TransportValidationFailureCode.DTSHD_CORE_ONLY_FALLBACK, result.failureCode)
    }

    @Test
    fun `comparator flags dtshd burst missing wrapper as core-only fallback`() {
        val reference = burstRecord(
            index = 8,
            codecFamily = TransportValidationCodecFamily.DTS_HD,
            rawPc = 0x0111,
            dtsHdWrapperPresent = true
        )
        val live = burstRecord(
            index = 8,
            codecFamily = TransportValidationCodecFamily.DTS_HD,
            rawPc = 0x0111,
            dtsHdWrapperPresent = false
        )

        val result = TransportValidationComparator.compareReferenceBurst(
            sample = sample(codecFamily = TransportValidationCodecFamily.DTS_HD),
            reference = reference,
            live = live
        )

        assertFalse(result.passed)
        assertEquals(TransportValidationFailureCode.DTSHD_CORE_ONLY_FALLBACK, result.failureCode)
    }

    @Test
    fun `comparator flags wrapped dtshd likely core-only payload explicitly`() {
        val reference = burstRecord(
            index = 12,
            codecFamily = TransportValidationCodecFamily.DTS_HD,
            rawPc = 0x0111,
            dtsHdWrapperPresent = true,
            dtsHdPayloadClassification =
                TransportValidationDtsPayloadClassification.HD_PAYLOAD,
        )
        val live = burstRecord(
            index = 12,
            codecFamily = TransportValidationCodecFamily.DTS_HD,
            rawPc = 0x0111,
            dtsHdWrapperPresent = true,
            dtsHdPayloadClassification =
                TransportValidationDtsPayloadClassification.LIKELY_CORE_ONLY_PAYLOAD,
        )

        val result = TransportValidationComparator.compareReferenceBurst(
            sample = sample(codecFamily = TransportValidationCodecFamily.DTS_HD),
            reference = reference,
            live = live,
        )

        assertFalse(result.passed)
        assertEquals(TransportValidationFailureCode.DTSHD_CORE_ONLY_FALLBACK, result.failureCode)
        assertEquals(
            TransportValidationDtsPayloadClassification.LIKELY_CORE_ONLY_PAYLOAD,
            result.dtsHdPayloadClassification,
        )
    }

    @Test
    fun `comparator preserves wrapped dtshd unknown payload classification on mismatch`() {
        val reference = burstRecord(
            index = 13,
            codecFamily = TransportValidationCodecFamily.DTS_HD,
            rawPc = 0x0111,
            fullBurstHash = "ref",
            dtsHdWrapperPresent = true,
            dtsHdPayloadClassification =
                TransportValidationDtsPayloadClassification.HD_PAYLOAD,
        )
        val live = burstRecord(
            index = 13,
            codecFamily = TransportValidationCodecFamily.DTS_HD,
            rawPc = 0x0111,
            fullBurstHash = "live",
            dtsHdWrapperPresent = true,
            dtsHdPayloadClassification =
                TransportValidationDtsPayloadClassification.UNKNOWN_PAYLOAD,
        )

        val result = TransportValidationComparator.compareReferenceBurst(
            sample = sample(codecFamily = TransportValidationCodecFamily.DTS_HD),
            reference = reference,
            live = live,
        )

        assertFalse(result.passed)
        assertEquals(TransportValidationFailureCode.FULL_BURST_MISMATCH, result.failureCode)
        assertEquals(
            TransportValidationDtsPayloadClassification.UNKNOWN_PAYLOAD,
            result.dtsHdPayloadClassification,
        )
    }

    @Test
    fun `comparator maps packer to audiotrack mutation`() {
        val packed = burstRecord(
            index = 7,
            codecFamily = TransportValidationCodecFamily.TRUEHD,
            fullBurstHash = "packed-hash"
        )
        val audioTrack = burstRecord(
            index = 7,
            codecFamily = TransportValidationCodecFamily.TRUEHD,
            fullBurstHash = "write-hash"
        )

        val result = TransportValidationComparator.comparePackedToAudioTrack(packed, audioTrack)

        assertFalse(result.passed)
        assertEquals(
            TransportValidationFailureCode.PACKER_TO_AUDIOTRACK_MUTATION,
            result.failureCode
        )
    }

    @Test
    fun `preamble only mode ignores full burst mismatch when headers align`() {
        val reference = burstRecord(index = 9, codecFamily = TransportValidationCodecFamily.TRUEHD, fullBurstHash = "ref")
        val live = burstRecord(index = 9, codecFamily = TransportValidationCodecFamily.TRUEHD, fullBurstHash = "live")

        val result = TransportValidationComparator.compareReferenceBurst(
            sample = sample(codecFamily = TransportValidationCodecFamily.TRUEHD),
            reference = reference,
            live = live,
            comparisonMode = TransportValidationComparisonMode.PREAMBLE_ONLY,
        )

        assertTrue(result.passed)
        assertNull(result.failureCode)
    }

    @Test
    fun `full burst compare maps reference payload mismatch`() {
        val reference = burstRecord(index = 10, codecFamily = TransportValidationCodecFamily.TRUEHD, fullBurstHash = "ref")
        val live = burstRecord(index = 10, codecFamily = TransportValidationCodecFamily.TRUEHD, fullBurstHash = "live")

        val result = TransportValidationComparator.compareReferenceBurst(
            sample = sample(codecFamily = TransportValidationCodecFamily.TRUEHD),
            reference = reference,
            live = live,
        )

        assertFalse(result.passed)
        assertEquals(TransportValidationFailureCode.FULL_BURST_MISMATCH, result.failureCode)
    }

    @Test
    fun `preamble only mode ignores packed to audiotrack hash mismatch when preamble aligns`() {
        val packed = burstRecord(index = 11, codecFamily = TransportValidationCodecFamily.TRUEHD, fullBurstHash = "packed")
        val audioTrack = burstRecord(index = 11, codecFamily = TransportValidationCodecFamily.TRUEHD, fullBurstHash = "audio")

        val result = TransportValidationComparator.comparePackedToAudioTrack(
            packed = packed,
            audioTrack = audioTrack,
            comparisonMode = TransportValidationComparisonMode.PREAMBLE_ONLY,
        )

        assertTrue(result.passed)
        assertNull(result.failureCode)
    }

    @Test
    fun `comparator passes for aligned matching burst`() {
        val reference = burstRecord(index = 8, codecFamily = TransportValidationCodecFamily.TRUEHD)
        val live = burstRecord(index = 8, codecFamily = TransportValidationCodecFamily.TRUEHD)

        val result = TransportValidationComparator.compareReferenceBurst(
            sample = sample(codecFamily = TransportValidationCodecFamily.TRUEHD),
            reference = reference,
            live = live
        )

        assertTrue(result.passed)
        assertNull(result.failureCode)
    }

    private fun sample(
        codecFamily: TransportValidationCodecFamily
    ): TransportValidationSample = TransportValidationSample(
        id = codecFamily.name.lowercase(),
        displayName = codecFamily.name,
        codecFamily = codecFamily,
        sourceAssetPath = "validation/${codecFamily.name.lowercase()}/source.bin",
        referenceAssetPath = "validation/${codecFamily.name.lowercase()}/reference.bin",
        expectedPc = when (codecFamily) {
            TransportValidationCodecFamily.TRUEHD -> 0x16
            TransportValidationCodecFamily.E_AC3,
            TransportValidationCodecFamily.E_AC3_JOC -> 0x15
            else -> 0x01
        },
        pdRule = when (codecFamily) {
            TransportValidationCodecFamily.TRUEHD -> TransportValidationPdRule.TRUEHD_MAT
            TransportValidationCodecFamily.E_AC3,
            TransportValidationCodecFamily.E_AC3_JOC -> TransportValidationPdRule.AGGREGATED_PAYLOAD_BYTES
            else -> TransportValidationPdRule.EXACT_REFERENCE_MATCH
        },
        expectedBurstModel = when (codecFamily) {
            TransportValidationCodecFamily.TRUEHD -> TransportValidationBurstModel.MAT
            else -> TransportValidationBurstModel.IEC_BURST
        },
        expectedRouteTuple = TransportValidationRouteTuple(
            encoding = "IEC61937",
            sampleRate = 192000,
            channelMask = "7.1"
        ),
        assetChecksums = mapOf(
            "sourceAssetPath" to "source-sha256",
            "referenceAssetPath" to "reference-sha256"
        )
    )

    private fun burstRecord(
        index: Int,
        codecFamily: TransportValidationCodecFamily,
        rawPc: Int = when (codecFamily) {
            TransportValidationCodecFamily.TRUEHD -> 0x16
            TransportValidationCodecFamily.DTS_HD,
            TransportValidationCodecFamily.DTS_X -> 0x0111
            else -> 0x01
        },
        pd: Int = 61424,
        payloadBytes: Int = pd,
        fullBurstHash: String = "hash-$index",
        dtsHdWrapperPresent: Boolean? = null,
        dtsHdPayloadClassification: TransportValidationDtsPayloadClassification? = null,
        codecFailureHint: TransportValidationFailureCode? = null
    ): TransportValidationBurstRecord = TransportValidationBurstRecord(
        codecFamily = codecFamily,
        sampleId = codecFamily.name.lowercase(),
        burstIndex = index,
        sourcePtsUs = 1_000_000L + index,
        rawBytes = ByteArray(64) { index.toByte() },
        burstSizeBytes = 61440,
        pa = 0xF872,
        pb = 0x4E1F,
        rawPc = rawPc,
        pd = pd,
        payloadBytes = payloadBytes,
        zeroPaddingBytes = 16,
        first64ByteHash = "first64-$index",
        fullBurstHash = fullBurstHash,
        dtsHdSubtype = if (codecFamily == TransportValidationCodecFamily.DTS_HD ||
            codecFamily == TransportValidationCodecFamily.DTS_X
        ) {
            rawPc ushr 8
        } else {
            null
        },
        dtsHdWrapperPresent = dtsHdWrapperPresent,
        dtsHdWrapperPayloadSize = if (dtsHdWrapperPresent == true) payloadBytes else null,
        dtsHdPayloadClassification = dtsHdPayloadClassification,
        codecFailureHint = codecFailureHint
    )
}
