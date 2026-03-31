package com.nexio.tv.data.trailer.helper

import org.junit.Assert.assertEquals
import org.junit.Test

class BundledTrailerHelperTest {

    @Test
    fun `parses video and audio urls from helper stdout`() {
        val parsed = parseHelperStdout(
            """
            {"videoUrl":"https://video.example","audioUrl":"https://audio.example","expiresAtEpochMs":1234}
            """.trimIndent()
        )

        assertEquals("https://video.example", parsed.videoUrl)
        assertEquals("https://audio.example", parsed.audioUrl)
    }

    @Test
    fun `parses line based yt dlp output and derives expiry from url`() {
        val parsed = parseHelperStdout(
            """
            https://rr2---sn.example.googlevideo.com/videoplayback?expire=1900000000&id=video
            https://rr2---sn.example.googlevideo.com/videoplayback?expire=1900000000&id=audio
            """.trimIndent()
        )

        assertEquals(
            "https://rr2---sn.example.googlevideo.com/videoplayback?expire=1900000000&id=video",
            parsed.videoUrl
        )
        assertEquals(
            "https://rr2---sn.example.googlevideo.com/videoplayback?expire=1900000000&id=audio",
            parsed.audioUrl
        )
        assertEquals(1_900_000_000_000L, parsed.expiresAtEpochMs)
    }

    @Test
    fun `selectTrailerHelperAbi picks first supported packaged abi`() {
        val selected = selectTrailerHelperAbi(
            supportedAbis = arrayOf("arm64-v8a", "armeabi-v7a"),
            availableRuntimeAbis = arrayOf("x86_64", "arm64-v8a")
        )

        assertEquals("arm64-v8a", selected)
    }
}
