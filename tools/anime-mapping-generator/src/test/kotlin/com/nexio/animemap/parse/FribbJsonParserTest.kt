package com.nexio.animemap.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FribbJsonParserTest {

    private fun fixture(): String =
        javaClass.getResource("/fixtures/fribb-mha-and-one-piece.json")!!.readText()

    @Test fun `parses all id fields for MHA S1`() {
        val parser = FribbJsonParser()
        val frags = parser.parse(fixture())
        val mha = frags.first { it.anidb == "11739" }
        assertEquals("11469", mha.kitsu)
        assertEquals("305074", mha.tvdb)
        assertEquals("65930", mha.tmdb)
        assertEquals("tt5626028", mha.imdb)
        assertEquals("31964", mha.mal)
        assertEquals("21459", mha.anilist)
        assertEquals("TV", mha.sourceType)
    }

    @Test fun `extracts tvdb season for seasonal anime`() {
        val parser = FribbJsonParser()
        val frags = parser.parse(fixture())
        val mhaS3 = frags.first { it.anidb == "13485" }
        assertEquals("3", mhaS3.tvdbSeasonHint)
        assertEquals("3", mhaS3.tmdbSeasonHint)
    }

    @Test fun `tvdb season is null when source omits season field`() {
        val parser = FribbJsonParser()
        val frags = parser.parse(fixture())
        val onePiece = frags.first { it.anidb == "69" }
        assertNull(onePiece.tvdbSeasonHint)
        assertNull(onePiece.tmdbSeasonHint)
    }

    @Test fun `parses three records from fixture`() {
        val parser = FribbJsonParser()
        val frags = parser.parse(fixture())
        assertEquals(3, frags.size)
    }
}
