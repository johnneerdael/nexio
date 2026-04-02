package com.nexio.tv.data.repository.benchmark

import com.google.gson.JsonParser
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
        assertEquals(1_000L, result.getAsJsonObject("optimized").getAsJsonObject("sustained").get("bucketMs").asLong)
        assertEquals(4, result.getAsJsonObject("optimized").getAsJsonObject("configSnapshot").get("parallelConnectionCount").asInt)
    }

    @Test
    fun `summary line exposes winners and safe budgets`() {
        val logger = BenchmarkResultJsonLogger { _, _ -> }

        val summary = logger.buildSummaryLine(sampleResult())

        assertTrue(summary.contains("provider=real_debrid"))
        assertTrue(summary.contains("sustained_winner=optimized"))
        assertTrue(summary.contains("direct_safe_budget=127.5"))
        assertTrue(summary.contains("optimized_safe_budget=170.0"))
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

    private fun sampleResult(): DebridBenchmarkResult {
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
                seekP95Ms = 330L
            ),
            optimized = sampleProfile(
                startupMs = 140L,
                p10Mbps = 200.0,
                averageMbps = 220.0,
                seekP95Ms = 240L,
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
        configSnapshot: DebridBenchmarkTransportConfigSnapshot? = null
    ): DebridBenchmarkTransportProfile {
        return DebridBenchmarkTransportProfile(
            startup = DebridBenchmarkStartupMetrics(
                initialTtfbMs = startupMs,
                startupFailureRate = 0.0
            ),
            sustained = DebridBenchmarkSustainedMetrics(
                collectorVersion = 2,
                samplingMode = "fixed_time_bucket",
                bucketMs = 1_000L,
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
                throughputWindowsMbps = listOf(p10Mbps, averageMbps),
                throughputBuckets = listOf(
                    DebridBenchmarkThroughputBucketSample(
                        startOffsetMs = 0L,
                        durationMs = 1_000L,
                        bytesTransferred = ((p10Mbps * 1_000_000.0 / 8.0) / 1_000.0).toLong(),
                        throughputMbps = p10Mbps,
                        complete = true
                    )
                ),
                seekSamples = listOf(
                    DebridBenchmarkSeekSample(
                        targetOffsetBytes = 1_024L,
                        ttfbMs = seekP95Ms,
                        succeeded = true
                    )
                )
            )
        )
    }

    private fun com.google.gson.JsonArray.containsString(expected: String): Boolean {
        return any { it.asString == expected }
    }
}
