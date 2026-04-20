package com.nexio.tv.core.player

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FfmpegStreamMetadataProbeTest {
    @After
    fun resetProbeBackend() {
        FfmpegStreamMetadataProbe.resetForTesting()
    }

    @Test
    fun detectsEmbeddedAssSubtitleStreamsFromFfmpegMetadata() {
        val result = FfmpegStreamMetadataProbe.parseForTesting(
            """
            {
              "streams": [
                {"codec_type": "video", "codec_name": "hevc"},
                {"codec_type": "audio", "codec_name": "opus"},
                {"codec_type": "subtitle", "codec_name": "ass"}
              ]
            }
            """.trimIndent()
        )

        assertTrue(result.hasEmbeddedAssSsaSubtitleStream)
    }

    @Test
    fun ignoresNonAssSubtitleStreams() {
        val result = FfmpegStreamMetadataProbe.parseForTesting(
            """
            {
              "streams": [
                {"codec_type": "subtitle", "codec_name": "hdmv_pgs_subtitle"},
                {"codec_type": "subtitle", "codec_name": "subrip"}
              ]
            }
            """.trimIndent()
        )

        assertFalse(result.hasEmbeddedAssSsaSubtitleStream)
    }

    @Test
    fun emptyNativeResultDoesNotPopulateCacheAndNextSuccessCanRecover() {
        var calls = 0
        FfmpegStreamMetadataProbe.setBackendForTesting(
            object : FfmpegStreamMetadataBackend {
                override fun probeStreamMetadataJson(
                    url: String,
                    requestHeadersBlob: String?
                ): String? {
                    calls += 1
                    return if (calls == 1) {
                        """{"streams":[]}"""
                    } else {
                        """{"streams":[{"codec_type":"video","codec_name":"hevc"}]}"""
                    }
                }
            }
        )

        assertNull(FfmpegStreamMetadataProbe.probeBlocking("https://example.test/video.mkv"))

        val recovered = FfmpegStreamMetadataProbe.probeBlocking("https://example.test/video.mkv")

        assertEquals(2, calls)
        assertEquals("hevc", recovered?.streams?.single()?.codecName)
    }

    @Test
    fun parsesDolbyVisionProfileFromFfprobeSideDataList() {
        val result = FfmpegStreamMetadataProbe.parseForTesting(
            """
            {
              "streams": [
                {
                  "index": 0,
                  "codec_type": "video",
                  "codec_name": "hevc",
                  "width": 3840,
                  "height": 2160,
                  "avg_frame_rate": "24000/1001",
                  "side_data_list": [
                    {
                      "side_data_type": "DOVI configuration record",
                      "dv_profile": 8
                    }
                  ]
                },
                {"index": 2, "codec_type": "subtitle", "codec_name": "ass"}
              ]
            }
            """.trimIndent()
        )

        assertEquals(8, result.streams.first().dvProfile)
        assertTrue(result.hasEmbeddedAssSsaSubtitleStream)
    }

    @Test
    fun debugCommandLogsExactUrlAndHeaderBlob() {
        val command = FfmpegStreamMetadataProbe.debugProbeCommandForTesting(
            url = "https://example.test/secret/path/movie.mkv?token=abc",
            requestHeadersBlob = "Authorization: Bearer secret\r\nUser-Agent: Nexio\r\n"
        )

        assertTrue(command.contains("ffprobe -v error"))
        assertTrue(command.contains("-rw_timeout 5000000"))
        assertTrue(command.contains("-probesize 10000"))
        assertTrue(command.contains("-analyzeduration 10000"))
        assertTrue(command.contains("-headers 'Authorization: Bearer secret\\r\\nUser-Agent: Nexio\\r\\n'"))
        assertTrue(command.contains("'https://example.test/secret/path/movie.mkv?token=abc'"))
    }

    @Test
    fun debugCommandOmitsHeadersArgumentWhenNoHeadersArePresent() {
        val command = FfmpegStreamMetadataProbe.debugProbeCommandForTesting(
            url = "https://example.test/video.mkv",
            requestHeadersBlob = null
        )

        assertFalse(command.contains("-headers"))
        assertTrue(command.endsWith("'https://example.test/video.mkv'"))
    }
}
