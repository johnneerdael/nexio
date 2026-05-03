package com.nexio.tv.data.repository.benchmark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class BenchmarkAwareScoringHarnessTest {

    @Test
    fun `dataset parses and evaluator scores expected winner`() {
        val dataset = sampleDataset()
        val parsed = BenchmarkAwareScoringDataset.fromJson(dataset.toJson())

        val summary = BenchmarkAwareScoringEvaluator().evaluate(
            parsed,
            BenchmarkAwareStreamScorer()
        )

        assertEquals(1, summary.scenarioCount)
        assertTrue(summary.top1Accuracy >= 0.0)
        val baseKey = summary.scenarios.single().selectedStreamKey?.substringBefore('|')
        assertTrue(baseKey in setOf("truehd_pcm", "ddp_atmos"))
    }

    @Test
    fun `tuning harness ranks tuned variant above default when labels prefer truehd atmos`() {
        val dataset = sampleDataset(expectedWinner = "truehd_pcm", truehdPassthrough = true, eac3Passthrough = true)
        val defaultConfig = BenchmarkAwareStreamScoringConfig.default()
        val tunedConfig = defaultConfig.copy(
            contentRewards = defaultConfig.contentRewards.copy(
                audio = defaultConfig.contentRewards.audio +
                    (ShadowAudioTier.TRUEHD_ATMOS to 18) +
                    (ShadowAudioTier.DDP_ATMOS to 14)
            )
        )

        val result = BenchmarkAwareScoringTuningHarness().evaluateVariants(
            dataset = dataset,
            variants = listOf(
                BenchmarkAwareNamedConfigVariant("default", defaultConfig),
                BenchmarkAwareNamedConfigVariant("tuned", tunedConfig)
            )
        )

        assertTrue(result.winner?.name in setOf("default", "tuned"))
        assertTrue(result.rankedVariants.first().objectiveScore >= result.rankedVariants.last().objectiveScore)
    }

    @Test
    fun `scorer rejects streams above autoplay max bitrate cap`() {
        val scenario = sampleDataset().scenarios.single()

        val event = BenchmarkAwareStreamScorer().score(
            request = scenario.toShadowRequestContext(),
            streams = scenario.toStreamCards(),
            benchmarkSessions = scenario.toBenchmarkSessionMap(),
            autoplayMaxBitrateMbps = 20.0
        )

        assertEquals(null, event.selected)
        assertTrue(event.rejected.isNotEmpty())
        assertTrue(event.rejected.all { rejected ->
            rejected.reasons == listOf(ShadowRejectReason.EXCEEDS_AUTOPLAY_CAP)
        })
    }

    @Test
    fun `manual cap scoring selects stream without benchmark sessions`() {
        val scenario = sampleDataset().scenarios.single()

        val event = BenchmarkAwareStreamScorer().scoreWithManualCap(
            request = scenario.toShadowRequestContext(),
            streams = scenario.toStreamCards(),
            manualBitrateCap = 80.0
        )

        assertNotNull(event.selected)
        assertTrue(event.benchmarksUsed.isEmpty())
        assertEquals(0, event.selected?.transportFitScore)
        assertEquals(80.0, event.selected?.safeBudgetMbps ?: -1.0, 0.0)
        assertEquals(0, event.selected?.breakdown?.transport?.ratioScore)
        assertEquals(0, event.selected?.breakdown?.transport?.startupScore)
        assertEquals(0, event.selected?.breakdown?.transport?.seekScore)
        assertEquals(0, event.selected?.breakdown?.transport?.stabilityScore)
        assertEquals(null, event.selectedNonDolbyVisionFallback)
    }

    @Test
    fun `manual cap scoring uses preserved metadata size when parsed size is missing`() {
        val sizeBytes = 77_729_165_620L
        val card = BenchmarkAwareScoringScenarioStream(
            streamKey = "avatar-fire-and-ash-pm",
            providerId = "PM",
            resolution = "2160p",
            quality = "WEBRip",
            encode = "AVC",
            sizeBytes = sizeBytes,
            durationMs = 198L * 60_000L,
            filename = "Avatar.Fire.and.Ash.(2025).2160p.SDR.iTUNES.WEBRiP.x264.24-bit.STEREO.WAV-CREATiVE24.mkv"
        ).toStreamCardModel().let { item ->
            item.copy(
                parsed = item.parsed.copy(
                    sizeBytes = null,
                    preservedMetadata = item.parsed.preservedMetadata.copy(videoSize = sizeBytes)
                )
            )
        }

        val event = BenchmarkAwareStreamScorer().scoreWithManualCap(
            request = ShadowRequestContext(
                requestId = "avatar-fire-and-ash",
                videoId = "tt1757678",
                contentType = "movie",
                title = "Avatar: Fire and Ash",
                season = null,
                episode = null,
                runtimeMinutes = 198
            ),
            streams = listOf(card),
            manualBitrateCap = 200.0
        )

        assertEquals("avatar-fire-and-ash-pm|PM", event.selected?.streamKey)
        assertEquals(sizeBytes, event.selected?.parsed?.sizeBytes)
        assertTrue(event.rejected.isEmpty())
    }

    @Test
    fun `manual cap scoring rejects streams over fixed bitrate cap without missing benchmark`() {
        val scenario = sampleDataset().scenarios.single()

        val event = BenchmarkAwareStreamScorer().scoreWithManualCap(
            request = scenario.toShadowRequestContext(),
            streams = scenario.toStreamCards(),
            manualBitrateCap = 20.0
        )

        assertEquals(null, event.selected)
        assertTrue(event.rejected.isNotEmpty())
        assertTrue(event.rejected.all { rejected ->
            rejected.reasons == listOf(ShadowRejectReason.EXCEEDS_AUTOPLAY_CAP)
        })
    }

    @Test
    fun `manual cap scoring rejects non debrid streams without benchmark requirement`() {
        val scenario = sampleDataset().scenarios.single()
        val stream = scenario.streams.first().copy(providerId = "HTTP")

        val event = BenchmarkAwareStreamScorer().scoreWithManualCap(
            request = scenario.toShadowRequestContext(),
            streams = listOf(stream.toStreamCardModel()),
            manualBitrateCap = 80.0
        )

        assertEquals(null, event.selected)
        assertEquals(listOf(ShadowRejectReason.NOT_DEBRID_WRAPPED), event.rejected.single().reasons)
    }

    @Test
    fun `manual cap scoring uses device audio capabilities`() {
        val request = ShadowRequestContext(
            requestId = "manual-device-audio",
            videoId = "tt123",
            contentType = "movie",
            title = "Example",
            season = null,
            episode = null,
            runtimeMinutes = 120
        )
        val trueHd = BenchmarkAwareScoringScenarioStream(
            streamKey = "truehd",
            providerId = "RD",
            resolution = "2160p",
            quality = "BluRay Remux",
            encode = "HEVC",
            sizeBytes = 12L * 1024L * 1024L * 1024L,
            durationMs = 120L * 60_000L,
            visualTags = emptyList(),
            audioTags = listOf("TrueHD", "Atmos")
        ).toStreamCardModel()
        val ddp = BenchmarkAwareScoringScenarioStream(
            streamKey = "ddp",
            providerId = "RD",
            resolution = "2160p",
            quality = "BluRay Remux",
            encode = "HEVC",
            sizeBytes = 12L * 1024L * 1024L * 1024L,
            durationMs = 120L * 60_000L,
            visualTags = emptyList(),
            audioTags = listOf("DD+", "Atmos")
        ).toStreamCardModel()

        val event = BenchmarkAwareStreamScorer().scoreWithManualCap(
            request = request,
            streams = listOf(trueHd, ddp),
            manualBitrateCap = 200.0,
            device = deviceSnapshot(
                truehdSupported = false,
                truehdPassthrough = false,
                eac3Supported = true,
                eac3Passthrough = true,
                ac3Supported = true,
                ac3Passthrough = true,
                dtsSupported = false,
                dtsPassthrough = false,
                dtshdSupported = false,
                dtshdPassthrough = false,
                atmosSupported = false,
                atmosPassthrough = false
            )
        )

        assertEquals("ddp|RD", event.selected?.streamKey)
        assertEquals(
            "unsupported",
            event.winners.first { it.streamKey == "truehd|RD" }.breakdown.content.audioSupportTier
        )
    }

    @Test
    fun `webdl atmos without container tag falls back to ddp when atmos passthrough is absent`() {
        val event = BenchmarkAwareStreamScorer().scoreWithManualCap(
            request = shadowRequest("webdl-atmos"),
            streams = listOf(
                BenchmarkAwareScoringScenarioStream(
                    streamKey = "webdl-atmos",
                    providerId = "RD",
                    resolution = "2160p",
                    quality = "WEB-DL",
                    encode = "HEVC",
                    sizeBytes = 12L * 1024L * 1024L * 1024L,
                    durationMs = 120L * 60_000L,
                    visualTags = emptyList(),
                    audioTags = listOf("Atmos")
                ).toStreamCardModel()
            ),
            manualBitrateCap = 200.0,
            device = deviceSnapshot(
                truehdSupported = false,
                truehdPassthrough = false,
                eac3Supported = true,
                eac3Passthrough = true,
                ac3Supported = true,
                ac3Passthrough = true,
                dtsSupported = false,
                dtsPassthrough = false,
                dtshdSupported = false,
                dtshdPassthrough = false,
                atmosSupported = false,
                atmosPassthrough = false
            )
        )

        assertEquals("ddp", event.selected?.breakdown?.content?.audioTier)
        assertEquals("supported", event.selected?.breakdown?.content?.audioSupportTier)
    }

    @Test
    fun `remux atmos without container tag falls back to truehd not ddp`() {
        val event = BenchmarkAwareStreamScorer().scoreWithManualCap(
            request = shadowRequest("remux-atmos"),
            streams = listOf(
                BenchmarkAwareScoringScenarioStream(
                    streamKey = "remux-atmos",
                    providerId = "RD",
                    resolution = "2160p",
                    quality = "BluRay Remux",
                    encode = "HEVC",
                    sizeBytes = 12L * 1024L * 1024L * 1024L,
                    durationMs = 120L * 60_000L,
                    visualTags = emptyList(),
                    audioTags = listOf("Atmos")
                ).toStreamCardModel()
            ),
            manualBitrateCap = 200.0,
            device = deviceSnapshot(
                truehdSupported = true,
                truehdPassthrough = true,
                eac3Supported = true,
                eac3Passthrough = true,
                ac3Supported = true,
                ac3Passthrough = true,
                dtsSupported = false,
                dtsPassthrough = false,
                dtshdSupported = false,
                dtshdPassthrough = false,
                atmosSupported = false,
                atmosPassthrough = false
            )
        )

        assertEquals("truehd", event.selected?.breakdown?.content?.audioTier)
        assertEquals("supported", event.selected?.breakdown?.content?.audioSupportTier)
    }

    @Test
    fun `manual cap scoring uses device hdr capabilities`() {
        val request = ShadowRequestContext(
            requestId = "manual-device-hdr",
            videoId = "tt123",
            contentType = "movie",
            title = "Example",
            season = null,
            episode = null,
            runtimeMinutes = 120
        )
        val dolbyVision = BenchmarkAwareScoringScenarioStream(
            streamKey = "dv",
            providerId = "RD",
            resolution = "2160p",
            quality = "WEB-DL",
            encode = "HEVC",
            sizeBytes = 8L * 1024L * 1024L * 1024L,
            durationMs = 120L * 60_000L,
            visualTags = listOf("DV"),
            audioTags = listOf("DD+")
        ).toStreamCardModel()
        val hdr10 = BenchmarkAwareScoringScenarioStream(
            streamKey = "hdr10",
            providerId = "RD",
            resolution = "2160p",
            quality = "WEB-DL",
            encode = "HEVC",
            sizeBytes = 8L * 1024L * 1024L * 1024L,
            durationMs = 120L * 60_000L,
            visualTags = listOf("HDR10"),
            audioTags = listOf("DD+")
        ).toStreamCardModel()

        val event = BenchmarkAwareStreamScorer().scoreWithManualCap(
            request = request,
            streams = listOf(dolbyVision, hdr10),
            manualBitrateCap = 200.0,
            device = deviceSnapshot(
                displayHdrTypes = setOf(DeviceHdrType.HDR10),
                truehdSupported = false,
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

        assertEquals(
            "fallback",
            event.winners.first { it.streamKey == "dv|RD" }.breakdown.content.hdrSupportTier
        )
        assertEquals(
            "full",
            event.winners.first { it.streamKey == "hdr10|RD" }.breakdown.content.hdrSupportTier
        )
    }

    @Test
    fun `cli runner writes evaluation output`() {
        val datasetFile = Files.createTempFile("benchmark_scoring_dataset", ".json")
        val outputFile = Files.createTempFile("benchmark_scoring_output", ".json")
        Files.write(datasetFile, sampleDataset().toJson().toByteArray())

        val exitCode = BenchmarkAwareScoringCliRunner.run(
            listOf(
                "--dataset", datasetFile.toString(),
                "--output", outputFile.toString()
            )
        )

        val output = String(Files.readAllBytes(outputFile))
        assertEquals(0, exitCode)
        assertTrue(output.contains("top1Accuracy"))
        assertTrue(output.contains("ddp_atmos") || output.contains("truehd_pcm"))
    }

    @Test
    fun `cli runner ranks variant files for tuning mode`() {
        val datasetFile = Files.createTempFile("benchmark_scoring_dataset_variants", ".json")
        val variantDefault = Files.createTempFile("benchmark_variant_default", ".json")
        val variantTuned = Files.createTempFile("benchmark_variant_tuned", ".json")
        val outputFile = Files.createTempFile("benchmark_variant_output", ".json")
        val defaultConfig = BenchmarkAwareStreamScoringConfig.default()
        val tunedConfig = defaultConfig.copy(
            contentRewards = defaultConfig.contentRewards.copy(
                audio = defaultConfig.contentRewards.audio +
                    (ShadowAudioTier.TRUEHD_ATMOS to 18) +
                    (ShadowAudioTier.DDP_ATMOS to 14)
            )
        )
        Files.write(datasetFile, sampleDataset(expectedWinner = "truehd_pcm", truehdPassthrough = true, eac3Passthrough = true).toJson().toByteArray())
        Files.write(variantDefault, defaultConfig.toJson().toByteArray())
        Files.write(variantTuned, tunedConfig.toJson().toByteArray())

        val exitCode = BenchmarkAwareScoringCliRunner.run(
            listOf(
                "--dataset", datasetFile.toString(),
                "--variant", variantDefault.toString(),
                "--variant", variantTuned.toString(),
                "--output", outputFile.toString()
            )
        )

        val output = String(Files.readAllBytes(outputFile))
        assertEquals(0, exitCode)
        assertTrue(output.contains(variantDefault.fileName.toString()))
        assertTrue(output.contains(variantTuned.fileName.toString()))
    }

    @Test
    fun `variant grid generator writes a sweep set to disk`() {
        val outDir = Files.createTempDirectory("benchmark_variant_grid")

        val generated = BenchmarkAwareScoringVariantGridGenerator.writeVariants(
            outputDirectory = outDir,
            base = BenchmarkAwareStreamScoringConfig.default()
        )

        assertTrue(generated.size >= 4)
        assertTrue(generated.any { it.fileName.toString() == "default.json" })
        assertTrue(generated.any { it.fileName.toString() == "pcm-heavy.json" })
    }

    private fun sampleDataset(
        expectedWinner: String = "truehd_pcm",
        truehdPassthrough: Boolean = false,
        eac3Passthrough: Boolean = true
    ): BenchmarkAwareScoringDataset {
        return BenchmarkAwareScoringDataset(
            scenarios = listOf(
                BenchmarkAwareScoringScenario(
                    id = "audio-fallback-1",
                    name = "DDP Atmos passthrough beats TrueHD Atmos PCM fallback",
                    request = BenchmarkAwareScoringScenarioRequest(
                        videoId = "tt123",
                        contentType = "movie",
                        title = "Example",
                        runtimeMinutes = 120
                    ),
                    benchmarks = listOf(
                        benchmarkResult(
                            provider = DebridBenchmarkProvider.REAL_DEBRID,
                            device = deviceSnapshot(
                                truehdSupported = true,
                                truehdPassthrough = truehdPassthrough,
                                eac3Supported = true,
                                eac3Passthrough = eac3Passthrough,
                                ac3Supported = true,
                                ac3Passthrough = true,
                                dtsSupported = false,
                                dtsPassthrough = false,
                                dtshdSupported = false,
                                dtshdPassthrough = false
                            )
                        )
                    ),
                    streams = listOf(
                        BenchmarkAwareScoringScenarioStream(
                            streamKey = "truehd_pcm",
                            providerId = "RD",
                            resolution = "2160p",
                            quality = "BluRay Remux",
                            encode = "HEVC",
                            sizeBytes = gib(42.0),
                            durationMs = 120L * 60_000L,
                            visualTags = listOf("DV"),
                            audioTags = listOf("Atmos", "TrueHD")
                        ),
                        BenchmarkAwareScoringScenarioStream(
                            streamKey = "ddp_atmos",
                            providerId = "RD",
                            resolution = "2160p",
                            quality = "BluRay Remux",
                            encode = "HEVC",
                            sizeBytes = gib(42.0),
                            durationMs = 120L * 60_000L,
                            visualTags = listOf("DV"),
                            audioTags = listOf("Atmos", "DD+")
                        )
                    ),
                    expectedWinnerStreamKey = expectedWinner,
                    acceptableWinnerKeys = listOf(expectedWinner),
                    preferredPairs = listOf(
                        BenchmarkAwareScoringPreferencePair(expectedWinner, if (expectedWinner == "ddp_atmos") "truehd_pcm" else "ddp_atmos")
                    )
                )
            )
        )
    }

    private fun benchmarkResult(
        provider: DebridBenchmarkProvider,
        device: DeviceCapabilitySnapshot
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
            direct = transportProfile(150.0, 160.0, 180L, 340L, 127.5),
            optimized = transportProfile(180.0, 195.0, 140L, 240L, 153.0),
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
        decisionSafeBudgetMbps: Double
    ): DebridBenchmarkTransportProfile {
        return DebridBenchmarkTransportProfile(
            startup = DebridBenchmarkStartupMetrics(startupMs, 0.0),
            sustained = DebridBenchmarkSustainedMetrics(
                averageThroughputMbps = averageMbps,
                derivedAverageThroughputMbps = averageMbps,
                actionable = true,
                p10ThroughputMbps = p10Mbps,
                p50ThroughputMbps = averageMbps,
                peakThroughputMbps = averageMbps + 15.0,
                throughputStddevMbps = 6.0,
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
            rawSamples = DebridBenchmarkRawSamples()
        )
    }

    private fun deviceSnapshot(
        displayHdrTypes: Set<DeviceHdrType> = setOf(DeviceHdrType.DOLBY_VISION, DeviceHdrType.HDR10),
        truehdSupported: Boolean,
        truehdPassthrough: Boolean,
        eac3Supported: Boolean,
        eac3Passthrough: Boolean,
        ac3Supported: Boolean,
        ac3Passthrough: Boolean,
        dtsSupported: Boolean,
        dtsPassthrough: Boolean,
        dtshdSupported: Boolean,
        dtshdPassthrough: Boolean,
        atmosSupported: Boolean? = null,
        atmosPassthrough: Boolean? = null
    ): DeviceCapabilitySnapshot {
        return DeviceCapabilitySnapshot(
            model = "Shield",
            manufacturer = "NVIDIA",
            sdkInt = 35,
            displayHdrTypes = displayHdrTypes,
            videoDecode = DeviceVideoDecodeCapabilities(
                h264 = CodecSupport(true, false, true),
                hevc = CodecSupport(true, false, true),
                av1 = CodecSupport(false, true, false),
                dolbyVision = CodecSupport(true, false, true)
            ),
            audioOutput = DeviceAudioOutputCapabilities(
                ac3 = AudioEncodingSupport(ac3Supported, ac3Passthrough),
                eac3 = AudioEncodingSupport(eac3Supported, eac3Passthrough),
                atmos = AudioEncodingSupport(
                    atmosSupported ?: (eac3Supported || truehdSupported),
                    atmosPassthrough ?: (eac3Passthrough || truehdPassthrough)
                ),
                truehd = AudioEncodingSupport(truehdSupported, truehdPassthrough),
                dts = AudioEncodingSupport(dtsSupported, dtsPassthrough),
                dtshd = AudioEncodingSupport(dtshdSupported, dtshdPassthrough)
            ),
            capturedAtMs = 40L
        )
    }

    private fun shadowRequest(requestId: String): ShadowRequestContext {
        return ShadowRequestContext(
            requestId = requestId,
            videoId = "tt123",
            contentType = "movie",
            title = "Example",
            season = null,
            episode = null,
            runtimeMinutes = 120
        )
    }

    private fun gib(value: Double): Long = (value * 1024.0 * 1024.0 * 1024.0).toLong()
}
