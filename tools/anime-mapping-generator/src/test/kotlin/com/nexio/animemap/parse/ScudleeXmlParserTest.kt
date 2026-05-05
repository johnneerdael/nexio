package com.nexio.animemap.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScudleeXmlParserTest {

    private fun fixture(name: String): String =
        javaClass.getResource("/fixtures/$name")!!.readText()

    @Test fun `parses default tvdbseason as numeric string`() {
        val entries = ScudleeXmlParser().parse(fixture("mha-eight-seasons.xml"))
        val mhaS1 = entries.first { it.anidb == "11739" }
        assertEquals("305074", mhaS1.tvdb)
        assertEquals("1", mhaS1.tvdbSeason)
        assertEquals("65930", mhaS1.tmdbTv)
        assertEquals("1", mhaS1.tmdbSeason)
    }

    @Test fun `parses episode offset and tmdb offset`() {
        val entries = ScudleeXmlParser().parse(fixture("mha-eight-seasons.xml"))
        val crayon = entries.first { it.anidb == "11742" }
        assertEquals(23, crayon.tvdbEpisodeOffset)
        assertEquals(23, crayon.tmdbEpisodeOffset)
        assertEquals("tt5526456", crayon.imdb)
    }

    @Test fun `tvdbid hentai marker is captured but does not produce series id`() {
        val entries = ScudleeXmlParser().parse(fixture("mha-eight-seasons.xml"))
        val hentaiEntry = entries.first { it.anidb == "16797" }
        assertEquals("hentai", hentaiEntry.tvdbSeason)
        assertNull(hentaiEntry.tvdb)
    }

    @Test fun `empty anime entry is skipped`() {
        val entries = ScudleeXmlParser().parse(fixture("mha-eight-seasons.xml"))
        assertTrue(entries.none { it.anidb.isEmpty() })
    }

    @Test fun `parses MHA season 2 mapping-list raw xml when present`() {
        val entries = ScudleeXmlParser().parse(fixture("mha-eight-seasons.xml"))
        val mhaS2 = entries.first { it.anidb == "12233" }
        assertTrue(mhaS2.mappingListXml?.contains(";1-2;") == true)
    }

    @Test fun `entries without mapping-list have null mappingListXml`() {
        val entries = ScudleeXmlParser().parse(fixture("mha-eight-seasons.xml"))
        val mhaS1 = entries.first { it.anidb == "11739" }
        assertNull(mhaS1.mappingListXml)
    }
}
