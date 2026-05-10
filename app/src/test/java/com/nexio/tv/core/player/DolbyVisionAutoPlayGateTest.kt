package com.nexio.tv.core.player

import android.content.ContextWrapper
import android.util.Log
import com.nexio.tv.data.local.PlayerPreference
import com.nexio.tv.ui.screens.stream.AutoPlayStreamAlternative
import com.nexio.tv.ui.screens.stream.StreamPlaybackInfo
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

class DolbyVisionAutoPlayGateTest {
    private val context = ContextWrapper(null)

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.i(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
    }

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
    fun `autoplay keeps primary stream on non dv displays when probe detects profile 10`() = runBlocking {
        val probe = RecordingDolbyVisionProfileProbe(
            DolbyVisionProfileProbeResult.detected(profileLabel = "dav1.10", profileNumber = 10)
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
    fun `default autoplay probe timeout is ten seconds`() = runBlocking {
        val probe = RecordingDolbyVisionProfileProbe(
            DolbyVisionProfileProbeResult.detected(profileLabel = "dvhe.08", profileNumber = 8)
        )
        val gate = DolbyVisionAutoPlayGate(probe)

        gate.resolve(
            context = context,
            playbackInfo = primaryPlaybackInfo(),
            autoPlay = true,
            displaySupportsDolbyVision = false
        )

        verify {
            Log.i(
                "DvAutoPlayGate",
                match { message ->
                    message.contains("DV_PROFILE_PROBE_STARTED") &&
                        message.contains("timeoutMs=10000")
                }
            )
        }
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
    fun `autoplay falls back when probe times out`() = runBlocking {
        val slowProbe = object : DolbyVisionProfileProbe {
            override suspend fun probe(
                context: android.content.Context,
                url: String,
                headers: Map<String, String>?,
                filename: String?
            ): DolbyVisionProfileProbeResult {
                delay(50)
                return DolbyVisionProfileProbeResult.detected(
                    profileLabel = "dvhe.05",
                    profileNumber = 5
                )
            }
        }
        val gate = DolbyVisionAutoPlayGate(probe = slowProbe, probeTimeoutMs = 10)

        val resolved = gate.resolve(
            context = context,
            playbackInfo = primaryPlaybackInfo(),
            autoPlay = true,
            displaySupportsDolbyVision = false
        )

        assertEquals("fallback_hdr10", resolved.playbackInfo.streamKey)
        assertEquals(DolbyVisionAutoPlayDecisionReason.PROBE_TIMEOUT, resolved.reason)
        verify {
            Log.i(
                "DvAutoPlayGate",
                match { message ->
                    message.contains("DV_PROFILE_TIMEOUT") &&
                        message.contains("timeoutMs=10")
                }
            )
        }
    }

    @Test
    fun `autoplay reuses precomputed probe result instead of probing again`() = runBlocking {
        val probe = RecordingDolbyVisionProfileProbe(
            DolbyVisionProfileProbeResult.detected(profileLabel = "dvhe.07", profileNumber = 7)
        )
        val gate = DolbyVisionAutoPlayGate(probe)

        val resolved = gate.resolve(
            context = context,
            playbackInfo = primaryPlaybackInfo(),
            autoPlay = true,
            displaySupportsDolbyVision = false,
            precomputedProbeResult = DolbyVisionProfileProbeResult.detected(
                profileLabel = "dvhe.07",
                profileNumber = 7
            )
        )

        assertEquals("primary", resolved.playbackInfo.streamKey)
        assertFalse(resolved.fallbackApplied)
        assertEquals(0, probe.invocations)
    }

    @Test
    fun `autoplay logs primary fallback probe result and finalized decision with timestamps`() = runBlocking {
        val probe = RecordingDolbyVisionProfileProbe(
            DolbyVisionProfileProbeResult.detected(profileLabel = "dvhe.05", profileNumber = 5)
        )
        val gate = DolbyVisionAutoPlayGate(probe)

        gate.resolve(
            context = context,
            playbackInfo = primaryPlaybackInfo(),
            autoPlay = true,
            displaySupportsDolbyVision = false
        )

        verify {
            Log.i(
                "DvAutoPlayGate",
                match { message ->
                    message.contains("PRIMARY_SELECTED") &&
                        message.contains("stream=primary") &&
                        message.contains("ts=")
                }
            )
        }
        verify {
            Log.i(
                "DvAutoPlayGate",
                match { message ->
                    message.contains("FALLBACK_CANDIDATES") &&
                        message.contains("count=1") &&
                        message.contains("ts=")
                }
            )
        }
        verify {
            Log.i(
                "DvAutoPlayGate",
                match { message ->
                    message.contains("DV_PROFILE_PROBE_STARTED") &&
                        message.contains("stream=primary") &&
                        message.contains("ts=")
                }
            )
        }
        verify {
            Log.i(
                "DvAutoPlayGate",
                match { message ->
                    message.contains("DV_PROFILE_DETECTED") &&
                        message.contains("profile=dvhe.05") &&
                        message.contains("ts=")
                }
            )
        }
        verify {
            Log.i(
                "DvAutoPlayGate",
                match { message ->
                    message.contains("FINAL_PLAYBACK_DECISION") &&
                        message.contains("selected=fallback_hdr10") &&
                        message.contains("ts=")
                }
            )
        }
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

    @Test
    fun `composite probe falls back to next probe when ffmpeg fails`() = runBlocking {
        val probe = CompositeDolbyVisionProfileProbe(
            probes = listOf(
                RecordingDolbyVisionProfileProbe(DolbyVisionProfileProbeResult.failed("ffmpeg_probe_failed")),
                RecordingDolbyVisionProfileProbe(
                    DolbyVisionProfileProbeResult.detected(
                        profileLabel = "dvhe.07",
                        profileNumber = 7
                    )
                )
            )
        )

        val result = probe.probe(
            context = context,
            url = "https://example.com/stream.mkv",
            headers = null,
            filename = "stream.mkv"
        )

        assertEquals(DolbyVisionProfileProbeStatus.DETECTED, result.status)
        assertEquals(7, result.profileNumber)
    }

    @Test
    fun `composite probe prefers unknown over failed when no definitive result exists`() = runBlocking {
        val probe = CompositeDolbyVisionProfileProbe(
            probes = listOf(
                RecordingDolbyVisionProfileProbe(DolbyVisionProfileProbeResult.failed("ffmpeg_probe_failed")),
                RecordingDolbyVisionProfileProbe(DolbyVisionProfileProbeResult.unknown())
            )
        )

        val result = probe.probe(
            context = context,
            url = "https://example.com/stream.mkv",
            headers = null,
            filename = "stream.mkv"
        )

        assertEquals(DolbyVisionProfileProbeStatus.UNKNOWN, result.status)
    }

    @Test
    fun `composite probe short-circuits on unknown by default`() = runBlocking {
        val first = RecordingDolbyVisionProfileProbe(DolbyVisionProfileProbeResult.unknown())
        val second = RecordingDolbyVisionProfileProbe(
            DolbyVisionProfileProbeResult.detected(
                profileLabel = "dvhe.07",
                profileNumber = 7
            )
        )
        val probe = CompositeDolbyVisionProfileProbe(
            probes = listOf(first, second)
        )

        val result = probe.probe(
            context = context,
            url = "https://example.com/stream.mkv",
            headers = null,
            filename = "stream.mkv"
        )

        assertEquals(DolbyVisionProfileProbeStatus.UNKNOWN, result.status)
        assertEquals(1, first.invocations)
        assertEquals(0, second.invocations)
    }

    private fun primaryPlaybackInfo(
        isWebDl: Boolean = true,
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
            originalLanguage = "en",
            imdbId = null,
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
            isWebDl = isWebDl,
            isDolbyVisionCandidate = isDolbyVisionCandidate,
            autoPlayFallbackCandidates = if (fallback != null) listOf(fallback) else emptyList()
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
