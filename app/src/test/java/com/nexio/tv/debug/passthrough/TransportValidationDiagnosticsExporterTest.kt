package com.nexio.tv.debug.passthrough

import java.io.File
import java.util.zip.ZipFile
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransportValidationDiagnosticsExporterTest {

    @Test
    fun `export bundle includes manifest version asset checksums and route snapshot`() {
        val tempDir = createTempDirectory("transport-validation-export-test").toFile()
        val bundle = TransportValidationDiagnosticsExporter.exportBundle(
            session = TransportValidationSessionSnapshot(
                manifestVersion = "2026.03.19",
                sample = TransportValidationSample(
                    id = "truehd",
                    displayName = "Dolby TrueHD",
                    codecFamily = TransportValidationCodecFamily.TRUEHD,
                    sourceAssetPath = "truehd.mkv",
                    elementaryAssetPath = "truehd.thd",
                    referenceAssetPath = "truehd.spdif",
                    expectedPc = 0x16,
                    pdRule = TransportValidationPdRule.TRUEHD_MAT,
                    expectedBurstModel = TransportValidationBurstModel.MAT,
                    expectedRouteTuple = TransportValidationRouteTuple(
                        encoding = "IEC61937",
                        sampleRate = 192000,
                        channelMask = "7.1"
                    ),
                    assetChecksums = mapOf(
                        "sourceAssetPath" to "source-sha",
                        "elementaryAssetPath" to "elementary-sha",
                        "referenceAssetPath" to "reference-sha"
                    )
                ),
                routeSnapshot = TransportValidationRouteSnapshot(
                    deviceName = "HDMI",
                    encoding = "IEC61937",
                    sampleRate = 192000,
                    channelMask = "7.1",
                    directPlaybackSupported = true,
                    audioTrackState = 1
                ),
                referenceBursts = listOf(
                    burstRecord(0)
                ),
                packerInputBursts = emptyList(),
                packedBursts = emptyList(),
                audioTrackWriteBursts = emptyList(),
                comparisonResults = emptyList()
            ),
            outputDirectory = tempDir,
            includeBinaryDumps = false
        )

        assertTrue(bundle.exists())

        ZipFile(bundle).use { zip ->
            val summary = zip.getEntry("summary.json")
            val summaryText = zip.getInputStream(summary).bufferedReader().use { it.readText() }
            assertTrue(summaryText.contains("\"manifestVersion\":\"2026.03.19\""))
            assertTrue(summaryText.contains("\"elementaryAssetPath\":\"truehd.thd\""))
            assertTrue(summaryText.contains("\"referenceAssetPath\":\"reference-sha\""))
            assertTrue(summaryText.contains("\"encoding\":\"IEC61937\""))
            assertEquals(
                setOf(
                    "summary.json",
                    "comparison-results.json",
                    "reference-bursts.json",
                    "packer-input-bursts.json",
                    "packed-bursts.json",
                    "audiotrack-write-bursts.json"
                ),
                zip.entries().asSequence().map { it.name }.toSet()
            )
        }
    }

    private fun burstRecord(index: Int): TransportValidationBurstRecord = TransportValidationBurstRecord(
        codecFamily = TransportValidationCodecFamily.TRUEHD,
        sampleId = "truehd",
        burstIndex = index,
        sourcePtsUs = 1_000_000L + index,
        rawBytes = ByteArray(64) { index.toByte() },
        burstSizeBytes = 61440,
        pa = 0xF872,
        pb = 0x4E1F,
        rawPc = 0x16,
        pd = 61424,
        payloadBytes = 61424,
        zeroPaddingBytes = 8,
        first64ByteHash = "first64",
        fullBurstHash = "full"
    )
}
