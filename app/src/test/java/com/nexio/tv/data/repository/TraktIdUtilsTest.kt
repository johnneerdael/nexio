package com.nexio.tv.data.repository

import com.nexio.tv.data.remote.dto.trakt.TraktIdsDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TraktIdUtilsTest {

    @Test
    fun parseContentIds_recognises_tvdb_prefix() {
        val parsed = parseContentIds("tvdb:81189")
        assertEquals(81189, parsed.tvdb)
        assertNull(parsed.imdb)
        assertNull(parsed.tmdb)
        assertNull(parsed.trakt)
    }

    @Test
    fun toTraktIds_carries_tvdb() {
        val parsed = parseContentIds("tvdb:81189")
        val ids = toTraktIds(parsed)
        assertEquals(81189, ids.tvdb)
    }

    @Test
    fun parseContentIds_still_recognises_existing_prefixes() {
        assertEquals(272, parseContentIds("tmdb:272").tmdb)
        assertEquals("tt0903747", parseContentIds("tt0903747").imdb)
        assertEquals(1, parseContentIds("trakt:1").trakt)
        assertEquals(1, parseContentIds("1").trakt)
    }
}
