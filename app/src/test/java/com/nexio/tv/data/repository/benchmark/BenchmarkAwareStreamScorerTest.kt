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
import kotlin.math.roundToLong

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
    fun `shadow parsed facts retain folder name when available`() {
        val event = scorer.score(
            request = request(runtimeMinutes = 120),
            streams = listOf(
                streamCard(
                    streamKey = "rd_foldered",
                    providerId = "RD",
                    filename = "00010.m2ts",
                    folderName = "The.Movie.2026.2160p.UHD.BluRay.REMUX"
                )
            ),
            benchmarkSessions = mapOf(
                DebridBenchmarkProvider.REAL_DEBRID to benchmarkResult(
                    provider = DebridBenchmarkProvider.REAL_DEBRID
                )
            )
        )

        assertEquals("00010.m2ts", event.selected?.parsed?.filename)
        assertEquals("The.Movie.2026.2160p.UHD.BluRay.REMUX", event.selected?.parsed?.folderName)
    }

    @Test
    fun `torbox and easydebrid service ids map to benchmark providers`() {
        val event = scorer.score(
            request = request(runtimeMinutes = 120),
            streams = listOf(
                streamCard(
                    streamKey = "tb_stream",
                    providerId = "TB",
                    wrappedProviderId = null,
                    filename = "TorBox.Release.mkv"
                ),
                streamCard(
                    streamKey = "ed_stream",
                    providerId = "ED",
                    wrappedProviderId = null,
                    filename = "EasyDebrid.Release.mkv"
                )
            ),
            benchmarkSessions = mapOf(
                DebridBenchmarkProvider.TORBOX to benchmarkResult(
                    provider = DebridBenchmarkProvider.TORBOX
                ),
                DebridBenchmarkProvider.EASY_DEBRID to benchmarkResult(
                    provider = DebridBenchmarkProvider.EASY_DEBRID
                )
            )
        )

        assertTrue(event.winners.any { it.provider == DebridBenchmarkProvider.TORBOX })
        assertTrue(event.winners.any { it.provider == DebridBenchmarkProvider.EASY_DEBRID })
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
    fun `parser runtime remains preferred when metadata runtime also exists`() {
        val event = scorer.score(
            request = request(runtimeMinutes = 46),
            streams = listOf(
                streamCard(
                    streamKey = "rd_parser_runtime",
                    providerId = "RD",
                    sizeBytes = gib(8.0),
                    durationMs = 50L * 60_000L
                )
            ),
            benchmarkSessions = mapOf(
                DebridBenchmarkProvider.REAL_DEBRID to benchmarkResult(
                    provider = DebridBenchmarkProvider.REAL_DEBRID
                )
            )
        )

        assertEquals(50L * 60_000L, event.selected?.parsed?.durationMs)
        assertEquals("parser", event.selected?.parsed?.runtimeSource)
    }

    @Test
    fun `missing runtime still allows final scoring on non bitrate metrics`() {
        val event = scorer.score(
            request = request(runtimeMinutes = null),
            streams = listOf(
                streamCard(
                    streamKey = "rd_no_runtime",
                    providerId = "RD",
                    resolution = "2160p",
                    quality = "WEB-DL",
                    encode = "HEVC",
                    sizeBytes = gib(8.0),
                    durationMs = null,
                    visualTags = listOf("HDR"),
                    audioTags = listOf("DD+")
                )
            ),
            benchmarkSessions = mapOf(
                DebridBenchmarkProvider.REAL_DEBRID to benchmarkResult(
                    provider = DebridBenchmarkProvider.REAL_DEBRID
                )
            )
        )

        assertEquals("rd_no_runtime", event.selected?.streamKey)
        assertTrue(event.rejected.none { it.streamKey == "rd_no_runtime" })
        assertEquals(0.0, event.selected?.breakdown?.averageBitrateMbps)
        assertEquals(0, event.selected?.breakdown?.content?.bitrateQualityPoints)
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
        assertTrue(event.winners.none { it.streamKey == "tiny_4k" })
    }

    @Test
    fun `rd remux wins when premiumize budget rejects the stream`() {
        val rdBenchmark = benchmarkResult(
            provider = DebridBenchmarkProvider.REAL_DEBRID,
            optimizedP10Mbps = 220.0
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
    fun `active transport mode filters out direct path when parallel playback is enabled`() {
        val event = scorer.score(
            request = request(runtimeMinutes = 120),
            streams = listOf(streamCard(streamKey = "rd_stream", providerId = "RD")),
            benchmarkSessions = mapOf(
                DebridBenchmarkProvider.REAL_DEBRID to benchmarkResult(
                    provider = DebridBenchmarkProvider.REAL_DEBRID,
                    directP10Mbps = 240.0,
                    optimizedP10Mbps = 180.0,
                    directSeekP95Ms = 120L,
                    optimizedSeekP95Ms = 220L
                )
            ),
            activeTransportMode = DebridBenchmarkTransportMode.OPTIMIZED
        )

        assertEquals(DebridBenchmarkTransportMode.OPTIMIZED, event.selected?.transport)
    }

    @Test
    fun `active transport mode direct still resolves to optimized transport`() {
        val event = scorer.score(
            request = request(runtimeMinutes = 120),
            streams = listOf(streamCard(streamKey = "rd_stream", providerId = "RD")),
            benchmarkSessions = mapOf(
                DebridBenchmarkProvider.REAL_DEBRID to benchmarkResult(
                    provider = DebridBenchmarkProvider.REAL_DEBRID,
                    directP10Mbps = 140.0,
                    optimizedP10Mbps = 220.0
                )
            ),
            activeTransportMode = DebridBenchmarkTransportMode.DIRECT
        )

        assertEquals(DebridBenchmarkTransportMode.OPTIMIZED, event.selected?.transport)
    }

    @Test
    fun `scorer uses optimized derived decision metrics as autoplay truth over raw sustained p10`() {
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

        assertNull(event.selected)
        assertEquals(
            listOf(
                ShadowRejectReason.INSUFFICIENT_TRANSPORT_BUDGET,
                ShadowRejectReason.NO_ELIGIBLE_TRANSPORT
            ),
            event.rejected.single { it.streamKey == "rd_stream" }.reasons
        )
    }

    @Test
    fun `atmos tagged streams can score equivalently when ddp passthrough is available`() {
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

        val truehd = event.winners.single { it.streamKey == "truehd_pcm" }
        val ddp = event.winners.single { it.streamKey == "ddp_atmos" }
        assertEquals(truehd.contentQualityScore, ddp.contentQualityScore)
        assertEquals(truehd.finalScore, ddp.finalScore)
    }

    @Test
    fun `supported ac3 beats unsupported truehd atmos`() {
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

        assertEquals("ac3_passthrough", event.selected?.streamKey)
    }

    @Test
    fun `all unsupported audio tags use the highest numeric score`() {
        val benchmark = benchmarkResult(
            provider = DebridBenchmarkProvider.REAL_DEBRID,
            device = deviceSnapshot(
                truehdSupported = false,
                truehdPassthrough = false,
                eac3Supported = false,
                eac3Passthrough = false,
                ac3Supported = false,
                ac3Passthrough = false,
                dtsSupported = false,
                dtsPassthrough = false,
                dtshdSupported = false,
                dtshdPassthrough = false,
                atmosSupported = false,
                atmosPassthrough = false
            )
        )
        val event = scorer.score(
            request = request(runtimeMinutes = 120),
            streams = listOf(
                streamCard(
                    streamKey = "unsupported_combo",
                    providerId = "RD",
                    audioTags = listOf("DTS:X", "Atmos", "AC3")
                )
            ),
            benchmarkSessions = mapOf(DebridBenchmarkProvider.REAL_DEBRID to benchmark)
        )

        val stream = event.selected ?: event.winners.single()
        assertEquals(-7, stream.breakdown.content.audioPoints)
    }

    @Test
    fun `json tuned config can flip audio preference without changing code`() {
        val tunedConfig = BenchmarkAwareStreamScoringConfig.fromJson(
            BenchmarkAwareStreamScoringConfig.default()
                .copy(
                    contentRewards = BenchmarkAwareStreamScoringConfig.default().contentRewards.copy(
                        audio = BenchmarkAwareStreamScoringConfig.default().contentRewards.audio +
                            (ShadowAudioTier.TRUEHD_ATMOS to 18) +
                            (ShadowAudioTier.DDP_ATMOS to 14)
                    )
                )
                .toJson()
        )
        val tunedScorer = BenchmarkAwareStreamScorer(tunedConfig)
        val benchmark = benchmarkResult(
            provider = DebridBenchmarkProvider.REAL_DEBRID,
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
    fun `selected dv winner expands to next bitrate group when current pool has no non dv fallback`() {
        val event = scorer.score(
            request = request(runtimeMinutes = 120),
            streams = listOf(
                streamCard(
                    streamKey = "dv_huge",
                    providerId = "RD",
                    resolution = "2160p",
                    quality = "WEB-DL",
                    encode = "HEVC",
                    sizeBytes = gib(42.0),
                    durationMs = 120L * 60_000L,
                    visualTags = listOf("DV"),
                    audioTags = listOf("Atmos", "DD+")
                ),
                streamCard(
                    streamKey = "dv_large",
                    providerId = "RD",
                    resolution = "2160p",
                    quality = "WEB-DL",
                    encode = "HEVC",
                    sizeBytes = gib(38.0),
                    durationMs = 120L * 60_000L,
                    visualTags = listOf("DV"),
                    audioTags = listOf("Atmos", "DD+")
                ),
                streamCard(
                    streamKey = "dv_mid",
                    providerId = "RD",
                    resolution = "2160p",
                    quality = "WEB-DL",
                    encode = "HEVC",
                    sizeBytes = gib(34.0),
                    durationMs = 120L * 60_000L,
                    visualTags = listOf("DV"),
                    audioTags = listOf("DD+")
                ),
                streamCard(
                    streamKey = "hdr10_next_group",
                    providerId = "RD",
                    resolution = "2160p",
                    quality = "WEB-DL",
                    encode = "HEVC",
                    sizeBytes = gib(28.0),
                    durationMs = 120L * 60_000L,
                    visualTags = listOf("HDR10"),
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

        assertEquals("dv_huge", event.selected?.streamKey)
        assertEquals("hdr10_next_group", event.selectedNonDolbyVisionFallback?.streamKey)
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

    @Test
    fun `webdl dolby vision scores like hdr10 when display does not advertise dv`() {
        val benchmark = benchmarkResult(
            provider = DebridBenchmarkProvider.REAL_DEBRID,
            device = deviceSnapshot(
                displayHdrTypes = setOf(DeviceHdrType.HDR10, DeviceHdrType.HDR10_PLUS, DeviceHdrType.HLG)
            )
        )
        val event = scorer.score(
            request = request(runtimeMinutes = 120),
            streams = listOf(
                streamCard(
                    streamKey = "webdl_dv",
                    providerId = "RD",
                    quality = "WEB-DL",
                    sizeBytes = gib(20.0),
                    visualTags = listOf("DV"),
                    audioTags = listOf("DD+")
                ),
                streamCard(
                    streamKey = "hdr10_webdl",
                    providerId = "RD",
                    quality = "WEB-DL",
                    sizeBytes = gib(20.0),
                    visualTags = listOf("HDR10"),
                    audioTags = listOf("DD+")
                )
            ),
            benchmarkSessions = mapOf(DebridBenchmarkProvider.REAL_DEBRID to benchmark)
        )

        val webdlDv = event.winners.single { it.streamKey == "webdl_dv" }
        val hdr10 = event.winners.single { it.streamKey == "hdr10_webdl" }
        assertEquals(hdr10.finalScore, webdlDv.finalScore)
        assertEquals(hdr10.contentQualityScore, webdlDv.contentQualityScore)
    }

    @Test
    fun `non webdl dolby vision falls back to hdr10 equivalent when display lacks dv`() {
        val benchmark = benchmarkResult(
            provider = DebridBenchmarkProvider.REAL_DEBRID,
            device = deviceSnapshot(
                displayHdrTypes = setOf(DeviceHdrType.HDR10, DeviceHdrType.HDR10_PLUS, DeviceHdrType.HLG)
            )
        )
        val event = scorer.score(
            request = request(runtimeMinutes = 120),
            streams = listOf(
                streamCard(
                    streamKey = "remux_dv",
                    providerId = "RD",
                    quality = "BluRay Remux",
                    sizeBytes = gib(42.0),
                    visualTags = listOf("DV"),
                    audioTags = listOf("Atmos", "TrueHD")
                ),
                streamCard(
                    streamKey = "remux_hdr10",
                    providerId = "RD",
                    quality = "BluRay Remux",
                    sizeBytes = gib(42.0),
                    visualTags = listOf("HDR10"),
                    audioTags = listOf("Atmos", "TrueHD")
                )
            ),
            benchmarkSessions = mapOf(DebridBenchmarkProvider.REAL_DEBRID to benchmark)
        )

        val remuxDv = event.winners.single { it.streamKey == "remux_dv" }
        val remuxHdr10 = event.winners.single { it.streamKey == "remux_hdr10" }
        assertEquals(remuxHdr10.finalScore, remuxDv.finalScore)
        assertEquals(remuxHdr10.contentQualityScore, remuxDv.contentQualityScore)
    }

    @Test
    fun `unsupported dolby vision does not keep premium hdr synergy when hdr10 is unavailable`() {
        val benchmark = benchmarkResult(
            provider = DebridBenchmarkProvider.REAL_DEBRID,
            device = deviceSnapshot(displayHdrTypes = setOf(DeviceHdrType.HLG))
        )
        val event = scorer.score(
            request = request(runtimeMinutes = 120),
            streams = listOf(
                streamCard(
                    streamKey = "remux_dv",
                    providerId = "RD",
                    quality = "BluRay Remux",
                    sizeBytes = gib(42.0),
                    visualTags = listOf("DV"),
                    audioTags = listOf("Atmos", "TrueHD")
                ),
                streamCard(
                    streamKey = "remux_sdr",
                    providerId = "RD",
                    quality = "BluRay Remux",
                    sizeBytes = gib(42.0),
                    visualTags = emptyList(),
                    audioTags = listOf("Atmos", "TrueHD")
                )
            ),
            benchmarkSessions = mapOf(DebridBenchmarkProvider.REAL_DEBRID to benchmark)
        )

        val remuxDv = event.winners.single { it.streamKey == "remux_dv" }
        val remuxSdr = event.winners.single { it.streamKey == "remux_sdr" }
        assertEquals(0, remuxDv.breakdown.content.hdrPoints)
        assertEquals(remuxSdr.breakdown.content.synergyPoints, remuxDv.breakdown.content.synergyPoints)
        assertEquals(remuxSdr.contentQualityScore, remuxDv.contentQualityScore)
        assertEquals(remuxSdr.finalScore, remuxDv.finalScore)
    }

    @Test
    fun `unsupported hdr10 does not keep premium hdr synergy when hdr is unavailable`() {
        val benchmark = benchmarkResult(
            provider = DebridBenchmarkProvider.REAL_DEBRID,
            device = deviceSnapshot(displayHdrTypes = setOf(DeviceHdrType.HLG))
        )
        val event = scorer.score(
            request = request(runtimeMinutes = 120),
            streams = listOf(
                streamCard(
                    streamKey = "remux_hdr10",
                    providerId = "RD",
                    quality = "BluRay Remux",
                    sizeBytes = gib(42.0),
                    visualTags = listOf("HDR10"),
                    audioTags = listOf("Atmos", "TrueHD")
                ),
                streamCard(
                    streamKey = "remux_sdr",
                    providerId = "RD",
                    quality = "BluRay Remux",
                    sizeBytes = gib(42.0),
                    visualTags = emptyList(),
                    audioTags = listOf("Atmos", "TrueHD")
                )
            ),
            benchmarkSessions = mapOf(DebridBenchmarkProvider.REAL_DEBRID to benchmark)
        )

        val remuxHdr10 = event.winners.single { it.streamKey == "remux_hdr10" }
        val remuxSdr = event.winners.single { it.streamKey == "remux_sdr" }
        assertEquals(0, remuxHdr10.breakdown.content.hdrPoints)
        assertEquals(remuxSdr.breakdown.content.synergyPoints, remuxHdr10.breakdown.content.synergyPoints)
        assertEquals(remuxSdr.contentQualityScore, remuxHdr10.contentQualityScore)
        assertEquals(remuxSdr.finalScore, remuxHdr10.finalScore)
    }

    @Test
    fun `supported hlg ranks above sdr but below hdr10`() {
        val benchmark = benchmarkResult(
            provider = DebridBenchmarkProvider.REAL_DEBRID,
            device = deviceSnapshot(displayHdrTypes = setOf(DeviceHdrType.HLG, DeviceHdrType.HDR10))
        )
        val event = scorer.score(
            request = request(runtimeMinutes = 120),
            streams = listOf(
                streamCard(
                    streamKey = "remux_hlg",
                    providerId = "RD",
                    quality = "BluRay Remux",
                    sizeBytes = gib(42.0),
                    visualTags = listOf("HLG"),
                    audioTags = listOf("Atmos", "TrueHD")
                ),
                streamCard(
                    streamKey = "remux_hdr10",
                    providerId = "RD",
                    quality = "BluRay Remux",
                    sizeBytes = gib(42.0),
                    visualTags = listOf("HDR10"),
                    audioTags = listOf("Atmos", "TrueHD")
                ),
                streamCard(
                    streamKey = "remux_sdr",
                    providerId = "RD",
                    quality = "BluRay Remux",
                    sizeBytes = gib(42.0),
                    visualTags = emptyList(),
                    audioTags = listOf("Atmos", "TrueHD")
                )
            ),
            benchmarkSessions = mapOf(DebridBenchmarkProvider.REAL_DEBRID to benchmark)
        )

        val hlg = event.winners.single { it.streamKey == "remux_hlg" }
        val hdr10 = event.winners.single { it.streamKey == "remux_hdr10" }
        val sdr = event.winners.single { it.streamKey == "remux_sdr" }
        assertEquals("hlg", hlg.breakdown.content.hdrTier)
        assertEquals("full", hlg.breakdown.content.hdrSupportTier)
        assertTrue(hlg.breakdown.content.hdrPoints > sdr.breakdown.content.hdrPoints)
        assertTrue(hdr10.breakdown.content.hdrPoints > hlg.breakdown.content.hdrPoints)
        assertTrue(hlg.contentQualityScore > sdr.contentQualityScore)
        assertTrue(hdr10.contentQualityScore > hlg.contentQualityScore)
    }

    @Test
    fun `unsupported hlg scores like sdr`() {
        val benchmark = benchmarkResult(
            provider = DebridBenchmarkProvider.REAL_DEBRID,
            device = deviceSnapshot(displayHdrTypes = setOf(DeviceHdrType.HDR10))
        )
        val event = scorer.score(
            request = request(runtimeMinutes = 120),
            streams = listOf(
                streamCard(
                    streamKey = "remux_hlg",
                    providerId = "RD",
                    quality = "BluRay Remux",
                    sizeBytes = gib(42.0),
                    visualTags = listOf("HLG"),
                    audioTags = listOf("Atmos", "TrueHD")
                ),
                streamCard(
                    streamKey = "remux_sdr",
                    providerId = "RD",
                    quality = "BluRay Remux",
                    sizeBytes = gib(42.0),
                    visualTags = emptyList(),
                    audioTags = listOf("Atmos", "TrueHD")
                )
            ),
            benchmarkSessions = mapOf(DebridBenchmarkProvider.REAL_DEBRID to benchmark)
        )

        val hlg = event.winners.single { it.streamKey == "remux_hlg" }
        val sdr = event.winners.single { it.streamKey == "remux_sdr" }
        assertEquals("hlg", hlg.breakdown.content.hdrTier)
        assertEquals("unsupported", hlg.breakdown.content.hdrSupportTier)
        assertEquals(0, hlg.breakdown.content.hdrPoints)
        assertEquals(sdr.contentQualityScore, hlg.contentQualityScore)
        assertEquals(sdr.finalScore, hlg.finalScore)
    }

    @Test
    fun `dolby vision still scores above hdr10 when display advertises dv`() {
        val benchmark = benchmarkResult(
            provider = DebridBenchmarkProvider.REAL_DEBRID,
            device = deviceSnapshot(
                displayHdrTypes = setOf(DeviceHdrType.DOLBY_VISION, DeviceHdrType.HDR10)
            )
        )
        val event = scorer.score(
            request = request(runtimeMinutes = 120),
            streams = listOf(
                streamCard(
                    streamKey = "remux_dv",
                    providerId = "RD",
                    quality = "BluRay Remux",
                    sizeBytes = gib(42.0),
                    visualTags = listOf("DV"),
                    audioTags = listOf("Atmos", "TrueHD")
                ),
                streamCard(
                    streamKey = "remux_hdr10",
                    providerId = "RD",
                    quality = "BluRay Remux",
                    sizeBytes = gib(42.0),
                    visualTags = listOf("HDR10"),
                    audioTags = listOf("Atmos", "TrueHD")
                )
            ),
            benchmarkSessions = mapOf(DebridBenchmarkProvider.REAL_DEBRID to benchmark)
        )

        assertEquals("remux_dv", event.selected?.streamKey)
    }

    @Test
    fun `movie scoring caps transport reward so viable remux beats tiny webdl`() {
        val benchmark = benchmarkResult(
            provider = DebridBenchmarkProvider.REAL_DEBRID,
            optimizedP10Mbps = 600.0,
            directP10Mbps = 300.0
        )
        val event = scorer.score(
            request = request(runtimeMinutes = 179),
            streams = listOf(
                streamCard(
                    streamKey = "tiny_webdl",
                    providerId = "RD",
                    quality = "WEB-DL",
                    sizeBytes = gib(33.0),
                    visualTags = listOf("HDR10"),
                    audioTags = listOf("Atmos", "DD+")
                ),
                streamCard(
                    streamKey = "large_remux",
                    providerId = "RD",
                    quality = "BluRay Remux",
                    sizeBytes = gib(105.0),
                    visualTags = listOf("HDR10"),
                    audioTags = listOf("TrueHD")
                )
            ),
            benchmarkSessions = mapOf(DebridBenchmarkProvider.REAL_DEBRID to benchmark),
            activeTransportMode = DebridBenchmarkTransportMode.OPTIMIZED
        )

        assertEquals("large_remux", event.selected?.streamKey)
        assertTrue(event.winners.none { it.streamKey == "tiny_webdl" })
        assertTrue(event.winners.any { it.streamKey == "large_remux" })
    }

    @Test
    fun `movie scoring keeps only top bitrate pool in highest viable resolution tier`() {
        val benchmark = benchmarkResult(
            provider = DebridBenchmarkProvider.REAL_DEBRID,
            optimizedP10Mbps = 600.0
        )
        val event = scorer.score(
            request = request(runtimeMinutes = 179),
            streams = listOf(
                streamCard(streamKey = "2160_small_1", providerId = "RD", quality = "WEB-DL", sizeBytes = gib(20.0)),
                streamCard(streamKey = "2160_small_2", providerId = "RD", quality = "WEB-DL", sizeBytes = gib(25.0)),
                streamCard(streamKey = "2160_mid", providerId = "RD", quality = "BluRay", sizeBytes = gib(60.0)),
                streamCard(streamKey = "2160_large", providerId = "RD", quality = "BluRay Remux", sizeBytes = gib(100.0)),
                streamCard(streamKey = "2160_huge", providerId = "RD", quality = "BluRay Remux", sizeBytes = gib(150.0)),
                streamCard(streamKey = "1080_massive", providerId = "RD", resolution = "1080p", quality = "BluRay Remux", sizeBytes = gib(200.0))
            ),
            benchmarkSessions = mapOf(DebridBenchmarkProvider.REAL_DEBRID to benchmark),
            activeTransportMode = DebridBenchmarkTransportMode.OPTIMIZED
        )

        assertTrue(event.winners.any { it.streamKey == "2160_huge" })
        assertTrue(event.winners.any { it.streamKey == "1080_massive" })
        assertTrue(event.winners.none { it.streamKey == "2160_small_1" })
        assertTrue(event.winners.none { it.streamKey == "2160_small_2" })
    }

    @Test
    fun `movie scoring rejects borderline remux and selects safer high bitrate 4k candidate`() {
        val safeBudgetMbps = 102.2301392
        val throughputP10Mbps = safeBudgetMbps / 0.85
        val benchmark = benchmarkResult(
            provider = DebridBenchmarkProvider.REAL_DEBRID,
            directP10Mbps = throughputP10Mbps,
            optimizedP10Mbps = throughputP10Mbps,
            directDecisionSafeBudgetMbps = safeBudgetMbps,
            optimizedDecisionSafeBudgetMbps = safeBudgetMbps,
            optimizedSeekP95Ms = 415L,
            device = deviceSnapshot(
                displayHdrTypes = emptySet(),
                truehdSupported = false,
                truehdPassthrough = false,
                eac3Supported = false,
                eac3Passthrough = false,
                ac3Supported = false,
                ac3Passthrough = false,
                dtsSupported = false,
                dtsPassthrough = false,
                dtshdSupported = false,
                dtshdPassthrough = false
            )
        )

        val event = scorer.score(
            request = request(runtimeMinutes = 180),
            streams = listOf(
                streamCard(
                    streamKey = "borderline_remux",
                    providerId = "RD",
                    filename = "The.Lord.of.the.Rings.The.Two.Towers.2002.THEATRICAL.4K.HDR.2160p.BDRemux Ita Eng x265-NAHOM.mkv",
                    quality = "BluRay REMUX",
                    sizeBytes = 77_883_182_944L,
                    visualTags = listOf("HDR"),
                    audioTags = emptyList()
                ),
                streamCard(
                    streamKey = "safer_hdr10_bluray",
                    providerId = "RD",
                    filename = "The Lord of the Rings - The Two Towers 2002 Extended UHD BluRay HDR10 10Bit 2160p Dts-HDMa7.1 HEVC-d3g.mkv",
                    quality = "BluRay",
                    sizeBytes = 58_778_158_991L,
                    visualTags = listOf("HDR10", "10bit"),
                    audioTags = emptyList()
                )
            ),
            benchmarkSessions = mapOf(DebridBenchmarkProvider.REAL_DEBRID to benchmark),
            activeTransportMode = DebridBenchmarkTransportMode.OPTIMIZED
        )

        assertEquals("safer_hdr10_bluray", event.selected?.streamKey)
    }

    @Test
    fun `movie scoring keeps better hdr fit over lower fit candidate even when transport is saturated`() {
        val safeBudgetMbps = 100.0
        val throughputP10Mbps = safeBudgetMbps / 0.85
        val benchmark = benchmarkResult(
            provider = DebridBenchmarkProvider.REAL_DEBRID,
            directP10Mbps = throughputP10Mbps,
            optimizedP10Mbps = throughputP10Mbps,
            directDecisionSafeBudgetMbps = safeBudgetMbps,
            optimizedDecisionSafeBudgetMbps = safeBudgetMbps
        )

        val event = scorer.score(
            request = request(runtimeMinutes = 120),
            streams = listOf(
                streamCard(
                    streamKey = "risky_remux",
                    providerId = "RD",
                    quality = "BluRay Remux",
                    encode = "HEVC",
                    sizeBytes = sizeBytesForAverageBitrateMbps(52.0, 120),
                    visualTags = listOf("HDR10"),
                    audioTags = emptyList()
                ),
                streamCard(
                    streamKey = "safer_bluray",
                    providerId = "RD",
                    quality = "BluRay",
                    encode = "HEVC",
                    sizeBytes = sizeBytesForAverageBitrateMbps(46.8, 120),
                    visualTags = emptyList(),
                    audioTags = emptyList()
                )
            ),
            benchmarkSessions = mapOf(DebridBenchmarkProvider.REAL_DEBRID to benchmark),
            activeTransportMode = DebridBenchmarkTransportMode.OPTIMIZED
        )

        assertEquals("risky_remux", event.selected?.streamKey)
        assertEquals(listOf("risky_remux", "safer_bluray"), event.winners.map { it.streamKey })
    }

    @Test
    fun `movie saturated headroom prefers supported hdr10 atmos over unsupported truehd remux`() {
        val benchmark = benchmarkResult(
            provider = DebridBenchmarkProvider.REAL_DEBRID,
            optimizedP10Mbps = 750.0,
            directP10Mbps = 600.0,
            device = deviceSnapshot(
                displayHdrTypes = setOf(DeviceHdrType.HDR10, DeviceHdrType.HDR10_PLUS),
                atmosSupported = true,
                atmosPassthrough = true,
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
        val event = scorer.score(
            request = request(runtimeMinutes = 180),
            streams = listOf(
                streamCard(
                    streamKey = "nitro_truehd",
                    providerId = "RD",
                    quality = "BluRay Remux",
                    encode = "HEVC",
                    sizeBytes = 113_712_493_229L,
                    durationMs = 10740_000L,
                    visualTags = listOf("HDR"),
                    audioTags = listOf("TrueHD")
                ),
                streamCard(
                    streamKey = "hdr10_atmos",
                    providerId = "RD",
                    quality = "BluRay Remux",
                    encode = null,
                    filename = "The Lord of the Rings - The Two Towers 2002 Extended UHD BluRay HDR10 10Bit 2160p Dts-HDMa7.1 HEVC-d3g.mkv",
                    sizeBytes = 139_763_188_653L,
                    durationMs = 10740_000L,
                    visualTags = listOf("HDR10", "DV"),
                    audioTags = listOf("Atmos", "TrueHD")
                )
            ),
            benchmarkSessions = mapOf(DebridBenchmarkProvider.REAL_DEBRID to benchmark),
            activeTransportMode = DebridBenchmarkTransportMode.OPTIMIZED
        )
        assertEquals("hdr10_atmos", event.selected?.streamKey)
    }

    @Test
    fun `movie saturated headroom prefers hdr10 atmos over unsupported dts hd remux`() {
        val benchmark = benchmarkResult(
            provider = DebridBenchmarkProvider.REAL_DEBRID,
            optimizedP10Mbps = 750.0,
            directP10Mbps = 600.0,
            device = deviceSnapshot(
                displayHdrTypes = setOf(DeviceHdrType.HDR10, DeviceHdrType.HDR10_PLUS),
                atmosSupported = true,
                atmosPassthrough = true,
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
        val event = scorer.score(
            request = request(runtimeMinutes = 169),
            streams = listOf(
                streamCard(
                    streamKey = "sgf_dtshd",
                    providerId = "RD",
                    quality = "BluRay Remux",
                    encode = "HEVC",
                    sizeBytes = 83_986_930_257L,
                    durationMs = 10140_000L,
                    visualTags = listOf("HDR", "DV"),
                    audioTags = listOf("DTS-HD MA")
                ),
                streamCard(
                    streamKey = "kc_hdr10_atmos",
                    providerId = "RD",
                    quality = "BluRay Remux",
                    encode = "HEVC",
                    sizeBytes = 95_563_022_336L,
                    durationMs = 10140_000L,
                    visualTags = listOf("HDR10", "DV"),
                    audioTags = listOf("Atmos", "DTS-HD", "TrueHD")
                )
            ),
            benchmarkSessions = mapOf(DebridBenchmarkProvider.REAL_DEBRID to benchmark),
            activeTransportMode = DebridBenchmarkTransportMode.OPTIMIZED
        )
        assertEquals("kc_hdr10_atmos", event.selected?.streamKey)
    }

    @Test
    fun `movie saturated headroom prefers hdr10 atmos over unsupported truehd on return of the king`() {
        val benchmark = benchmarkResult(
            provider = DebridBenchmarkProvider.REAL_DEBRID,
            optimizedP10Mbps = 750.0,
            directP10Mbps = 600.0,
            device = deviceSnapshot(
                displayHdrTypes = setOf(DeviceHdrType.HDR10, DeviceHdrType.HDR10_PLUS),
                atmosSupported = true,
                atmosPassthrough = true,
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
        val event = scorer.score(
            request = request(runtimeMinutes = 201),
            streams = listOf(
                streamCard(
                    streamKey = "nitro_truehd_rok",
                    providerId = "RD",
                    quality = "BluRay Remux",
                    encode = "HEVC",
                    sizeBytes = 133_604_792_514L,
                    durationMs = 12060_000L,
                    visualTags = listOf("HDR"),
                    audioTags = listOf("TrueHD")
                ),
                streamCard(
                    streamKey = "hdr10_atmos_rok",
                    providerId = "RD",
                    quality = "BluRay Remux",
                    encode = "HEVC",
                    sizeBytes = 136_695_949_310L,
                    durationMs = 12060_000L,
                    visualTags = listOf("HDR10", "DV"),
                    audioTags = listOf("Atmos", "TrueHD")
                )
            ),
            benchmarkSessions = mapOf(DebridBenchmarkProvider.REAL_DEBRID to benchmark),
            activeTransportMode = DebridBenchmarkTransportMode.OPTIMIZED
        )
        assertEquals("hdr10_atmos_rok", event.selected?.streamKey)
    }

    @Test
    fun `show scoring caps transport reward so viable higher bitrate release wins over tiny webdl`() {
        val benchmark = benchmarkResult(
            provider = DebridBenchmarkProvider.REAL_DEBRID,
            optimizedP10Mbps = 600.0,
            directP10Mbps = 300.0
        )
        val event = scorer.score(
            request = seriesRequest(runtimeMinutes = 46),
            streams = listOf(
                streamCard(
                    streamKey = "show_tiny_webdl",
                    providerId = "RD",
                    quality = "WEB-DL",
                    sizeBytes = gib(3.0),
                    visualTags = listOf("HDR10"),
                    audioTags = listOf("DD+")
                ),
                streamCard(
                    streamKey = "show_large_bluray",
                    providerId = "RD",
                    quality = "BluRay",
                    sizeBytes = gib(15.0),
                    visualTags = listOf("HDR10"),
                    audioTags = listOf("DD+")
                )
            ),
            benchmarkSessions = mapOf(DebridBenchmarkProvider.REAL_DEBRID to benchmark),
            activeTransportMode = DebridBenchmarkTransportMode.OPTIMIZED
        )

        assertEquals("show_large_bluray", event.selected?.streamKey)
        assertTrue(event.winners.none { it.streamKey == "show_tiny_webdl" })
        assertTrue(event.winners.any { it.streamKey == "show_large_bluray" })
    }

    @Test
    fun `show scoring keeps top webdl dv pool and records lower non dv fallback`() {
        val benchmark = benchmarkResult(
            provider = DebridBenchmarkProvider.REAL_DEBRID,
            device = deviceSnapshot(
                displayHdrTypes = setOf(DeviceHdrType.HDR10, DeviceHdrType.HDR10_PLUS, DeviceHdrType.HLG)
            ),
            optimizedP10Mbps = 300.0
        )
        val event = scorer.score(
            request = seriesRequest(runtimeMinutes = 46),
            streams = listOf(
                streamCard(
                    streamKey = "show_top_bad_dv_1",
                    providerId = "RD",
                    quality = "WEB-DL",
                    sizeBytes = gib(12.0),
                    visualTags = listOf("DV"),
                    audioTags = listOf("DD+")
                ),
                streamCard(
                    streamKey = "show_top_bad_dv_2",
                    providerId = "RD",
                    quality = "WEB-DL",
                    sizeBytes = gib(11.0),
                    visualTags = listOf("DV"),
                    audioTags = listOf("DD+")
                ),
                streamCard(
                    streamKey = "show_top_bad_dv_3",
                    providerId = "RD",
                    quality = "WEB-DL",
                    sizeBytes = gib(10.0),
                    visualTags = listOf("DV"),
                    audioTags = listOf("DD+")
                ),
                streamCard(
                    streamKey = "show_lower_hdr10",
                    providerId = "RD",
                    resolution = "1080p",
                    quality = "WEB-DL",
                    sizeBytes = gib(6.0),
                    visualTags = listOf("HDR10"),
                    audioTags = listOf("DD+")
                )
            ),
            benchmarkSessions = mapOf(DebridBenchmarkProvider.REAL_DEBRID to benchmark),
            activeTransportMode = DebridBenchmarkTransportMode.OPTIMIZED
        )

        assertEquals(
            listOf("show_top_bad_dv_1", "show_top_bad_dv_2", "show_top_bad_dv_3"),
            event.winners.map { it.streamKey }
        )
        assertEquals("show_top_bad_dv_1", event.selected?.streamKey)
        assertEquals("show_lower_hdr10", event.selectedNonDolbyVisionFallback?.streamKey)
    }

    private fun request(runtimeMinutes: Int? = 120): ShadowRequestContext {
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

    private fun seriesRequest(runtimeMinutes: Int? = 46): ShadowRequestContext {
        return ShadowRequestContext(
            requestId = "req-series-1",
            videoId = "tt123:1:1",
            contentType = "series",
            title = "Example Show",
            season = 1,
            episode = 1,
            runtimeMinutes = runtimeMinutes
        )
    }

    private fun streamCard(
        streamKey: String,
        providerId: String,
        wrappedProviderId: String? = providerId,
        filename: String = "$streamKey.mkv",
        folderName: String? = null,
        resolution: String = "2160p",
        quality: String = "BluRay Remux",
        encode: String? = "HEVC",
        sizeBytes: Long = gib(42.0),
        durationMs: Long? = 120L * 60_000L,
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
            folderName = folderName,
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
            durationMs = durationMs?.takeIf { it > 0L },
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

    private fun sizeBytesForAverageBitrateMbps(value: Double, runtimeMinutes: Int): Long {
        return ((value * 1_000_000.0) * (runtimeMinutes * 60.0) / 8.0).roundToLong()
    }

    private fun deviceSnapshot(
        displayHdrTypes: Set<DeviceHdrType> = setOf(DeviceHdrType.DOLBY_VISION, DeviceHdrType.HDR10),
        truehdSupported: Boolean = true,
        truehdPassthrough: Boolean = true,
        eac3Supported: Boolean = true,
        eac3Passthrough: Boolean = true,
        ac3Supported: Boolean = true,
        ac3Passthrough: Boolean = true,
        dtsSupported: Boolean = true,
        dtsPassthrough: Boolean = true,
        dtshdSupported: Boolean = true,
        dtshdPassthrough: Boolean = true,
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
}
