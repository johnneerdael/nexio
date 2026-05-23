package com.nexio.tv.data.trailer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrailerPlaybackSourcePoTokenTest {
    @Test
    fun `with poToken and both adaptive urls, prefer split adaptive`() {
        val selected = selectPreferredTrailerPlaybackSource(
            combinedUrl = "https://example.com/master.m3u8",
            adaptiveVideoUrl = "https://example.com/video.mp4",
            adaptiveAudioUrl = "https://example.com/audio.m4a",
            streamingDataPoToken = "TOKEN"
        )
        assertEquals("https://example.com/video.mp4", selected?.videoUrl)
        assertEquals("https://example.com/audio.m4a", selected?.audioUrl)
        assertEquals("TOKEN", selected?.streamingDataPoToken)
    }

    @Test
    fun `without poToken, prefer combined even when split adaptive is available`() {
        val selected = selectPreferredTrailerPlaybackSource(
            combinedUrl = "https://example.com/master.m3u8",
            adaptiveVideoUrl = "https://example.com/video.mp4",
            adaptiveAudioUrl = "https://example.com/audio.m4a"
        )
        assertEquals("https://example.com/master.m3u8", selected?.videoUrl)
        assertNull(selected?.audioUrl)
        assertNull(selected?.streamingDataPoToken)
    }
}
