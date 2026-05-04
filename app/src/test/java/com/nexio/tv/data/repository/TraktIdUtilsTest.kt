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

    @Test
    fun normalizeContentId_show_kind_prefers_tvdb() {
        val ids = TraktIdsDto(
            trakt = 1, slug = "breaking-bad",
            imdb = "tt0903747", tmdb = 1396, tvdb = 81189
        )
        assertEquals("tvdb:81189", normalizeContentId(ids, MediaKind.SHOW))
    }

    @Test
    fun normalizeContentId_show_falls_back_to_tmdb_when_tvdb_missing() {
        val ids = TraktIdsDto(
            trakt = 1, slug = "x", imdb = "tt1", tmdb = 99, tvdb = null
        )
        assertEquals("tmdb:99", normalizeContentId(ids, MediaKind.SHOW))
    }

    @Test
    fun normalizeContentId_movie_kind_prefers_tmdb() {
        val ids = TraktIdsDto(
            trakt = 6, slug = "batman-begins-2005",
            imdb = "tt0372784", tmdb = 272, tvdb = null
        )
        assertEquals("tmdb:272", normalizeContentId(ids, MediaKind.MOVIE))
    }

    @Test
    fun normalizeContentId_anime_kind_uses_caller_supplied_canonical() {
        val ids = TraktIdsDto(trakt = 1, tmdb = 1396, tvdb = 81189)
        assertEquals(
            "kitsu:42",
            normalizeContentId(ids, MediaKind.ANIME, animeCanonical = "kitsu:42")
        )
    }

    @Test
    fun normalizeContentId_no_kind_overload_keeps_legacy_behaviour() {
        val ids = TraktIdsDto(imdb = "tt0903747", tmdb = 1396, tvdb = 81189)
        assertEquals("tt0903747", normalizeContentId(ids))
    }
}
