package com.nexio.tv.core.player

import android.content.ContextWrapper
import android.util.Log
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class FfmpegDolbyVisionProfileProbeTest {

    private val context = ContextWrapper(null)

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.w(any<String>(), any<String>()) } returns 0
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    @Test
    fun `stream metadata result 5 maps to detected profile 5`() = runBlocking {
        val probe = FfmpegDolbyVisionProfileProbe(
            backend = fakeBackend(
                streamMetadataJson = """
                    {
                      "streams": [
                        {"codec_type":"video","codec_name":"hevc","color_transfer":"smpte2084","color_primaries":"bt2020","dv_profile":5}
                      ]
                    }
                """.trimIndent()
            )
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
    fun `missing stream metadata maps to failed`() = runBlocking {
        val unknown = FfmpegDolbyVisionProfileProbe(
            backend = fakeBackend(streamMetadataJson = null)
        ).probe(context, "https://example.com/b.mkv", null, "b.mkv")

        assertEquals(DolbyVisionProfileProbeStatus.FAILED, unknown.status)
    }

    @Test
    fun `headers are serialized for ffmpeg style input`() = runBlocking {
        var capturedHeaders: String? = null
        val probe = FfmpegDolbyVisionProfileProbe(
            backend = object : NativeDolbyVisionProfileBackend {
                override fun probeStreamMetadataJson(url: String, requestHeadersBlob: String?): String? {
                    capturedHeaders = requestHeadersBlob
                    return """{"streams":[]}"""
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
    fun `stream metadata populates video and hdr fields`() = runBlocking {
        val probe = FfmpegDolbyVisionProfileProbe(
            backend = fakeBackend(
                streamMetadataJson = """
                    {
                      "streams": [
                        {"codec_type":"video","codec_name":"hevc","color_transfer":"smpte2084","color_primaries":"bt2020","dv_profile":7},
                        {"codec_type":"subtitle","codec_name":"ass"}
                      ]
                    }
                """.trimIndent()
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
        assertEquals(null, result.audioCodec)
        assertEquals("dolbyvision", result.hdrType)
    }

    @Test
    fun `stream metadata side data result maps to detected profile`() = runBlocking {
        val probe = FfmpegDolbyVisionProfileProbe(
            backend = fakeBackend(
                streamMetadataJson = """
                    {
                      "streams": [
                        {
                          "index":0,
                          "codec_type":"video",
                          "codec_name":"hevc",
                          "color_transfer":"smpte2084",
                          "color_primaries":"bt2020",
                          "side_data_list":[
                            {"side_data_type":"DOVI configuration record","dv_profile":8}
                          ]
                        },
                        {"index":2,"codec_type":"subtitle","codec_name":"ass"}
                      ]
                    }
                """.trimIndent()
            )
        )

        val result = probe.probe(context, "https://example.com/dv8.mkv", null, "dv8.mkv")

        assertEquals(DolbyVisionProfileProbeStatus.DETECTED, result.status)
        assertEquals(8, result.profileNumber)
        assertEquals("hevc", result.videoCodec)
        assertEquals("dolbyvision", result.hdrType)
    }

    @Test
    fun `stream metadata result 10 maps to detected profile 10`() = runBlocking {
        val probe = FfmpegDolbyVisionProfileProbe(
            backend = fakeBackend(
                streamMetadataJson = """
                    {
                      "streams": [
                        {"codec_type":"video","codec_name":"av1","color_transfer":"smpte2084","color_primaries":"bt2020","dv_profile":10}
                      ]
                    }
                """.trimIndent()
            )
        )

        val result = probe.probe(context, "https://example.com/dv10.mp4", null, "dv10.mp4")

        assertEquals(DolbyVisionProfileProbeStatus.DETECTED, result.status)
        assertEquals(10, result.profileNumber)
        assertEquals("av1", result.videoCodec)
        assertEquals(null, result.audioCodec)
        assertEquals("dolbyvision", result.hdrType)
    }

    @Test
    fun `stream metadata detects hdr10`() = runBlocking {
        val probe = FfmpegDolbyVisionProfileProbe(
            backend = fakeBackend(
                streamMetadataJson = """
                    {
                      "streams": [
                        {"codec_type":"video","codec_name":"hevc","color_transfer":"smpte2084","color_primaries":"bt2020"}
                      ]
                    }
                """.trimIndent()
            )
        )

        val result = probe.probe(context, "https://example.com/test.mkv", null, "test.mkv")

        assertEquals(DolbyVisionProfileProbeStatus.NOT_DOLBY_VISION, result.status)
        assertEquals(null, result.audioCodec)
        assertEquals("hdr10", result.hdrType)
    }

    @Test
    fun `stream metadata detects hdr10 plus`() = runBlocking {
        val probe = FfmpegDolbyVisionProfileProbe(
            backend = fakeBackend(
                streamMetadataJson = """
                    {
                      "streams": [
                        {"codec_type":"video","codec_name":"hevc","color_transfer":"smpte2084","color_primaries":"bt2020","hdr10_plus":true}
                      ]
                    }
                """.trimIndent()
            )
        )

        val result = probe.probe(context, "https://example.com/test.mkv", null, "test.mkv")

        assertEquals(DolbyVisionProfileProbeStatus.NOT_DOLBY_VISION, result.status)
        assertEquals("hdr10+", result.hdrType)
        assertEquals(null, result.audioCodec)
    }

    @Test
    fun `stream metadata ignores frame metadata for dolby vision decision`() = runBlocking {
        val probe = FfmpegDolbyVisionProfileProbe(
            backend = fakeBackend(
                streamMetadataJson = """
                    {
                      "streams": [
                        {
                          "codec_type":"video",
                          "codec_name":"hevc",
                          "width":3840,
                          "height":2160,
                          "avg_frame_rate":"24000/1001",
                          "r_frame_rate":"24/1",
                          "color_transfer":"smpte2084",
                          "color_primaries":"bt2020",
                          "dv_profile":7
                        }
                      ]
                    }
                """.trimIndent()
            )
        )

        val result = probe.probe(context, "https://example.com/test.mkv", null, "test.mkv")

        assertEquals(DolbyVisionProfileProbeStatus.DETECTED, result.status)
        assertEquals(7, result.profileNumber)
        assertEquals("hevc", result.videoCodec)
        assertEquals(null, result.audioCodec)
    }

    @Test
    fun `metadata without dv profile remains not dolby vision`() =
        runBlocking {
            val probe = FfmpegDolbyVisionProfileProbe(
                backend = object : NativeDolbyVisionProfileBackend {
                    override fun probeStreamMetadataJson(
                        url: String,
                        requestHeadersBlob: String?
                    ): String? {
                        return """{"streams":[{"codec_type":"video","codec_name":"h264"}]}"""
                    }
                }
            )

            val result = probe.probe(context, "https://example.com/sdr.mkv", null, "sdr.mkv")

            assertEquals(DolbyVisionProfileProbeStatus.NOT_DOLBY_VISION, result.status)
            assertEquals(null, result.profileNumber)
        }

    private fun fakeBackend(
        streamMetadataJson: String?
    ): NativeDolbyVisionProfileBackend {
        return object : NativeDolbyVisionProfileBackend {
            override fun probeStreamMetadataJson(url: String, requestHeadersBlob: String?): String? = streamMetadataJson
        }
    }
}
