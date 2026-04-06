package com.nexio.tv.ui.screens.stream

import com.nexio.tv.core.stream.ParsedStreamInfo
import com.nexio.tv.core.stream.StreamCardModel
import com.nexio.tv.core.stream.StreamTransportKind
import com.nexio.tv.data.repository.benchmark.AudioEncodingSupport
import com.nexio.tv.data.repository.benchmark.BenchmarkAwareStreamScorer
import com.nexio.tv.data.repository.benchmark.CodecSupport
import com.nexio.tv.data.repository.benchmark.DebridBenchmarkComparisonSummary
import com.nexio.tv.data.repository.benchmark.DebridBenchmarkProvider
import com.nexio.tv.data.repository.benchmark.DebridBenchmarkRawSamples
import com.nexio.tv.data.repository.benchmark.DebridBenchmarkResult
import com.nexio.tv.data.repository.benchmark.DebridBenchmarkSeekMetrics
import com.nexio.tv.data.repository.benchmark.DebridBenchmarkSessionMetadata
import com.nexio.tv.data.repository.benchmark.DebridBenchmarkStartupMetrics
import com.nexio.tv.data.repository.benchmark.DebridBenchmarkSustainedMetrics
import com.nexio.tv.data.repository.benchmark.DebridBenchmarkSummary
import com.nexio.tv.data.repository.benchmark.DebridBenchmarkTerminationReason
import com.nexio.tv.data.repository.benchmark.DebridBenchmarkTransportConfigSnapshot
import com.nexio.tv.data.repository.benchmark.DebridBenchmarkTransportDecisionMetrics
import com.nexio.tv.data.repository.benchmark.DebridBenchmarkTransportMode
import com.nexio.tv.data.repository.benchmark.DebridBenchmarkTransportProfile
import com.nexio.tv.data.repository.benchmark.DeviceAudioOutputCapabilities
import com.nexio.tv.data.repository.benchmark.DeviceCapabilitySnapshot
import com.nexio.tv.data.repository.benchmark.DeviceHdrType
import com.nexio.tv.data.repository.benchmark.DeviceVideoDecodeCapabilities
import com.nexio.tv.data.repository.benchmark.ShadowRejectReason
import com.nexio.tv.data.repository.benchmark.ShadowRequestContext
import com.nexio.tv.domain.model.Stream
import com.nexio.tv.domain.model.StreamBehaviorHints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class StreamScreenShadowSelectionTest {

    private val scorer = BenchmarkAwareStreamScorer()

    @Test
    fun `coordinator does not emit before a terminal decision even when benchmarks arrive later`() {
        val coordinator = ShadowAutoPlayReplayCoordinator(scorer)
        coordinator.updateCandidates(
            request(),
            listOf(streamCard(streamKey = "rd_stream", providerId = "RD")),
            DebridBenchmarkTransportMode.OPTIMIZED,
            isFinalPass = false,
            allowEarlyFinishTerminal = true
        )

        val beforeBenchmark = coordinator.buildEventIfReady(emptyMap())
        assertNull(beforeBenchmark)

        val afterBenchmark = coordinator.buildEventIfReady(
            mapOf(DebridBenchmarkProvider.REAL_DEBRID to benchmarkResult(DebridBenchmarkProvider.REAL_DEBRID))
        )

        assertNull(afterBenchmark)
    }

    @Test
    fun `coordinator emits once when benchmark replay reaches early finish terminal state`() {
        val coordinator = ShadowAutoPlayReplayCoordinator(scorer)
        val benchmarks = mapOf(
            DebridBenchmarkProvider.REAL_DEBRID to benchmarkResult(DebridBenchmarkProvider.REAL_DEBRID),
            DebridBenchmarkProvider.PREMIUMIZE to benchmarkResult(DebridBenchmarkProvider.PREMIUMIZE),
            DebridBenchmarkProvider.TORBOX to benchmarkResult(DebridBenchmarkProvider.TORBOX)
        )

        coordinator.updateCandidates(
            request(),
            listOf(
                streamCard(streamKey = "rd_stream", providerId = "RD"),
                streamCard(streamKey = "pm_stream", providerId = "PM"),
                streamCard(streamKey = "tb_stream", providerId = "TB")
            ),
            DebridBenchmarkTransportMode.OPTIMIZED,
            isFinalPass = false,
            allowEarlyFinishTerminal = true
        )

        val event = coordinator.buildEventIfReady(benchmarks)
        assertNotNull(event)
        assertEquals(DebridBenchmarkTransportMode.OPTIMIZED, event?.selected?.transport)
        assertNull(coordinator.buildEventIfReady(benchmarks))
    }

    @Test
    fun `coordinator winner can change as later stream batches add a better candidate`() {
        val coordinator = ShadowAutoPlayReplayCoordinator(scorer)
        val benchmarks = mapOf(
            DebridBenchmarkProvider.REAL_DEBRID to benchmarkResult(
                provider = DebridBenchmarkProvider.REAL_DEBRID,
                optimizedP10Mbps = 220.0
            ),
            DebridBenchmarkProvider.PREMIUMIZE to benchmarkResult(
                provider = DebridBenchmarkProvider.PREMIUMIZE,
                optimizedP10Mbps = 120.0
            )
        )

        coordinator.updateCandidates(
            request(),
            listOf(
                streamCard(
                    streamKey = "pm_ok",
                    providerId = "PM",
                    resolution = "2160p",
                    quality = "WEBRip",
                    sizeBytes = gib(12.0)
                )
            ),
            DebridBenchmarkTransportMode.OPTIMIZED,
            isFinalPass = false,
            allowEarlyFinishTerminal = true
        )
        val earlyEvent = coordinator.buildEventIfReady(benchmarks)
        assertNull(earlyEvent)

        coordinator.updateCandidates(
            request(),
            listOf(
                streamCard(
                    streamKey = "pm_ok",
                    providerId = "PM",
                    resolution = "2160p",
                    quality = "WEBRip",
                    sizeBytes = gib(12.0)
                ),
                streamCard(
                    streamKey = "rd_remux",
                    providerId = "RD",
                    resolution = "2160p",
                    quality = "BluRay Remux",
                    sizeBytes = gib(82.0)
                )
            ),
            DebridBenchmarkTransportMode.OPTIMIZED,
            isFinalPass = true,
            allowEarlyFinishTerminal = true
        )
        val laterEvent = coordinator.buildEventIfReady(benchmarks)
        assertEquals("rd_remux", laterEvent?.selected?.streamKey)
        assertEquals(DebridBenchmarkTransportMode.OPTIMIZED, laterEvent?.selected?.transport)
        assertNull(coordinator.buildEventIfReady(benchmarks))
    }

    @Test
    fun `shared CDN host still distinguishes candidates by service key`() {
        val coordinator = ShadowAutoPlayReplayCoordinator(scorer)
        val benchmarks = mapOf(
            DebridBenchmarkProvider.REAL_DEBRID to benchmarkResult(
                provider = DebridBenchmarkProvider.REAL_DEBRID,
                optimizedP10Mbps = 120.0
            ),
            DebridBenchmarkProvider.PREMIUMIZE to benchmarkResult(
                provider = DebridBenchmarkProvider.PREMIUMIZE,
                optimizedP10Mbps = 260.0
            )
        )

        coordinator.updateCandidates(
            request(),
            listOf(
                streamCard(streamKey = "rd_same_host", providerId = "RD"),
                streamCard(streamKey = "pm_same_host", providerId = "PM")
            ),
            DebridBenchmarkTransportMode.OPTIMIZED,
            isFinalPass = true,
            allowEarlyFinishTerminal = true
        )

        val event = coordinator.buildEventIfReady(benchmarks)

        assertEquals("pm_same_host", event?.selected?.streamKey)
        assertEquals(DebridBenchmarkTransportMode.OPTIMIZED, event?.selected?.transport)
    }

    @Test
    fun `coordinator clear drops stale candidates`() {
        val coordinator = ShadowAutoPlayReplayCoordinator(scorer)
        coordinator.updateCandidates(
            request(),
            listOf(streamCard(streamKey = "rd_stream", providerId = "RD")),
            DebridBenchmarkTransportMode.OPTIMIZED,
            isFinalPass = false,
            allowEarlyFinishTerminal = true
        )

        coordinator.clear()

        assertNull(
            coordinator.buildEventIfReady(
                mapOf(DebridBenchmarkProvider.REAL_DEBRID to benchmarkResult(DebridBenchmarkProvider.REAL_DEBRID))
            )
        )
    }

    @Test
    fun `coordinator can replay direct only path when parallel playback is disabled`() {
        val coordinator = ShadowAutoPlayReplayCoordinator(scorer)
        coordinator.updateCandidates(
            request(),
            listOf(streamCard(streamKey = "rd_stream", providerId = "RD")),
            DebridBenchmarkTransportMode.DIRECT,
            isFinalPass = true,
            allowEarlyFinishTerminal = false
        )

        val event = coordinator.buildEventIfReady(
            mapOf(DebridBenchmarkProvider.REAL_DEBRID to benchmarkResult(DebridBenchmarkProvider.REAL_DEBRID))
        )

        assertEquals(DebridBenchmarkTransportMode.DIRECT, event?.selected?.transport)
    }

    @Test
    fun `coordinator emits one final no-winner event after final pass`() {
        val coordinator = ShadowAutoPlayReplayCoordinator(scorer)
        coordinator.updateCandidates(
            request(),
            listOf(streamCard(streamKey = "http_stream", providerId = null)),
            DebridBenchmarkTransportMode.OPTIMIZED,
            isFinalPass = true,
            allowEarlyFinishTerminal = false
        )

        val event = coordinator.buildEventIfReady(emptyMap())
        assertNotNull(event)
        assertNull(event?.selected)
        assertEquals(listOf(ShadowRejectReason.NOT_DEBRID_WRAPPED), event?.rejected?.single()?.reasons)
        assertNull(coordinator.buildEventIfReady(emptyMap()))
    }

    private fun request(): ShadowRequestContext {
        return ShadowRequestContext(
            requestId = "req-1",
            videoId = "tt123",
            contentType = "movie",
            title = "Example",
            season = null,
            episode = null,
            runtimeMinutes = 120
        )
    }

    private fun streamCard(
        streamKey: String,
        providerId: String?,
        resolution: String = "2160p",
        quality: String = "BluRay Remux",
        encode: String = "HEVC",
        sizeBytes: Long = gib(42.0),
        durationMs: Long = 120L * 60_000L
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
            visualTags = listOf("DV"),
            audioTags = listOf("Atmos", "TrueHD"),
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
