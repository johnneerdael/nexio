package com.nexio.tv.data.repository.benchmark

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShadowAutoPlayDecisionLoggerTest {

    @Test
    fun `shadow autoplay event json is single line and contains selected winner`() {
        val logger = ShadowAutoPlayDecisionLogger { _, _ -> }
        val payload = logger.buildEventJson(sampleEvent())
        val root = JsonParser.parseString(payload).asJsonObject

        assertFalse(payload.contains('\n'))
        assertEquals(1, root.get("event_version").asInt)
        assertEquals("shadow_autoplay_decision", root.get("event_type").asString)
        assertEquals("rd_stream", root.getAsJsonObject("selected").get("streamKey").asString)
        val selectedParsed = root.getAsJsonObject("selected").getAsJsonObject("parsed")
        assertEquals("RD", selectedParsed.get("serviceId").asString)
        assertEquals("rd_stream.mkv", selectedParsed.get("filename").asString)
        assertEquals(42_000_000_000L, selectedParsed.get("sizeBytes").asLong)
        assertEquals(7_200_000L, selectedParsed.get("durationMs").asLong)
        assertEquals("parser", selectedParsed.get("runtimeSource").asString)
        assertEquals("HEVC", selectedParsed.get("videoCodec").asString)
        assertEquals(
            "pm_hdr",
            root.getAsJsonObject("selectedNonDolbyVisionFallback").get("streamKey").asString
        )
        assertEquals(1, root.getAsJsonArray("winners").size())
        assertEquals(1, root.getAsJsonArray("rejected").size())
        val rejectedParsed = root.getAsJsonArray("rejected").first().asJsonObject.getAsJsonObject("parsed")
        assertEquals("PM", rejectedParsed.get("serviceId").asString)
        assertEquals("pm_stream.mkv", rejectedParsed.get("filename").asString)
    }

    @Test
    fun `summary line reflects chosen provider transport and ratio`() {
        val logger = ShadowAutoPlayDecisionLogger { _, _ -> }

        val summary = logger.buildSummaryLine(sampleEvent())

        assertTrue(summary.contains("winner=real_debrid optimized"))
        assertTrue(summary.contains("ratio=1.44"))
        assertTrue(summary.contains("resolution=2160p"))
    }

    private fun sampleEvent(): ShadowAutoPlayDecisionEvent {
        val selected = ShadowStreamDecision(
            streamKey = "rd_stream",
            parsed = ShadowParsedStreamFacts(
                serviceId = "RD",
                filename = "rd_stream.mkv",
                sizeBytes = 42_000_000_000L,
                durationMs = 7_200_000L,
                runtimeSource = "parser",
                resolution = "2160p",
                quality = "BluRay Remux",
                videoCodec = "HEVC",
                audioTags = listOf("Atmos", "TrueHD"),
                audioChannels = listOf("7.1"),
                visualTags = listOf("DV"),
                languages = listOf("English"),
                releaseGroup = "GROUP",
                cached = true
            ),
            provider = DebridBenchmarkProvider.REAL_DEBRID,
            transport = DebridBenchmarkTransportMode.OPTIMIZED,
            finalScore = 90,
            contentQualityScore = 60,
            transportFitScore = 30,
            suitabilityRatio = 1.44,
            requiredMbps = 72.0,
            safeBudgetMbps = 103.7,
            resolution = "2160p",
            hdrTags = listOf("DV"),
            audioTags = listOf("Atmos", "TrueHD"),
            breakdown = ShadowDecisionBreakdown(
                averageBitrateMbps = 45.0,
                releaseType = "remux",
                lowQuality4k = false,
                realismRatio = 1.12,
                content = ShadowContentScoreBreakdown(
                    resolutionPoints = 28,
                    audioPoints = 24,
                    hdrPoints = 10,
                    codecPoints = 6,
                    releaseTypePoints = 10,
                    bitrateQualityPoints = 24,
                    synergyPoints = 10,
                    penaltyPoints = 0,
                    lowQuality4kPenalty = 0,
                    resolutionTier = "uhd_2160",
                    releaseTypeTier = "remux",
                    codecTier = "hevc_hw",
                    hdrTier = "dolby_vision",
                    audioTier = "truehd_atmos",
                    audioSupportTier = "full_immersive_passthrough",
                    hdrSupportTier = "full",
                    realismRatio = 1.12
                ),
                transport = ShadowTransportScoreBreakdown(
                    provider = DebridBenchmarkProvider.REAL_DEBRID,
                    transport = DebridBenchmarkTransportMode.OPTIMIZED,
                    safeBudgetMbps = 103.7,
                    requiredMbps = 72.0,
                    suitabilityRatio = 1.44,
                    ratioScore = 28,
                    startupScore = 4,
                    seekScore = 6,
                    stabilityScore = 6,
                    startupTtfbMs = 140L,
                    seekTtfbP95Ms = 240L,
                    seekFailRate = 0.0
                )
            )
        )
        return ShadowAutoPlayDecisionEvent(
            eventVersion = 1,
            eventType = "shadow_autoplay_decision",
            request = ShadowRequestContext(
                requestId = "req-1",
                videoId = "tt123",
                contentType = "movie",
                title = "Example",
                season = null,
                episode = null,
                runtimeMinutes = 120
            ),
            benchmarksUsed = listOf(
                ShadowBenchmarkReference(
                    provider = DebridBenchmarkProvider.REAL_DEBRID,
                    measuredAtMs = 42L,
                    benchmarkVersion = 3,
                    host = "cdn.example.net",
                    directUrlFingerprint = "abc123"
                )
            ),
            winners = listOf(selected),
            rejected = listOf(
                ShadowRejectedStream(
                    streamKey = "pm_stream",
                    parsed = ShadowParsedStreamFacts(
                        serviceId = "PM",
                        filename = "pm_stream.mkv"
                    ),
                    provider = DebridBenchmarkProvider.PREMIUMIZE,
                    reasons = listOf(
                        ShadowRejectReason.INSUFFICIENT_TRANSPORT_BUDGET,
                        ShadowRejectReason.NO_ELIGIBLE_TRANSPORT
                    )
                )
            ),
            selected = selected,
            selectedNonDolbyVisionFallback = selected.copy(
                streamKey = "pm_hdr",
                parsed = selected.parsed.copy(
                    serviceId = "PM",
                    filename = "pm_hdr.mkv",
                    visualTags = listOf("HDR10"),
                    audioTags = listOf("TrueHD")
                ),
                provider = DebridBenchmarkProvider.PREMIUMIZE,
                hdrTags = listOf("HDR10"),
                audioTags = listOf("TrueHD")
            ),
            timingsMs = 18L
        )
    }
}
