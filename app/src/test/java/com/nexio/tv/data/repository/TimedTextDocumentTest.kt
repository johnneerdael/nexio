package com.nexio.tv.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class TimedTextDocumentTest {
    @Test
    fun parseSrtKeepsTimingLinesAndReplacesCueText() {
        val document = TimedTextDocument.parse(
            raw = """
                1
                00:00:01,000 --> 00:00:02,500
                Hello
                world
            """.trimIndent(),
            url = "https://example.test/subtitle.srt"
        )

        assertNotNull(document)
        val parsed = document!!
        assertEquals("srt", parsed.extension)
        assertEquals(1, parsed.translatableBlocks.size)
        assertEquals("Hello\nworld", parsed.translatableBlocks.single().text)
        assertEquals(
            """
            1
            00:00:01,000 --> 00:00:02,500
            Hallo
            wereld
            """.trimIndent() + "\n",
            parsed.render(mapOf(0 to "Hallo\nwereld"))
        )
    }

    @Test
    fun parseVttKeepsHeaderAsPassthroughAndTranslatesCueText() {
        val document = TimedTextDocument.parse(
            raw = """
                WEBVTT

                00:00:01.000 --> 00:00:02.500
                Hello
            """.trimIndent(),
            url = "https://example.test/subtitle.vtt"
        )

        assertNotNull(document)
        val parsed = document!!
        assertEquals("vtt", parsed.extension)
        assertEquals(1, parsed.translatableBlocks.size)
        assertEquals(
            """
            WEBVTT

            00:00:01.000 --> 00:00:02.500
            Hallo
            """.trimIndent() + "\n",
            parsed.render(mapOf(0 to "Hallo"))
        )
    }
}
