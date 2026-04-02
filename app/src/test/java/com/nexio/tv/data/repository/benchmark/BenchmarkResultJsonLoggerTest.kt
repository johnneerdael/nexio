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
        configSnapshot: DebridBenchmarkTransportConfigSnapshot? = null
    ): DebridBenchmarkTransportProfile {
        return DebridBenchmarkTransportProfile(
            startup = DebridBenchmarkStartupMetrics(
                initialTtfbMs = startupMs,
                startupFailureRate = 0.0
            ),
            sustained = DebridBenchmarkSustainedMetrics(
                averageThroughputMbps = averageMbps,
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
            configSnapshot = configSnapshot,
            rawSamples = DebridBenchmarkRawSamples(
                throughputWindowsMbps = listOf(p10Mbps, averageMbps),
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
