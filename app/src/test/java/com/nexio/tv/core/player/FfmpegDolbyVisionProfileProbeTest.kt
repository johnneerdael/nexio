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
            backend = NativeDolbyVisionProfileBackend { _, _ -> 5 }
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
            backend = NativeDolbyVisionProfileBackend { _, _ -> -2 }
        ).probe(context, "https://example.com/b.mkv", null, "b.mkv")
        val failed = FfmpegDolbyVisionProfileProbe(
            backend = NativeDolbyVisionProfileBackend { _, _ -> -3 }
        ).probe(context, "https://example.com/c.mkv", null, "c.mkv")

        assertEquals(DolbyVisionProfileProbeStatus.UNKNOWN, unknown.status)
        assertEquals(DolbyVisionProfileProbeStatus.FAILED, failed.status)
    }

    @Test
    fun `headers are serialized for ffmpeg style input`() = runBlocking {
        var capturedHeaders: String? = null
        val probe = FfmpegDolbyVisionProfileProbe(
            backend = NativeDolbyVisionProfileBackend { _, headers ->
                capturedHeaders = headers
                -1
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
}
