package com.nexio.tv.data.trailer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrailerPlaybackSourceUserAgentTest {
    @Test
    fun `userAgent defaults to null for backwards compatibility`() {
        val source = TrailerPlaybackSource(videoUrl = "https://example.com/v.mp4")

        assertNull(source.userAgent)
    }

    @Test
    fun `selectPreferredTrailerPlaybackSource preserves userAgent for combined source`() {
        val userAgent = "com.google.ios.youtube/21.03.2(iPhone16,2; U; CPU iOS 18_7_2 like Mac OS X; US)"

        val source = selectPreferredTrailerPlaybackSource(
            combinedUrl = "https://manifest.googlevideo.com/api/manifest/hls_variant/index.m3u8",
            adaptiveVideoUrl = null,
            adaptiveAudioUrl = null,
            userAgent = userAgent
        )

        assertEquals(userAgent, source?.userAgent)
    }
}
