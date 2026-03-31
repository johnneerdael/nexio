package com.nexio.tv.ui.screens.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomePosterTrailerOptionsTest {

    @Test
    fun `returns playable trailer only when internal preview exists`() {
        val playback = playableHomeTrailerFor(
            itemId = "tt123",
            title = "Example",
            previewUrls = mapOf("tt123" to "https://video.example"),
            previewAudioUrls = mapOf("tt123" to "https://audio.example")
        )

        assertEquals("https://video.example", playback?.videoUrl)
        assertEquals("https://audio.example", playback?.audioUrl)
    }

    @Test
    fun `returns null when trailer preview has not been resolved`() {
        val playback = playableHomeTrailerFor(
            itemId = "tt123",
            title = "Example",
            previewUrls = emptyMap(),
            previewAudioUrls = emptyMap()
        )

        assertNull(playback)
    }
}
