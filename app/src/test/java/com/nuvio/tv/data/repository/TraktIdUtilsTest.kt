package com.nuvio.tv.data.repository

import com.nuvio.tv.data.remote.dto.trakt.TraktIdsDto
import org.junit.Assert.assertEquals
import org.junit.Test

class TraktIdUtilsTest {

    @Test
    fun `parseContentIds extracts imdb id`() {
        val parsed = parseContentIds("tt1234567")
        assertEquals("tt1234567", parsed.imdb)
        assertEquals(null, parsed.tmdb)
        assertEquals(null, parsed.trakt)
    }

    @Test
    fun `normalizeContentId prefers imdb then tmdb then trakt`() {
        assertEquals(
            "tt7654321",
            normalizeContentId(TraktIdsDto(imdb = "tt7654321", tmdb = 100, trakt = 55))
        )
        assertEquals(
            "tmdb:100",
            normalizeContentId(TraktIdsDto(tmdb = 100, trakt = 55))
        )
        assertEquals(
            "trakt:55",
            normalizeContentId(TraktIdsDto(trakt = 55))
        )
    }

    @Test
    fun `toTraktPathId converts tmdb and keeps imdb`() {
        assertEquals("tmdb:1399", toTraktPathId("tmdb:1399"))
        assertEquals("tt0944947", toTraktPathId("tt0944947"))
        assertEquals("42", toTraktPathId("trakt:42"))
    }
}

