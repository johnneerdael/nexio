package com.nexio.tv.data.repository.benchmark

import com.nexio.tv.core.stream.ParsedStreamInfo
import com.nexio.tv.core.stream.StreamCardModel
import com.nexio.tv.core.stream.StreamTransportKind
import com.nexio.tv.domain.model.Stream
import com.nexio.tv.domain.model.StreamBehaviorHints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BenchmarkAwareStreamScorerTest {

    private val scorer = BenchmarkAwareStreamScorer()

    @Test
    fun `wrapped stream without provider benchmark is rejected`() {
        val event = scorer.score(
            request = request(),
            streams = listOf(streamCard(streamKey = "rd_missing", providerId = "RD")),
            benchmarkSessions = emptyMap()
        )

        assertEquals(0, event.winners.size)
        assertEquals(listOf(ShadowRejectReason.MISSING_BENCHMARK), event.rejected.single().reasons)
    }

    @Test
    fun `suspicious tiny 4k loses to healthier remux on the same provider`() {
        val benchmark = benchmarkResult(
            provider = DebridBenchmarkProvider.REAL_DEBRID,
            optimizedP10Mbps = 180.0
        )
        val event = scorer.score(
            request = request(runtimeMinutes = 120),
            streams = listOf(
                streamCard(
                    streamKey = "tiny_4k",
                    providerId = "RD",
                    resolution = "2160p",
                    quality = "WEBRip",
                    encode = "HEVC",
                    sizeBytes = gib(1.2),
                    durationMs = 120L * 60_000L,
                    visualTags = listOf("HDR10"),
                    audioTags = listOf("Atmos")
                ),
                streamCard(
                    streamKey = "healthy_remux",
                    providerId = "RD",
                    resolution = "2160p",
                    quality = "BluRay Remux",
                    encode = "HEVC",
                    sizeBytes = gib(42.0),
                    durationMs = 120L * 60_000L,
                    visualTags = listOf("DV"),
                    audioTags = listOf("Atmos", "TrueHD")
                )
            ),
            benchmarkSessions = mapOf(DebridBenchmarkProvider.REAL_DEBRID to benchmark)
        )

        assertEquals("healthy_remux", event.selected?.streamKey)
        assertTrue(event.winners.single { it.streamKey == "tiny_4k" }.breakdown.lowQuality4k)
    }

    @Test
    fun `rd remux wins when premiumize budget rejects the stream`() {
        val rdBenchmark = benchmarkResult(
            provider = DebridBenchmarkProvider.REAL_DEBRID,
            optimizedP10Mbps = 205.0
        )
        val pmBenchmark = benchmarkResult(
            provider = DebridBenchmarkProvider.PREMIUMIZE,
            optimizedP10Mbps = 120.0
        )
        val rdStream = streamCard(
            streamKey = "rd_remux",
            providerId = "RD",
            resolution = "2160p",
            quality = "BluRay Remux",
            encode = "HEVC",
            sizeBytes = gib(82.0),
            durationMs = 120L * 60_000L,
            visualTags = listOf("DV"),
            audioTags = listOf("Atmos", "TrueHD")
        )
        val pmStream = streamCard(
            streamKey = "pm_remux",
            providerId = "PM",
            resolution = "2160p",
            quality = "BluRay Remux",
            encode = "HEVC",
            sizeBytes = gib(82.0),
            durationMs = 120L * 60_000L,
            visualTags = listOf("DV"),
            audioTags = listOf("Atmos", "TrueHD")
        )

        val event = scorer.score(
            request = request(runtimeMinutes = 120),
            streams = listOf(rdStream, pmStream),
            benchmarkSessions = mapOf(
                DebridBenchmarkProvider.REAL_DEBRID to rdBenchmark,
                DebridBenchmarkProvider.PREMIUMIZE to pmBenchmark
            )
        )

        assertEquals("rd_remux", event.selected?.streamKey)
        assertEquals(
            listOf(
                ShadowRejectReason.INSUFFICIENT_TRANSPORT_BUDGET,
                ShadowRejectReason.NO_ELIGIBLE_TRANSPORT
            ),
            event.rejected.single { it.streamKey == "pm_remux" }.reasons
        )
    }

    @Test
    fun `optimized transport is selected when direct and optimized are both viable`() {
        val event = scorer.score(
            request = request(runtimeMinutes = 120),
            streams = listOf(streamCard(streamKey = "rd_stream", providerId = "RD")),
            benchmarkSessions = mapOf(
                DebridBenchmarkProvider.REAL_DEBRID to benchmarkResult(
                    provider = DebridBenchmarkProvider.REAL_DEBRID,
                    directP10Mbps = 140.0,
                    optimizedP10Mbps = 200.0,
                    directSeekP95Ms = 380L,
                    optimizedSeekP95Ms = 220L
                )
            )
        )

        val selected = event.selected
        assertNotNull(selected)
        assertEquals(DebridBenchmarkTransportMode.OPTIMIZED, selected?.transport)
    }

    @Test
    fun `scorer uses derived decision metrics as autoplay truth over raw sustained p10`() {
        val event = scorer.score(
            request = request(runtimeMinutes = 120),
            streams = listOf(streamCard(streamKey = "rd_stream", providerId = "RD")),
            benchmarkSessions = mapOf(
                DebridBenchmarkProvider.REAL_DEBRID to benchmarkResult(
                    provider = DebridBenchmarkProvider.REAL_DEBRID,
                    directP10Mbps = 150.0,
                    optimizedP10Mbps = 200.0,
                    directDecisionSafeBudgetMbps = 127.5,
                    optimizedDecisionSafeBudgetMbps = 60.0
                )
            )
        )

        assertEquals(DebridBenchmarkTransportMode.DIRECT, event.selected?.transport)
        assertEquals(127.5, event.selected?.safeBudgetMbps ?: 0.0, 0.0)
    }

    private fun request(runtimeMinutes: Int = 120): ShadowRequestContext {
        return ShadowRequestContext(
            requestId = "req-1",
            videoId = "tt123",
            contentType = "movie",
            title = "Example",
            season = null,
            episode = null,
            runtimeMinutes = runtimeMinutes
        )
    }

    private fun streamCard(
        streamKey: String,
        providerId: String,
        resolution: String = "2160p",
        quality: String = "BluRay Remux",
        encode: String = "HEVC",
        sizeBytes: Long = gib(42.0),
        durationMs: Long = 120L * 60_000L,
        visualTags: List<String> = listOf("DV"),
        audioTags: List<String> = listOf("Atmos", "TrueHD")
    ): StreamCardModel {
        val stream = Stream(
            name = "Example $streamKey",
            title = "Example",
            description = "Example description",
            url = "https://example.com/$streamKey.mkv",
            ytId = null,
            infoHash = "0123456789abcdef0123456789abcdef01234567",
            fileIdx = 0,
            externalUrl = null,
            behaviorHints = StreamBehaviorHints(
                notWebReady = false,
                bingeGroup = null,
                countryWhitelist = null,
                proxyHeaders = null,
                videoHash = null,
                videoSize = sizeBytes,
                filename = "$streamKey.mkv"
            ),
            addonName = "Addon",
            addonLogo = null,
            wrappedProviderId = providerId,
            wrappedOriginalStreamKey = streamKey
        )
        val parsed = ParsedStreamInfo(
            stream = stream,
            title = "Example",
            filename = "$streamKey.mkv",
            sizeBytes = sizeBytes,
            resolution = resolution,
            quality = quality,
            encode = encode,
            visualTags = visualTags,
            audioTags = audioTags,
            audioChannels = listOf("5.1"),
            languages = listOf("English"),
            year = "2026",
            seasons = emptyList(),
            episodes = emptyList(),
            releaseGroup = "GROUP",
            serviceId = providerId,
            isCached = true,
            durationMs = durationMs,
            transportKind = StreamTransportKind.CACHED
        )
        return StreamCardModel(
            stream = stream,
            parsed = parsed,
            title = "Example",
            subtitle = null,
            detailLines = emptyList()
        )
    }

    private fun benchmarkResult(
        provider: DebridBenchmarkProvider,
        directP10Mbps: Double = 150.0,
        optimizedP10Mbps: Double = 180.0,
        directDecisionSafeBudgetMbps: Double = directP10Mbps * 0.85,
        optimizedDecisionSafeBudgetMbps: Double = optimizedP10Mbps * 0.85,
        directSeekP95Ms: Long = 340L,
        optimizedSeekP95Ms: Long = 240L
    ): DebridBenchmarkResult {
        return DebridBenchmarkResult(
            provider = provider,
            measuredAtMs = 42L,
            summary = DebridBenchmarkSummary(
                startupTimeMs = 140L,
                sustainedThroughputMbps = 200.0,
                transferredBytes = 2_048L,
                elapsedMs = 120_000L
            ),
            terminationReason = DebridBenchmarkTerminationReason.COMPLETED,
            device = DeviceCapabilitySnapshot(
                model = "Shield",
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
                    dtshd = AudioEncodingSupport(true, true)
                ),
                capturedAtMs = 40L
            ),
            direct = transportProfile(
                p10Mbps = directP10Mbps,
                averageMbps = directP10Mbps + 10.0,
                startupMs = 180L,
                seekP95Ms = directSeekP95Ms,
                decisionSafeBudgetMbps = directDecisionSafeBudgetMbps
            ),
            optimized = transportProfile(
                p10Mbps = optimizedP10Mbps,
                averageMbps = optimizedP10Mbps + 15.0,
                startupMs = 140L,
                seekP95Ms = optimizedSeekP95Ms,
                decisionSafeBudgetMbps = optimizedDecisionSafeBudgetMbps,
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
            ),
            session = DebridBenchmarkSessionMetadata(
                benchmarkVersion = 3,
                executionOrder = emptyList(),
                totalElapsedMs = 240_000L
            )
        )
    }

    private fun transportProfile(
        p10Mbps: Double,
        averageMbps: Double,
        startupMs: Long,
        seekP95Ms: Long,
        decisionSafeBudgetMbps: Double = p10Mbps * 0.85,
        configSnapshot: DebridBenchmarkTransportConfigSnapshot? = null
    ): DebridBenchmarkTransportProfile {
        return DebridBenchmarkTransportProfile(
            startup = DebridBenchmarkStartupMetrics(
                initialTtfbMs = startupMs,
                startupFailureRate = 0.0
            ),
            sustained = DebridBenchmarkSustainedMetrics(
                averageThroughputMbps = averageMbps,
                derivedAverageThroughputMbps = averageMbps,
                actionable = true,
                p10ThroughputMbps = p10Mbps,
                p50ThroughputMbps = averageMbps,
                peakThroughputMbps = averageMbps + 25.0,
                throughputStddevMbps = 7.0,
                throughputCv = 0.05,
                stallCount = 0,
                maxReadGapMs = 150L,
                bytesTransferred = 2_048L,
                elapsedMs = 120_000L
            ),
            seek = DebridBenchmarkSeekMetrics(
                seekTtfbP50Ms = seekP95Ms - 60L,
                seekTtfbP95Ms = seekP95Ms,
                seekTtfbP99Ms = seekP95Ms + 80L,
                seekTtfbStddevMs = 20.0,
                seekFailRate = 0.0
            ),
            decision = DebridBenchmarkTransportDecisionMetrics(
                safeSustainedBudgetMbps = decisionSafeBudgetMbps,
                actionable = true
            ),
            configSnapshot = configSnapshot,
            rawSamples = DebridBenchmarkRawSamples()
        )
    }

    private fun gib(value: Double): Long {
        return (value * 1024.0 * 1024.0 * 1024.0).toLong()
    }
}
