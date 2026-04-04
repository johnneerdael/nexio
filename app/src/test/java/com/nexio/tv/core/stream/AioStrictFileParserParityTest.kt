package com.nexio.tv.core.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AioStrictFileParserParityTest {

    @Test
    fun `strict file parser normalizes 4k resolution and extracts tags`() {
        val parsed = AioStrictFileParser.parse(
            "Movie.Title.2023.4K.BluRay.REMUX.HEVC.DV.TrueHD.Atmos.7.1.ENG-GROUP.mkv"
        )

        assertEquals("2160p", parsed.resolution)
        assertEquals("BluRay REMUX", parsed.quality)
        assertEquals("HEVC", parsed.encode)
        assertEquals(listOf("DV"), parsed.visualTags)
        assertEquals(listOf("Atmos", "TrueHD"), parsed.audioTags)
        assertEquals(listOf("7.1"), parsed.audioChannels)
        assertEquals("GROUP", parsed.releaseGroup)
        assertEquals("Movie Title", parsed.title)
        assertEquals("2023", parsed.year)
    }

    @Test
    fun `strict file parser merges parsed and regex languages like ts source`() {
        val parsed = AioStrictFileParser.parse(
            "Shelter.2026.MULTi.VFQ.2160p.HDR.WEB-DL.H265-Slay3R.mkv"
        )

        assertTrue(parsed.languages.contains("Multi"))
        assertTrue(parsed.languages.contains("French"))
        assertEquals("2160p", parsed.resolution)
        assertEquals("WEB-DL", parsed.quality)
        assertEquals("HEVC", parsed.encode)
    }

    @Test
    fun `strict file parser detects season pack without episodes`() {
        val parsed = AioStrictFileParser.parse(
            "Show.S03.1080p.WEB-DL.DDP5.1.H264-GROUP.mkv"
        )

        assertEquals(listOf(3), parsed.seasons)
        assertEquals(emptyList<Int>(), parsed.episodes)
        assertEquals(true, parsed.seasonPack)
    }

    @Test
    fun `strict file parser extracts hlg visual tag`() {
        val parsed = AioStrictFileParser.parse(
            "Movie.Title.2023.2160p.WEB-DL.HEVC.HLG.DDP5.1-GROUP.mkv"
        )

        assertTrue(parsed.visualTags.contains("HLG"))
    }
}
