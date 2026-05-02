package com.nexio.tv.data.integration.subtitles.wyzie

import com.nexio.tv.domain.model.WyzieIdHints
import org.junit.Assert.assertEquals
import org.junit.Test

class WyzieIdHintsParserTest {

    @Test
    fun `tt-prefixed id parses as imdb with prefix preserved`() {
        assertEquals(
            WyzieIdHints(imdb = "tt0121955"),
            WyzieIdHintsParser.parse("tt0121955"),
        )
    }

    @Test
    fun `tmdb-prefixed id parses as tmdb integer`() {
        assertEquals(
            WyzieIdHints(tmdb = 9876),
            WyzieIdHintsParser.parse("tmdb:9876"),
        )
    }

    @Test
    fun `tmdb prefix with non-numeric tail is dropped`() {
        assertEquals(WyzieIdHints.EMPTY, WyzieIdHintsParser.parse("tmdb:abc"))
    }

    @Test
    fun `kitsu-prefixed id parses as kitsu hint without imdb or tmdb`() {
        assertEquals(
            WyzieIdHints(kitsu = "42"),
            WyzieIdHintsParser.parse("kitsu:42"),
        )
    }

    @Test
    fun `mal-prefixed id parses as mal hint`() {
        assertEquals(
            WyzieIdHints(mal = "1"),
            WyzieIdHintsParser.parse("mal:1"),
        )
    }

    @Test
    fun `anilist-prefixed id parses as anilist hint`() {
        assertEquals(
            WyzieIdHints(anilist = "5"),
            WyzieIdHintsParser.parse("anilist:5"),
        )
    }

    @Test
    fun `anidb-prefixed id parses as anidb hint`() {
        assertEquals(
            WyzieIdHints(anidb = "9"),
            WyzieIdHintsParser.parse("anidb:9"),
        )
    }

    @Test
    fun `tvdb-prefixed id maps to no Wyzie-usable hint`() {
        // Wyzie has no TVDB lane and cannot route on TVDB ids. Yields empty hints
        // so the provider skips the call rather than guessing.
        assertEquals(WyzieIdHints.EMPTY, WyzieIdHintsParser.parse("tvdb:1234"))
    }

    @Test
    fun `unknown prefix returns empty hints`() {
        assertEquals(WyzieIdHints.EMPTY, WyzieIdHintsParser.parse("trakt:99"))
    }

    @Test
    fun `null contentId returns empty hints`() {
        assertEquals(WyzieIdHints.EMPTY, WyzieIdHintsParser.parse(null))
    }

    @Test
    fun `blank contentId returns empty hints`() {
        assertEquals(WyzieIdHints.EMPTY, WyzieIdHintsParser.parse("   "))
    }

    @Test
    fun `tt prefix is matched case-insensitively for safety`() {
        assertEquals(
            WyzieIdHints(imdb = "tt9999999"),
            WyzieIdHintsParser.parse("TT9999999"),
        )
    }
}
