package com.nexio.tv.data.repository.benchmark

import com.nexio.tv.core.stream.ParsedStreamInfo
import com.nexio.tv.core.stream.StreamCardModel
import com.nexio.tv.core.stream.StreamTransportKind
import com.nexio.tv.domain.model.Stream
import com.nexio.tv.domain.model.StreamBehaviorHints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
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
    fun `parser service id is used when wrapped provider id is missing`() {
        val event = scorer.score(
            request = request(runtimeMinutes = 120),
            streams = listOf(
                streamCard(
                    streamKey = "rd_parser_only",
                    providerId = "RD",
                    wrappedProviderId = null,
                    filename = "Parser.Only.Release.mkv"
                )
            ),
            benchmarkSessions = mapOf(
                DebridBenchmarkProvider.REAL_DEBRID to benchmarkResult(
                    provider = DebridBenchmarkProvider.REAL_DEBRID
                )
            )
        )

        assertEquals("rd_parser_only", event.selected?.streamKey)
        assertEquals("RD", event.selected?.parsed?.serviceId)
        assertEquals("Parser.Only.Release.mkv", event.selected?.parsed?.filename)
    }

    @Test
    fun `runtime falls back to metadata when parser duration is missing`() {
        val event = scorer.score(
            request = request(runtimeMinutes = 46),
            streams = listOf(
                streamCard(
                    streamKey = "rd_metadata_runtime",
                    providerId = "RD",
                    sizeBytes = gib(8.0),
                    durationMs = 0L
                )
            ),
            benchmarkSessions = mapOf(
                DebridBenchmarkProvider.REAL_DEBRID to benchmarkResult(
                    provider = DebridBenchmarkProvider.REAL_DEBRID
                )
            )
        )

        assertEquals(46L * 60_000L, event.selected?.parsed?.durationMs)
        assertEquals("metadata", event.selected?.parsed?.runtimeSource)
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

    @Test
    fun `ddp atmos passthrough beats truehd atmos pcm fallback on the same device`() {
        val benchmark = benchmarkResult(
            provider = DebridBenchmarkProvider.REAL_DEBRID,
            device = deviceSnapshot(
                truehdSupported = true,
                truehdPassthrough = false,
                eac3Supported = true,
                eac3Passthrough = true,
                ac3Supported = true,
                ac3Passthrough = true,
                dtsSupported = false,
                dtsPassthrough = false,
                dtshdSupported = false,
                dtshdPassthrough = false
            )
        )
        val event = scorer.score(
            request = request(runtimeMinutes = 120),
            streams = listOf(
                streamCard(
                    streamKey = "truehd_pcm",
                    providerId = "RD",
                    audioTags = listOf("Atmos", "TrueHD")
                ),
                streamCard(
                    streamKey = "ddp_atmos",
                    providerId = "RD",
                    audioTags = listOf("Atmos", "DD+")
                )
            ),
            benchmarkSessions = mapOf(DebridBenchmarkProvider.REAL_DEBRID to benchmark)
        )

        assertEquals("ddp_atmos", event.selected?.streamKey)
    }

    @Test
    fun `truehd atmos pcm fallback still beats basic ac3 passthrough`() {
        val benchmark = benchmarkResult(
            provider = DebridBenchmarkProvider.REAL_DEBRID,
            device = deviceSnapshot(
                truehdSupported = true,
                truehdPassthrough = false,
                eac3Supported = false,
                eac3Passthrough = false,
                ac3Supported = true,
                ac3Passthrough = true,
                dtsSupported = false,
                dtsPassthrough = false,
                dtshdSupported = false,
                dtshdPassthrough = false
            )
        )
        val event = scorer.score(
            request = request(runtimeMinutes = 120),
            streams = listOf(
                streamCard(
                    streamKey = "truehd_pcm",
                    providerId = "RD",
                    audioTags = listOf("Atmos", "TrueHD")
                ),
                streamCard(
                    streamKey = "ac3_passthrough",
                    providerId = "RD",
                    audioTags = listOf("AC3")
                )
            ),
            benchmarkSessions = mapOf(DebridBenchmarkProvider.REAL_DEBRID to benchmark)
        )

        assertEquals("truehd_pcm", event.selected?.streamKey)
    }

    @Test
    fun `json tuned config can flip audio preference without changing code`() {
        val tunedConfig = BenchmarkAwareStreamScoringConfig.fromJson(
            BenchmarkAwareStreamScoringConfig.default()
                .copy(
                    audioScoring = BenchmarkAwareStreamScoringConfig.default().audioScoring.copy(
                        supportMultipliers = BenchmarkAwareStreamScoringConfig.default().audioScoring.supportMultipliers +
                            (ShadowAudioSupportTier.DECODED_MULTICHANNEL_PCM to 0.95)
                    )
                )
                .toJson()
        )
        val tunedScorer = BenchmarkAwareStreamScorer(tunedConfig)
        val benchmark = benchmarkResult(
            provider = DebridBenchmarkProvider.REAL_DEBRID,
            device = deviceSnapshot(
                truehdSupported = true,
                truehdPassthrough = false,
                eac3Supported = true,
                eac3Passthrough = true,
                ac3Supported = true,
                ac3Passthrough = true,
                dtsSupported = false,
                dtsPassthrough = false,
                dtshdSupported = false,
                dtshdPassthrough = false
            )
        )

        val event = tunedScorer.score(
            request = request(runtimeMinutes = 120),
            streams = listOf(
                streamCard(
                    streamKey = "truehd_pcm",
                    providerId = "RD",
                    audioTags = listOf("Atmos", "TrueHD")
                ),
                streamCard(
                    streamKey = "ddp_atmos",
                    providerId = "RD",
                    audioTags = listOf("Atmos", "DD+")
                )
            ),
            benchmarkSessions = mapOf(DebridBenchmarkProvider.REAL_DEBRID to benchmark)
        )

        assertEquals("truehd_pcm", event.selected?.streamKey)
    }

    @Test
    fun `selected dv winner retains best non dv fallback from the same ranked list`() {
        val event = scorer.score(
            request = request(runtimeMinutes = 120),
            streams = listOf(
                streamCard(
                    streamKey = "dv_primary",
                    providerId = "RD",
                    resolution = "2160p",
                    quality = "BluRay Remux",
                    encode = "HEVC",
                    sizeBytes = gib(42.0),
                    durationMs = 120L * 60_000L,
                    visualTags = listOf("DV"),
                    audioTags = listOf("Atmos", "TrueHD")
                ),
                streamCard(
                    streamKey = "hdr10_fallback",
                    providerId = "RD",
                    resolution = "2160p",
                    quality = "BluRay Remux",
                    encode = "HEVC",
                    sizeBytes = gib(40.0),
                    durationMs = 120L * 60_000L,
                    visualTags = listOf("HDR10"),
                    audioTags = listOf("TrueHD")
                ),
                streamCard(
                    streamKey = "sdr_lower",
                    providerId = "RD",
                    resolution = "1080p",
                    quality = "BluRay",
                    encode = "H264",
                    sizeBytes = gib(20.0),
                    durationMs = 120L * 60_000L,
                    visualTags = emptyList(),
                    audioTags = listOf("DD+")
                )
            ),
            benchmarkSessions = mapOf(
                DebridBenchmarkProvider.REAL_DEBRID to benchmarkResult(
                    provider = DebridBenchmarkProvider.REAL_DEBRID,
                    optimizedP10Mbps = 200.0
                )
            )
        )

        assertEquals("dv_primary", event.selected?.streamKey)
        assertEquals("hdr10_fallback", event.selectedNonDolbyVisionFallback?.streamKey)
        assertTrue(event.selectedNonDolbyVisionFallback?.hdrTags?.contains("DV") == false)
    }

    @Test
    fun `non dv winner does not expose a redundant non dv fallback`() {
        val event = scorer.score(
            request = request(runtimeMinutes = 120),
            streams = listOf(
                streamCard(
                    streamKey = "hdr10_primary",
                    providerId = "RD",
                    quality = "BluRay Remux",
                    sizeBytes = gib(55.0),
                    visualTags = listOf("HDR10"),
                    audioTags = listOf("Atmos", "TrueHD")
                ),
                streamCard(
                    streamKey = "dv_secondary",
                    providerId = "RD",
                    quality = "WEB-DL",
                    sizeBytes = gib(20.0),
                    visualTags = listOf("DV"),
                    audioTags = listOf("DD+")
                )
            ),
            benchmarkSessions = mapOf(
                DebridBenchmarkProvider.REAL_DEBRID to benchmarkResult(
                    provider = DebridBenchmarkProvider.REAL_DEBRID,
                    optimizedP10Mbps = 200.0
                )
            )
        )

        assertEquals("hdr10_primary", event.selected?.streamKey)
        assertNull(event.selectedNonDolbyVisionFallback)
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
        wrappedProviderId: String? = providerId,
        filename: String = "$streamKey.mkv",
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
                filename = filename
            ),
            addonName = "Addon",
            addonLogo = null,
            wrappedProviderId = wrappedProviderId,
            wrappedOriginalStreamKey = streamKey
        )
        val parsed = ParsedStreamInfo(
            stream = stream,
            title = "Example",
            filename = filename,
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
            durationMs = durationMs.takeIf { it > 0L },
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
        optimizedSeekP95Ms: Long = 240L,
        device: DeviceCapabilitySnapshot = deviceSnapshot()
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
            device = device,
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

    private fun deviceSnapshot(
        truehdSupported: Boolean = true,
        truehdPassthrough: Boolean = true,
        eac3Supported: Boolean = true,
        eac3Passthrough: Boolean = true,
        ac3Supported: Boolean = true,
        ac3Passthrough: Boolean = true,
        dtsSupported: Boolean = true,
        dtsPassthrough: Boolean = true,
        dtshdSupported: Boolean = true,
        dtshdPassthrough: Boolean = true
    ): DeviceCapabilitySnapshot {
        return DeviceCapabilitySnapshot(
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
                ac3 = AudioEncodingSupport(ac3Supported, ac3Passthrough),
                eac3 = AudioEncodingSupport(eac3Supported, eac3Passthrough),
                truehd = AudioEncodingSupport(truehdSupported, truehdPassthrough),
                dts = AudioEncodingSupport(dtsSupported, dtsPassthrough),
                dtshd = AudioEncodingSupport(dtshdSupported, dtshdPassthrough)
            ),
            capturedAtMs = 40L
        )
    }
}
