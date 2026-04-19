package com.nexio.tv.core.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FfmpegStreamMetadataProbeTest {
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
}
