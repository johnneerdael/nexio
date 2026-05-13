package com.nexio.tv.data.trailer.captions

import org.junit.Assert.assertEquals
import org.junit.Test

class SrtSerializerTest {

    @Test
    fun `serializes well-formed lines`() {
        val lines = listOf(
            CaptionLine(offsetMs = 1500, durationMs = 2700, text = "Hello world"),
            CaptionLine(offsetMs = 5000, durationMs = 2500, text = "Second line"),
            CaptionLine(offsetMs = 9000, durationMs = 1000, text = "Third")
        )

        val srt = SrtSerializer.serialize(lines)

        val expected = "1\n" +
            "00:00:01,500 --> 00:00:04,200\n" +
            "Hello world\n" +
            "\n" +
            "2\n" +
            "00:00:05,000 --> 00:00:07,500\n" +
            "Second line\n" +
            "\n" +
            "3\n" +
            "00:00:09,000 --> 00:00:10,000\n" +
            "Third\n" +
            "\n"

        assertEquals(expected, srt)
    }

    @Test
    fun `formats hours minutes seconds milliseconds correctly`() {
        val lines = listOf(
            CaptionLine(offsetMs = 3_725_001L, durationMs = 500L, text = "Long")
        )
        val srt = SrtSerializer.serialize(lines)
        // 3,725,001 ms = 1h 02m 05.001s
        assertEquals(
            "1\n" +
                "01:02:05,001 --> 01:02:05,501\n" +
                "Long\n" +
                "\n",
            srt
        )
    }

    @Test
    fun `clamps overlapping caption end to next caption start`() {
        val lines = listOf(
            CaptionLine(offsetMs = 1_000L, durationMs = 4_000L, text = "First"),
            CaptionLine(offsetMs = 2_500L, durationMs = 2_000L, text = "Second")
        )

        val srt = SrtSerializer.serialize(lines)

        assertEquals(
            "1\n" +
                "00:00:01,000 --> 00:00:02,500\n" +
                "First\n" +
                "\n" +
                "2\n" +
                "00:00:02,500 --> 00:00:04,500\n" +
                "Second\n" +
                "\n",
            srt
        )
    }

    @Test
    fun `replaces arrow sequence in caption text with en-dashes`() {
        val lines = listOf(
            CaptionLine(offsetMs = 0, durationMs = 1000, text = "Use --> for arrows")
        )
        val srt = SrtSerializer.serialize(lines)
        assertEquals(
            "1\n" +
                "00:00:00,000 --> 00:00:01,000\n" +
                "Use ––> for arrows\n" +
                "\n",
            srt
        )
    }

    @Test
    fun `empty input yields empty string`() {
        assertEquals("", SrtSerializer.serialize(emptyList()))
    }
}
