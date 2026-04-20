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
}
