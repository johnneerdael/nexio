package com.nexio.tv.core.player

import android.content.ContextWrapper
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class FfmpegDolbyVisionProfileProbeTest {

    private val context = ContextWrapper(null)

    @Test
    fun `native backend result 5 maps to detected profile 5`() = runBlocking {
        val probe = FfmpegDolbyVisionProfileProbe(
            backend = fakeBackend(profile = 5)
        )

        val result = probe.probe(
            context = context,
            url = "https://example.com/test.mkv",
            headers = null,
            filename = "test.mkv"
        )

        assertEquals(DolbyVisionProfileProbeStatus.DETECTED, result.status)
        assertEquals(5, result.profileNumber)
    }

    @Test
    fun `native backend negative results map to unknown and failed`() = runBlocking {
        val unknown = FfmpegDolbyVisionProfileProbe(
            backend = fakeBackend(profile = -2)
        ).probe(context, "https://example.com/b.mkv", null, "b.mkv")
        val failed = FfmpegDolbyVisionProfileProbe(
            backend = fakeBackend(profile = -3)
        ).probe(context, "https://example.com/c.mkv", null, "c.mkv")

        assertEquals(DolbyVisionProfileProbeStatus.UNKNOWN, unknown.status)
        assertEquals(DolbyVisionProfileProbeStatus.FAILED, failed.status)
    }

    @Test
    fun `headers are serialized for ffmpeg style input`() = runBlocking {
        var capturedHeaders: String? = null
        val probe = FfmpegDolbyVisionProfileProbe(
            backend = object : NativeDolbyVisionProfileBackend {
                override fun probe(url: String, requestHeadersBlob: String?): Int {
                    capturedHeaders = requestHeadersBlob
                    return -1
                }
            }
        )

        probe.probe(
            context = context,
            url = "https://example.com/test.mkv",
            headers = linkedMapOf("Authorization" to "Bearer token", "User-Agent" to "Nexio"),
            filename = "test.mkv"
        )

        assertEquals("Authorization: Bearer token\r\nUser-Agent: Nexio\r\n", capturedHeaders)
    }

    @Test
    fun `metadata blob populates video audio and hdr fields`() = runBlocking {
        val probe = FfmpegDolbyVisionProfileProbe(
            backend = fakeBackend(
                profile = 7,
                metadataBlob = "video=hevc;audio=truehd;hdr=DolbyVision"
            )
        )

        val result = probe.probe(
            context = context,
            url = "https://example.com/test.mkv",
            headers = null,
            filename = "test.mkv"
        )

        assertEquals(DolbyVisionProfileProbeStatus.DETECTED, result.status)
        assertEquals(7, result.profileNumber)
        assertEquals("hevc", result.videoCodec)
        assertEquals("truehd", result.audioCodec)
        assertEquals("DolbyVision", result.hdrType)
    }

    private fun fakeBackend(
        profile: Int,
        metadataBlob: String? = null
    ): NativeDolbyVisionProfileBackend {
        return object : NativeDolbyVisionProfileBackend {
            override fun probe(url: String, requestHeadersBlob: String?): Int = profile
            override fun probeMetadataBlob(url: String, requestHeadersBlob: String?): String? = metadataBlob
        }
    }
}
