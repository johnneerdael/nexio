package com.nexio.tv.ui.components

import androidx.media3.common.C
import androidx.media3.common.TrackSelectionParameters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrailerPlayerSourceSelectionTest {

    @Test
    fun `chunked youtube data source is disabled for hls trailer playback`() {
        assertFalse(
            shouldUseChunkedTrailerDataSource(
                trailerUrl = "https://manifest.googlevideo.com/api/manifest/hls_variant/file/index.m3u8",
                trailerAudioUrl = null
            )
        )
    }

    @Test
    fun `chunked youtube data source stays enabled for direct split googlevideo playback`() {
        assertTrue(
            shouldUseChunkedTrailerDataSource(
                trailerUrl = "https://rr4---sn-uhvcpaxoa-xpoe.googlevideo.com/videoplayback?id=abc123",
                trailerAudioUrl = "https://rr2---sn-5hne6nsk.googlevideo.com/videoplayback?id=audio123"
            )
        )
    }

    @Test
    fun `trailer track selection starts hls at highest supported 1080p variant`() {
        val parameters = buildTrailerTrackSelectionParameters(TrackSelectionParameters.DEFAULT)

        assertEquals(1920, parameters.maxVideoWidth)
        assertEquals(1080, parameters.maxVideoHeight)
        assertEquals(30, parameters.maxVideoFrameRate)
        assertTrue(parameters.forceHighestSupportedBitrate)
        assertTrue(parameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT))
    }
}
