package com.nexio.tv.core.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FrameRateUtilsTest {
    @Test
    fun `parse probe rational handles ntsc cinema rate`() {
        assertEquals(24000f / 1001f, FrameRateUtils.parseProbeRationalForTests("24000/1001")!!, 0.0001f)
    }

    @Test
    fun `parse probe rational rejects empty and zero denominator`() {
        assertNull(FrameRateUtils.parseProbeRationalForTests(""))
        assertNull(FrameRateUtils.parseProbeRationalForTests("0/0"))
        assertNull(FrameRateUtils.parseProbeRationalForTests("24/0"))
    }

    @Test
    fun `parse probe rational handles decimal fallback`() {
        assertEquals(23.976f, FrameRateUtils.parseProbeRationalForTests("23.976")!!, 0.0001f)
    }

    @Test
    fun `parse ffmpeg stream metadata returns frame rate and resolution`() {
        val detection = FrameRateUtils.parseFfmpegStreamMetadataForTests(
            """
            {
              "streams": [
                {"codec_type":"audio","codec_name":"truehd"},
                {"codec_type":"video","codec_name":"hevc","width":3840,"height":2160,"avg_frame_rate":"24000/1001","r_frame_rate":"24/1"}
              ]
            }
            """.trimIndent()
        )

        assertEquals(24000f / 1001f, detection!!.raw, 0.0001f)
        assertEquals(24000f / 1001f, detection.snapped, 0.0001f)
        assertEquals(3840, detection.videoWidth)
        assertEquals(2160, detection.videoHeight)
    }
}
