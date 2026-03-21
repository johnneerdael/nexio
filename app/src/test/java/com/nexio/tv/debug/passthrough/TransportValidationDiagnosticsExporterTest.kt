package com.nexio.tv.debug.passthrough

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.zip.ZipFile
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
                assetSource = TransportValidationAssetSourceSnapshot(
                    baseUrl = "https://files.thepi.es/validator",
                    manifestUrl = "https://files.thepi.es/validator/transport_validation_manifest.json",
                    manifestVersion = "2026.03.19",
                    source = TransportValidationCachedAssetSnapshot(
                        remotePath = "truehd.mkv",
                        localFileName = "truehd.mkv",
                        localFilePath = "/tmp/truehd.mkv",
                        checksum = "source-sha",
                        cacheState = TransportValidationAssetCacheState.REUSED,
                    ),
                    reference = TransportValidationCachedAssetSnapshot(
                        remotePath = "truehd.spdif",
                        localFileName = "truehd.spdif",
                        localFilePath = "/tmp/truehd.spdif",
                        checksum = "reference-sha",
                        cacheState = TransportValidationAssetCacheState.DOWNLOADED,
                    ),
                    elementary = TransportValidationCachedAssetSnapshot(
                        remotePath = "truehd.thd",
                        localFileName = "truehd.thd",
                        localFilePath = "/tmp/truehd.thd",
                        checksum = "elementary-sha",
                        cacheState = TransportValidationAssetCacheState.REUSED,
                    ),
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
                comparisonResults = emptyList(),
                transportVerdict = TransportValidationVerdict.PASS,
                runtimeSnapshot = TransportValidationRuntimeSnapshot(
                    summary = TransportValidationRuntimeSummary(
                        enabled = true,
                        thresholds = TransportValidationRuntimeDefaults.thresholds,
                        startedAtElapsedRealtimeMs = 100L,
                        stableStartAtElapsedRealtimeMs = 360L,
                        routeScoringStartAtElapsedRealtimeMs = 1360L,
                        prepareRequestedAtElapsedRealtimeMs = 110L,
                        timeToReadyMs = 200L,
                        timeToIsPlayingMs = 250L,
                        playerStateVerdict = TransportValidationRuntimeVerdict.PASS,
                        sinkContinuityVerdict = TransportValidationRuntimeVerdict.PASS,
                        routeStabilityVerdict = TransportValidationRuntimeVerdict.PASS,
                        operatorObservationVerdict = TransportValidationRuntimeVerdict.DEGRADED,
                        operatorObservation = TransportValidationOperatorObservation(
                            avrLock = TransportValidationAvrLockQuality.WEAK,
                            audioQuality = TransportValidationAudioQuality.CHOPPY,
                            note = "glitches",
                        ),
                        verdict = TransportValidationRuntimeVerdict.PASS,
                    ),
                    playbackStats = TransportValidationPlaybackStatsSummary(
                        totalJoinTimeMs = 200L,
                        totalPlayTimeMs = 1000L,
                        totalRebufferTimeMs = 0L,
                        totalWaitTimeMs = 200L,
                        totalElapsedTimeMs = 1200L,
                        totalRebufferCount = 0,
                        totalDroppedFrames = 0,
                        totalAudioUnderruns = 0,
                        fatalErrorCount = 0,
                        nonFatalErrorCount = 0,
                    ),
                    playerEvents = listOf(
                        TransportValidationRuntimePlayerEvent(
                            elapsedRealtimeMs = 110L,
                            type = "playback_state_changed",
                            playbackState = 2,
                        )
                    ),
                    analyticsEvents = listOf(
                        TransportValidationRuntimeAnalyticsEvent(
                            elapsedRealtimeMs = 250L,
                            type = "rendered_first_frame",
                        )
                    ),
                    sinkHealth = TransportValidationSinkHealthSummary(
                        writeAttemptCount = 8,
                        successfulWriteCount = 7,
                        zeroWriteCount = 1,
                        writeEvents = listOf(
                            TransportValidationSinkWriteEventRecord(
                                elapsedRealtimeMs = 300L,
                                type = "audio_write_zero",
                                requestedBytes = 61440,
                                value = 61440L,
                                detail = TransportValidationSinkWriteEventDetail(
                                    pendingRemainderId = 17L,
                                    ownership = "startup",
                                    pendingRemainderRetryCount = 2,
                                    retryEligibleReason = "playback_head_advanced",
                                ),
                            )
                        ),
                    ),
                    routeHealth = TransportValidationRouteHealthSummary(
                        stableStartAtElapsedRealtimeMs = 360L,
                        routeScoringStartAtElapsedRealtimeMs = 1360L,
                        routeChangeCountAfterStableStart = 1,
                    ),
                    playbackHeadHealth = TransportValidationPlaybackHeadHealthSummary(
                        sampleCount = 2,
                        longestStallMs = 0L,
                    ),
                )
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
            assertTrue(summaryText.contains("\"transportVerdict\":\"PASS\""))
            assertTrue(summaryText.contains("\"runtimeVerdict\":\"PASS\""))
            assertTrue(summaryText.contains("\"baseUrl\":\"https://files.thepi.es/validator\""))
            val sinkHealthText =
                zip.getInputStream(zip.getEntry("sink-health.json")).bufferedReader().use { it.readText() }
            val sinkHealthJson = Json.parseToJsonElement(sinkHealthText).jsonObject
            val writeEvents = sinkHealthJson.getValue("writeEvents").jsonArray
            assertEquals(1, writeEvents.size)
            val firstEvent = writeEvents.first().jsonObject
            assertEquals("audio_write_zero", firstEvent.getValue("type").jsonPrimitive.content)
            val detail = firstEvent.getValue("detail").jsonObject
            assertEquals("17", detail.getValue("pendingRemainderId").jsonPrimitive.content)
            assertEquals("startup", detail.getValue("ownership").jsonPrimitive.content)
            assertEquals(
                setOf(
                    "summary.json",
                    "comparison-results.json",
                    "asset-source.json",
                    "runtime-summary.json",
                    "playback-stats.json",
                    "player-events.json",
                    "analytics-events.json",
                    "sink-health.json",
                    "route-health.json",
                    "playback-head-health.json",
                    "operator-observation.json",
                    "reference-bursts.json",
                    "packer-input-bursts.json",
                    "packed-bursts.json",
                    "audiotrack-write-bursts.json"
                ),
                zip.entries().asSequence().map { it.name }.toSet()
            )
        }
    }

    @Test
    fun `export bundle reconstructs sink health from analytics events when snapshot sink health is missing`() {
        val tempDir = createTempDirectory("transport-validation-export-fallback-test").toFile()
        val bundle = TransportValidationDiagnosticsExporter.exportBundle(
            session = TransportValidationSessionSnapshot(
                manifestVersion = "2026.03.21",
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
                    assetChecksums = emptyMap(),
                ),
                referenceBursts = listOf(burstRecord(0)),
                packerInputBursts = emptyList(),
                packedBursts = emptyList(),
                audioTrackWriteBursts = emptyList(),
                comparisonResults = emptyList(),
                transportVerdict = TransportValidationVerdict.PASS,
                runtimeSnapshot = TransportValidationRuntimeSnapshot(
                    summary = TransportValidationRuntimeSummary(
                        enabled = true,
                        thresholds = TransportValidationRuntimeDefaults.thresholds,
                        startedAtElapsedRealtimeMs = 100L,
                        verdict = TransportValidationRuntimeVerdict.DEGRADED,
                    ),
                    analyticsEvents = listOf(
                        TransportValidationRuntimeAnalyticsEvent(
                            elapsedRealtimeMs = 250L,
                            type = "audio_write_partial",
                            value = 3054L,
                            detail =
                                "requestedBytes=23040 pendingRemainderId=26 packetId=26 " +
                                    "ownership=steady_state firstOffsetBytes=38400 offsetBytes=38400 " +
                                    "bytesRemaining=23040 retryEligibleReason=forced_retry",
                        ),
                        TransportValidationRuntimeAnalyticsEvent(
                            elapsedRealtimeMs = 260L,
                            type = "audio_write_deferred",
                            value = 23040L,
                            detail =
                                "requestedBytes=23040 pendingRemainderId=26 packetId=26 " +
                                    "ownership=steady_state firstOffsetBytes=38400 offsetBytes=41454 " +
                                    "bytesRemaining=19986 retryEligibleReason=not_eligible",
                        ),
                    ),
                    sinkHealth = null,
                )
            ),
            outputDirectory = tempDir,
            includeBinaryDumps = false
        )

        ZipFile(bundle).use { zip ->
            val sinkHealthEntry = zip.getEntry("sink-health.json")
            assertNotEquals(0L, sinkHealthEntry.size)
            val sinkHealthText =
                zip.getInputStream(sinkHealthEntry).bufferedReader().use { it.readText() }
            assertTrue(sinkHealthText.isNotBlank())
            val sinkHealthJson = Json.parseToJsonElement(sinkHealthText).jsonObject
            assertEquals("1", sinkHealthJson.getValue("writeAttemptCount").jsonPrimitive.content)
            assertEquals("1", sinkHealthJson.getValue("partialWriteCount").jsonPrimitive.content)
            assertEquals("0", sinkHealthJson.getValue("zeroWriteCount").jsonPrimitive.content)
            val writeEvents = sinkHealthJson.getValue("writeEvents").jsonArray
            assertEquals(2, writeEvents.size)
            assertEquals(
                "audio_write_deferred",
                writeEvents[1].jsonObject.getValue("type").jsonPrimitive.content,
            )
        }
    }

    @Test
    fun `export bundle keeps explicit zero route counters in route health`() {
        val tempDir = createTempDirectory("transport-validation-route-health-test").toFile()
        val bundle = TransportValidationDiagnosticsExporter.exportBundle(
            session = TransportValidationSessionSnapshot(
                manifestVersion = "2026.03.21",
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
                    assetChecksums = emptyMap(),
                ),
                referenceBursts = listOf(burstRecord(0)),
                packerInputBursts = emptyList(),
                packedBursts = emptyList(),
                audioTrackWriteBursts = emptyList(),
                comparisonResults = emptyList(),
                transportVerdict = TransportValidationVerdict.PASS,
                runtimeSnapshot = TransportValidationRuntimeSnapshot(
                    summary = TransportValidationRuntimeSummary(
                        enabled = true,
                        thresholds = TransportValidationRuntimeDefaults.thresholds,
                        startedAtElapsedRealtimeMs = 100L,
                        verdict = TransportValidationRuntimeVerdict.DEGRADED,
                    ),
                    routeHealth = TransportValidationRouteHealthSummary(
                        stableStartAtElapsedRealtimeMs = 360L,
                        routeScoringStartAtElapsedRealtimeMs = 1360L,
                        routeChangeCount = 0,
                        routeChangeCountAfterStableStart = 0,
                        routeTupleChangeCountAfterStableStart = 0,
                        routeReopenCountAfterStart = 0,
                        initialRouteSnapshot = TransportValidationRouteSnapshot(
                            deviceName = "",
                            encoding = "IEC61937",
                            sampleRate = 192000,
                            channelMask = "7.1",
                            directPlaybackSupported = true,
                            audioTrackState = 1,
                        ),
                        finalRouteSnapshot = TransportValidationRouteSnapshot(
                            deviceName = "",
                            encoding = "IEC61937",
                            sampleRate = 192000,
                            channelMask = "7.1",
                            directPlaybackSupported = true,
                            audioTrackState = 1,
                        ),
                        samples = listOf(
                            TransportValidationTimedRouteSnapshot(
                                elapsedRealtimeMs = 300L,
                                snapshot = TransportValidationRouteSnapshot(
                                    deviceName = "",
                                    encoding = "IEC61937",
                                    sampleRate = 192000,
                                    channelMask = "7.1",
                                    directPlaybackSupported = true,
                                    audioTrackState = 1,
                                ),
                            )
                        ),
                    ),
                )
            ),
            outputDirectory = tempDir,
            includeBinaryDumps = false,
        )

        ZipFile(bundle).use { zip ->
            val routeHealthText =
                zip.getInputStream(zip.getEntry("route-health.json")).bufferedReader().use { it.readText() }
            val routeHealthJson = Json.parseToJsonElement(routeHealthText).jsonObject
            assertEquals("0", routeHealthJson.getValue("routeChangeCount").jsonPrimitive.content)
            assertEquals(
                "0",
                routeHealthJson.getValue("routeChangeCountAfterStableStart").jsonPrimitive.content,
            )
            assertEquals(
                "0",
                routeHealthJson.getValue("routeTupleChangeCountAfterStableStart").jsonPrimitive.content,
            )
            assertEquals(
                "0",
                routeHealthJson.getValue("routeReopenCountAfterStart").jsonPrimitive.content,
            )
            assertEquals(0, routeHealthJson.getValue("routeReopenCandidates").jsonArray.size)
        }
    }

    @Test
    fun `export bundle preserves default runtime summary and playback head fields`() {
        val tempDir = createTempDirectory("transport-validation-default-fields-test").toFile()
        val bundle = TransportValidationDiagnosticsExporter.exportBundle(
            session = TransportValidationSessionSnapshot(
                manifestVersion = "2026.03.21",
                sample = sample(),
                referenceBursts = listOf(burstRecord(0)),
                transportVerdict = TransportValidationVerdict.PASS,
                runtimeSnapshot = TransportValidationRuntimeSnapshot(
                    summary = TransportValidationRuntimeSummary(
                        enabled = true,
                        thresholds = TransportValidationRuntimeDefaults.thresholds,
                        startedAtElapsedRealtimeMs = 100L,
                    ),
                    playbackHeadHealth = TransportValidationPlaybackHeadHealthSummary(),
                ),
            ),
            outputDirectory = tempDir,
            includeBinaryDumps = false,
        )

        ZipFile(bundle).use { zip ->
            val runtimeSummaryText =
                zip.getInputStream(zip.getEntry("runtime-summary.json")).bufferedReader().use { it.readText() }
            val runtimeSummaryJson = Json.parseToJsonElement(runtimeSummaryText).jsonObject
            assertEquals("UNKNOWN", runtimeSummaryJson.getValue("verdict").jsonPrimitive.content)
            assertEquals(0, runtimeSummaryJson.getValue("failureCodes").jsonArray.size)
            assertEquals("UNKNOWN", runtimeSummaryJson.getValue("playerStateVerdict").jsonPrimitive.content)

            val playbackHeadText =
                zip.getInputStream(zip.getEntry("playback-head-health.json")).bufferedReader().use { it.readText() }
            val playbackHeadJson = Json.parseToJsonElement(playbackHeadText).jsonObject
            assertEquals("0", playbackHeadJson.getValue("backwardJumpCount").jsonPrimitive.content)
            assertEquals("true", playbackHeadJson.getValue("monotonic").jsonPrimitive.content)
            assertEquals("false", playbackHeadJson.getValue("stalledWhilePlaying").jsonPrimitive.content)
        }
    }

    private fun sample(): TransportValidationSample = TransportValidationSample(
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
            channelMask = "7.1",
        ),
        assetChecksums = emptyMap(),
    )

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
