package com.nexio.tv.data.trailer.helper

import org.junit.Assert.assertEquals
import org.junit.Test

class BundledTrailerHelperTest {
    @Test
    fun `builds helper invocation args for bearer auth`() {
        val request = TrailerHelperRequest(
            youtubeUrl = "https://www.youtube.com/watch?v=abc123",
            authorizationHeader = "Bearer test-token",
            pageId = "delegated-page",
            authUser = "2"
        )

        val args = buildTrailerHelperInvocationArgs(
            request = request
        )

        assertEquals(
            listOf(
                "https://www.youtube.com/watch?v=abc123",
                "Bearer test-token",
                "delegated-page",
                "2",
                "Mozilla/5.0 (Linux; Android 12; Android TV) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36",
                "en-US,en;q=0.9",
                "https://www.youtube.com",
                "https://www.youtube.com/"
            ),
            args.toList()
        )
    }

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

}
