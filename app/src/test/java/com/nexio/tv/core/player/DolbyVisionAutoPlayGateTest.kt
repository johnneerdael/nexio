package com.nexio.tv.core.player

import android.content.ContextWrapper
import com.nexio.tv.data.local.PlayerPreference
import com.nexio.tv.ui.screens.stream.AutoPlayStreamAlternative
import com.nexio.tv.ui.screens.stream.StreamPlaybackInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking

class DolbyVisionAutoPlayGateTest {
    private val context = ContextWrapper(null)

    @Test
    fun `manual playback never probes or falls back`() = runBlocking {
        val probe = RecordingDolbyVisionProfileProbe(
            DolbyVisionProfileProbeResult.detected(profileLabel = "dvhe.05", profileNumber = 5)
        )
        val gate = DolbyVisionAutoPlayGate(probe)

        val resolved = gate.resolve(
            context = context,
            playbackInfo = primaryPlaybackInfo(),
            autoPlay = false,
            displaySupportsDolbyVision = false
        )

        assertEquals("primary", resolved.playbackInfo.streamKey)
        assertFalse(resolved.fallbackApplied)
        assertEquals(0, probe.invocations)
    }

    @Test
    fun `autoplay falls back on non dv displays when probe detects profile 5`() = runBlocking {
        val probe = RecordingDolbyVisionProfileProbe(
            DolbyVisionProfileProbeResult.detected(profileLabel = "dvhe.05", profileNumber = 5)
        )
        val gate = DolbyVisionAutoPlayGate(probe)

        val resolved = gate.resolve(
            context = context,
            playbackInfo = primaryPlaybackInfo(),
            autoPlay = true,
            displaySupportsDolbyVision = false
        )

        assertEquals("fallback_hdr10", resolved.playbackInfo.streamKey)
        assertTrue(resolved.fallbackApplied)
        assertEquals(DolbyVisionAutoPlayDecisionReason.UNSUPPORTED_PROFILE_5, resolved.reason)
        assertEquals(1, probe.invocations)
    }

    @Test
    fun `autoplay keeps primary stream on non dv displays when probe detects profile 7`() = runBlocking {
        val probe = RecordingDolbyVisionProfileProbe(
            DolbyVisionProfileProbeResult.detected(profileLabel = "dvhe.07", profileNumber = 7)
        )
        val gate = DolbyVisionAutoPlayGate(probe)

        val resolved = gate.resolve(
            context = context,
            playbackInfo = primaryPlaybackInfo(),
            autoPlay = true,
            displaySupportsDolbyVision = false
        )

        assertEquals("primary", resolved.playbackInfo.streamKey)
        assertFalse(resolved.fallbackApplied)
        assertEquals(DolbyVisionAutoPlayDecisionReason.PROFILE_ALLOWED, resolved.reason)
        assertEquals(1, probe.invocations)
    }

    @Test
    fun `autoplay falls back when probe fails or returns unknown`() = runBlocking {
        val failingProbe = RecordingDolbyVisionProfileProbe(DolbyVisionProfileProbeResult.failed("io"))
        val unknownProbe = RecordingDolbyVisionProfileProbe(DolbyVisionProfileProbeResult.unknown())
        val gate = DolbyVisionAutoPlayGate(failingProbe)
        val failed = gate.resolve(
            context = context,
            playbackInfo = primaryPlaybackInfo(),
            autoPlay = true,
            displaySupportsDolbyVision = false
        )
        val unknown = DolbyVisionAutoPlayGate(unknownProbe).resolve(
            context = context,
            playbackInfo = primaryPlaybackInfo(),
            autoPlay = true,
            displaySupportsDolbyVision = false
        )

        assertEquals("fallback_hdr10", failed.playbackInfo.streamKey)
        assertEquals(DolbyVisionAutoPlayDecisionReason.PROBE_FAILED, failed.reason)
        assertEquals("fallback_hdr10", unknown.playbackInfo.streamKey)
        assertEquals(DolbyVisionAutoPlayDecisionReason.PROBE_UNKNOWN, unknown.reason)
    }

    @Test
    fun `autoplay does not probe when primary stream is not dolby vision`() = runBlocking {
        val probe = RecordingDolbyVisionProfileProbe(
            DolbyVisionProfileProbeResult.detected(profileLabel = "dvhe.05", profileNumber = 5)
        )
        val gate = DolbyVisionAutoPlayGate(probe)

        val resolved = gate.resolve(
            context = context,
            playbackInfo = primaryPlaybackInfo(isDolbyVisionCandidate = false, fallback = null),
            autoPlay = true,
            displaySupportsDolbyVision = false
        )

        assertEquals("primary", resolved.playbackInfo.streamKey)
        assertEquals(DolbyVisionAutoPlayDecisionReason.NOT_DOLBY_VISION, resolved.reason)
        assertEquals(0, probe.invocations)
    }

    private fun primaryPlaybackInfo(
        isDolbyVisionCandidate: Boolean = true,
        fallback: AutoPlayStreamAlternative? = fallbackAlternative()
    ): StreamPlaybackInfo {
        return StreamPlaybackInfo(
            url = "https://example.com/primary.mkv",
            title = "Example",
            streamName = "Primary",
            playerBackend = PlayerPreference.INTERNAL,
            year = "2003",
            isExternal = false,
            isTorrent = false,
            infoHash = null,
            ytId = null,
            headers = null,
            contentId = "tt0167260",
            contentType = "movie",
            contentName = "Example",
            poster = null,
            backdrop = null,
            logo = null,
            videoId = "tt0167260",
            season = null,
            episode = null,
            episodeTitle = null,
            bingeGroup = null,
            rememberedAudioLanguage = null,
            rememberedAudioName = null,
            filename = "primary.mkv",
            videoHash = null,
            videoSize = 42L,
            streamKey = "primary",
            isDolbyVisionCandidate = isDolbyVisionCandidate,
            autoPlayNonDolbyVisionFallback = fallback
        )
    }

    private fun fallbackAlternative(): AutoPlayStreamAlternative {
        return AutoPlayStreamAlternative(
            streamKey = "fallback_hdr10",
            url = "https://example.com/fallback.mkv",
            streamName = "Fallback",
            headers = null,
            filename = "fallback.mkv",
            videoHash = null,
            videoSize = 41L,
            isDolbyVisionCandidate = false
        )
    }

    private class RecordingDolbyVisionProfileProbe(
        private val result: DolbyVisionProfileProbeResult
    ) : DolbyVisionProfileProbe {
        var invocations: Int = 0

        override suspend fun probe(
            context: android.content.Context,
            url: String,
            headers: Map<String, String>?,
            filename: String?
        ): DolbyVisionProfileProbeResult {
            invocations += 1
            return result
        }
    }
}
