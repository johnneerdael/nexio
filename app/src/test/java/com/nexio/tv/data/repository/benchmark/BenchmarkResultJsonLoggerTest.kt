package com.nexio.tv.data.repository.benchmark

import com.google.gson.JsonParser
import kotlin.math.max
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BenchmarkResultJsonLoggerTest {

    @Test
    fun `completed benchmark event json is single line and includes device payload`() {
        val logger = BenchmarkResultJsonLogger { _, _ -> }
        val payload = logger.buildCompletedEventJson(sampleResult())
        val root = JsonParser.parseString(payload).asJsonObject

        assertFalse(payload.contains('\n'))
        assertEquals(1, root.get("event_version").asInt)
        assertEquals("benchmark_session_completed", root.get("event_type").asString)
        val result = root.getAsJsonObject("result")
        assertEquals("real_debrid", result.get("provider").asString)
        assertTrue(result.getAsJsonObject("device").getAsJsonArray("displayHdrTypes").containsString("dolby_vision"))
        val evidence = result.getAsJsonObject("device").getAsJsonObject("evidence")
        assertEquals(
            "direct_profiles",
            evidence.getAsJsonObject("audio").get("discoveryMode").asString
        )
        assertEquals(
            "c2.qti.av1.decoder",
            evidence.getAsJsonObject("video").getAsJsonArray("decoders").first().asJsonObject.get("codecName").asString
        )
        assertEquals(1_000L, result.getAsJsonObject("optimized").getAsJsonObject("sustained").get("bucketMs").asLong)
        assertEquals(5_000L, result.getAsJsonObject("optimized").getAsJsonObject("sustained").get("decisionWindowMs").asLong)
        assertEquals(4, result.getAsJsonObject("optimized").getAsJsonObject("configSnapshot").get("parallelConnectionCount").asInt)
    }

    @Test
    fun `summary line exposes winners and safe budgets`() {
        val logger = BenchmarkResultJsonLogger { _, _ -> }

        val summary = logger.buildSummaryLine(sampleResult())

        assertTrue(summary.contains("provider=real_debrid"))
        assertTrue(summary.contains("sustained_winner=optimized"))
        assertFalse(summary.contains("direct_safe_budget="))
        assertTrue(summary.contains("optimized_safe_budget=170.0"))
    }

    @Test
    fun `summary line keeps optimized safe budget when result has no direct or comparison payload`() {
        val logger = BenchmarkResultJsonLogger { _, _ -> }
        val optimizedOnly = sampleResult().copy(
            direct = null,
            comparison = null
        )

        val summary = logger.buildSummaryLine(optimizedOnly)

        assertTrue(summary.contains("provider=real_debrid"))
        assertTrue(summary.contains("optimized_safe_budget=170.0"))
        assertFalse(summary.contains("sustained_winner="))
        assertFalse(summary.contains("seek_winner="))
        assertFalse(summary.contains("stability_winner="))
    }

    @Test
    fun `completed benchmark event json includes derived decision metrics separate from sustained evidence`() {
        val logger = BenchmarkResultJsonLogger { _, _ -> }

        val payload = logger.buildCompletedEventJson(sampleResult())
        val result = JsonParser.parseString(payload).asJsonObject.getAsJsonObject("result")
        val optimizedDecision = result.getAsJsonObject("optimized").getAsJsonObject("decision")

        assertEquals(170.0, optimizedDecision.get("safeSustainedBudgetMbps").asDouble, 0.0)
        assertTrue(optimizedDecision.get("actionable").asBoolean)
        assertEquals(
            220.0,
            result.getAsJsonObject("optimized")
                .getAsJsonObject("sustained")
                .get("averageThroughputMbps")
                .asDouble,
            0.0
        )
    }

    @Test
    fun `failure outcome json includes transport candidate and partial metrics`() {
        val logger = BenchmarkResultJsonLogger { _, _ -> }

        val payload = logger.buildOutcomeEventJson(
            provider = DebridBenchmarkProvider.REAL_DEBRID,
            terminationReason = DebridBenchmarkTerminationReason.FAILED,
            summary = DebridBenchmarkSummary(
                startupTimeMs = 310L,
                sustainedThroughputMbps = 88.5,
                transferredBytes = 345_678_901L,
                elapsedMs = 42_000L
            ),
            failureDetails = DebridBenchmarkFailureDetails(
                candidate = DebridBenchmarkCandidateMetadata(
                    filename = "Example.mkv",
                    sizeBytes = 50L * 1024L * 1024L * 1024L,
                    host = "cdn.example.net",
                    directUrlFingerprint = "abc123def456"
                ),
                failedTransport = DebridBenchmarkTransportMode.DIRECT,
                transportFailure = DebridBenchmarkTransportFailure(
                    exceptionClass = "ChunkWaitTimeoutException",
                    message = "Timed out waiting 60000ms for chunk 8",
                    chunkIndex = 8L,
                    rootCauseClass = "SocketTimeoutException",
                    rootCauseMessage = "Read timed out",
                    recoverableFailureCount = 2,
                    recoverableTimeoutCount = 1
                ),
                direct = sampleProfile(
                    startupMs = 310L,
                    p10Mbps = 104.0,
                    averageMbps = 112.0,
                    seekP95Ms = 0L
                )
            )
        )
        val root = JsonParser.parseString(payload).asJsonObject

        assertFalse(payload.contains('\n'))
        assertEquals("benchmark_session_outcome", root.get("event_type").asString)
        assertEquals("real_debrid", root.get("provider").asString)
        assertEquals("failed", root.get("terminationReason").asString)
        val failure = root.getAsJsonObject("failure")
        assertEquals("direct", failure.get("failedTransport").asString)
        assertEquals("ChunkWaitTimeoutException", failure.getAsJsonObject("transportFailure").get("exceptionClass").asString)
        assertEquals(8L, failure.getAsJsonObject("transportFailure").get("chunkIndex").asLong)
        assertEquals("SocketTimeoutException", failure.getAsJsonObject("transportFailure").get("rootCauseClass").asString)
        assertEquals(2, failure.getAsJsonObject("transportFailure").get("recoverableFailureCount").asInt)
        assertEquals("cdn.example.net", failure.getAsJsonObject("candidate").get("host").asString)
        assertEquals(
            104.0,
            failure.getAsJsonObject("direct")
                .getAsJsonObject("sustained")
                .get("p10ThroughputMbps")
                .asDouble,
            0.0
        )
    }

    @Test
    fun `logCompleted chunks oversized benchmark json into reconstructable parts`() {
        val entries = mutableListOf<Pair<String, String>>()
        val logger = BenchmarkResultJsonLogger { tag, message -> entries += tag to message }
        val result = sampleResult(
            rawWindowCount = 600,
            rawBucketCount = 300,
            rawSeekSampleCount = 100
        )

        logger.logCompleted(result)

        val jsonEntries = entries.filter { it.first == "BenchmarkJson" }
        val summaryEntries = entries.filter { it.first == "BenchmarkSummary" }

        assertTrue(jsonEntries.size > 1)
        assertEquals(1, summaryEntries.size)
        jsonEntries.forEach { (_, message) ->
            assertFalse(message.contains('\n'))
            assertTrue(message.length <= 3600)
        }

        val chunkRoots = jsonEntries.map { JsonParser.parseString(it.second).asJsonObject }
        assertTrue(chunkRoots.all { it.get("event_type").asString == "benchmark_session_completed_chunk" })
        assertTrue(chunkRoots.all { it.get("original_event_type").asString == "benchmark_session_completed" })
        val chunkCount = chunkRoots.first().get("chunk_count").asInt
        assertEquals(chunkCount, chunkRoots.size)

        val reassembled = chunkRoots
            .sortedBy { it.get("chunk_index").asInt }
            .joinToString(separator = "") { it.get("payload").asString }

        assertEquals(logger.buildCompletedEventJson(result), reassembled)
    }

    @Test
    fun `logOutcome chunks oversized benchmark outcome json into reconstructable parts`() {
        val entries = mutableListOf<Pair<String, String>>()
        val logger = BenchmarkResultJsonLogger { tag, message -> entries += tag to message }
        val largeProfile = sampleProfile(
            startupMs = 310L,
            p10Mbps = 104.0,
            averageMbps = 112.0,
            seekP95Ms = 280L,
            rawWindowCount = 500,
            rawBucketCount = 250,
            rawSeekSampleCount = 80
        )

        logger.logOutcome(
            provider = DebridBenchmarkProvider.REAL_DEBRID,
            terminationReason = DebridBenchmarkTerminationReason.FAILED,
            summary = DebridBenchmarkSummary(
                startupTimeMs = 310L,
                sustainedThroughputMbps = 88.5,
                transferredBytes = 345_678_901L,
                elapsedMs = 42_000L
            ),
            failureDetails = DebridBenchmarkFailureDetails(
                candidate = DebridBenchmarkCandidateMetadata(
                    filename = "Example.mkv",
                    sizeBytes = 50L * 1024L * 1024L * 1024L,
                    host = "cdn.example.net",
                    directUrlFingerprint = "abc123def456"
                ),
                failedTransport = DebridBenchmarkTransportMode.DIRECT,
                direct = largeProfile
            )
        )

        val jsonEntries = entries.filter { it.first == "BenchmarkJson" }
        assertTrue(jsonEntries.size > 1)
        val chunkRoots = jsonEntries.map { JsonParser.parseString(it.second).asJsonObject }
        assertTrue(chunkRoots.all { it.get("event_type").asString == "benchmark_session_outcome_chunk" })
        assertTrue(chunkRoots.all { it.get("original_event_type").asString == "benchmark_session_outcome" })
    }

    private fun sampleResult(
        rawWindowCount: Int = 2,
        rawBucketCount: Int = 1,
        rawSeekSampleCount: Int = 1
    ): DebridBenchmarkResult {
        return DebridBenchmarkResult(
            provider = DebridBenchmarkProvider.REAL_DEBRID,
            measuredAtMs = 42L,
            summary = DebridBenchmarkSummary(
                startupTimeMs = 120L,
                sustainedThroughputMbps = 220.0,
                transferredBytes = 1_024L,
                elapsedMs = 120_000L
            ),
            terminationReason = DebridBenchmarkTerminationReason.COMPLETED,
            candidate = DebridBenchmarkCandidateMetadata(
                filename = "Example.mkv",
                sizeBytes = 50L * 1024L * 1024L * 1024L,
                host = "cdn.example.net",
                directUrlFingerprint = "abc123def456"
            ),
            device = DeviceCapabilitySnapshot(
                model = "Shield TV",
                manufacturer = "NVIDIA",
                sdkInt = 35,
                displayHdrTypes = setOf(DeviceHdrType.DOLBY_VISION, DeviceHdrType.HDR10),
                videoDecode = DeviceVideoDecodeCapabilities(
                    h264 = CodecSupport(true, false, true),
                    hevc = CodecSupport(true, false, true),
                    av1 = CodecSupport(false, true, false),
                    dolbyVision = CodecSupport(true, false, true)
                ),
                audioOutput = DeviceAudioOutputCapabilities(
                    ac3 = AudioEncodingSupport(true, true),
                    eac3 = AudioEncodingSupport(true, true),
                    truehd = AudioEncodingSupport(true, true),
                    dts = AudioEncodingSupport(true, true),
                    dtshd = AudioEncodingSupport(false, false)
                ),
                evidence = DeviceCapabilityEvidence(
                    hdr = DeviceHdrCapabilityEvidence(
                        displayId = 0,
                        rawSupportedHdrTypes = listOf("dolby_vision", "hdr10")
                    ),
                    audio = DeviceAudioCapabilityEvidence(
                        discoveryMode = "direct_profiles",
                        routedDeviceTypes = listOf("hdmi"),
                        outputDevices = listOf(
                            AudioOutputDeviceEvidence(
                                id = 2,
                                type = "hdmi",
                                productName = "Receiver",
                                encodings = listOf("ac3", "truehd")
                            )
                        ),
                        directProfiles = listOf(
                            AudioDirectProfileEvidence(
                                format = "truehd",
                                channelMasks = listOf(12),
                                sampleRates = listOf(48000)
                            )
                        )
                    ),
                    video = DeviceVideoDecoderEvidence(
                        scannedDecoderCount = 4,
                        decoders = listOf(
                            VideoDecoderEvidence(
                                codecName = "c2.qti.av1.decoder",
                                mimeType = "video/av01",
                                hardwareAccelerated = true,
                                softwareOnly = false,
                                secureSupported = false
                            )
                        )
                    )
                ),
                capturedAtMs = 99L
            ),
            session = DebridBenchmarkSessionMetadata(
                benchmarkVersion = 3,
                executionOrder = listOf(
                    DebridBenchmarkPhaseExecution(
                        phase = DebridBenchmarkPhase.STARTUP,
                        order = listOf(
                            DebridBenchmarkTransportMode.DIRECT,
                            DebridBenchmarkTransportMode.OPTIMIZED
                        )
                    )
                ),
                totalElapsedMs = 240_000L
            ),
            direct = sampleProfile(
                startupMs = 180L,
                p10Mbps = 150.0,
                averageMbps = 170.0,
                seekP95Ms = 330L,
                rawWindowCount = rawWindowCount,
                rawBucketCount = rawBucketCount,
                rawSeekSampleCount = rawSeekSampleCount
            ),
            optimized = sampleProfile(
                startupMs = 140L,
                p10Mbps = 200.0,
                averageMbps = 220.0,
                seekP95Ms = 240L,
                rawWindowCount = rawWindowCount,
                rawBucketCount = rawBucketCount,
                rawSeekSampleCount = rawSeekSampleCount,
                configSnapshot = DebridBenchmarkTransportConfigSnapshot(
                    useParallelConnections = true,
                    parallelConnectionCount = 4,
                    parallelChunkSizeMb = 8
                )
            ),
            comparison = DebridBenchmarkComparisonSummary(
                sustainedWinner = DebridBenchmarkTransportMode.OPTIMIZED,
                seekWinner = DebridBenchmarkTransportMode.OPTIMIZED,
                stabilityWinner = DebridBenchmarkTransportMode.OPTIMIZED
            )
        )
    }

    private fun sampleProfile(
        startupMs: Long,
        p10Mbps: Double,
        averageMbps: Double,
        seekP95Ms: Long,
        decisionSafeBudgetMbps: Double = p10Mbps * 0.85,
        decisionActionable: Boolean = true,
        rawWindowCount: Int = 2,
        rawBucketCount: Int = 1,
        rawSeekSampleCount: Int = 1,
        configSnapshot: DebridBenchmarkTransportConfigSnapshot? = null
    ): DebridBenchmarkTransportProfile {
        val throughputWindows = List(max(rawWindowCount, 1)) { index ->
            if (index % 2 == 0) p10Mbps else averageMbps
        }
        val throughputBuckets = List(max(rawBucketCount, 1)) { index ->
            val throughput = throughputWindows[index % throughputWindows.size]
            DebridBenchmarkThroughputBucketSample(
                startOffsetMs = index * 1_000L,
                durationMs = 1_000L,
                bytesTransferred = ((throughput * 1_000_000.0 / 8.0) / 1_000.0).toLong(),
                throughputMbps = throughput,
                complete = true
            )
        }
        val seekSamples = List(max(rawSeekSampleCount, 1)) { index ->
            DebridBenchmarkSeekSample(
                targetOffsetBytes = (index + 1) * 1_024L,
                ttfbMs = seekP95Ms + (index % 5).toLong(),
                succeeded = true
            )
        }
        return DebridBenchmarkTransportProfile(
            startup = DebridBenchmarkStartupMetrics(
                initialTtfbMs = startupMs,
                startupFailureRate = 0.0
            ),
            sustained = DebridBenchmarkSustainedMetrics(
                collectorVersion = 5,
                samplingMode = "fixed_time_bucket",
                bucketMs = 1_000L,
                decisionWindowMs = 5_000L,
                averageThroughputMbps = averageMbps,
                derivedAverageThroughputMbps = averageMbps,
                actionable = true,
                p10ThroughputMbps = p10Mbps,
                p50ThroughputMbps = averageMbps,
                peakThroughputMbps = averageMbps + 20.0,
                throughputStddevMbps = 8.0,
                throughputCv = 0.05,
                stallCount = 0,
                maxReadGapMs = 120L,
                bytesTransferred = 2_048L,
                elapsedMs = 120_000L
            ),
            seek = DebridBenchmarkSeekMetrics(
                seekTtfbP50Ms = seekP95Ms - 50L,
                seekTtfbP95Ms = seekP95Ms,
                seekTtfbP99Ms = seekP95Ms + 90L,
                seekTtfbStddevMs = 12.0,
                seekFailRate = 0.0
            ),
            decision = DebridBenchmarkTransportDecisionMetrics(
                safeSustainedBudgetMbps = decisionSafeBudgetMbps,
                actionable = decisionActionable
            ),
            configSnapshot = configSnapshot,
            rawSamples = DebridBenchmarkRawSamples(
                throughputWindowsMbps = throughputWindows,
                throughputBuckets = throughputBuckets,
                seekSamples = seekSamples
            )
        )
    }

    private fun com.google.gson.JsonArray.containsString(expected: String): Boolean {
        return any { it.asString == expected }
    }
}
