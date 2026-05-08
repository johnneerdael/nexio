package com.nexio.tv.core.metadata.router

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins MetadataIdParser parsing for typed provider ids (`tmdb:tv:<id>`,
 * `tmdb:movie:<id>`). Prior to the centralized parser, these were silently
 * collapsed to value="tv" / value="movie" which broke TMDB tv id propagation
 * into TVDB identity bridging.
 */
class MetadataIdParserTypedTmdbTest {

    @Test
    fun `tmdb tv content id parses to numeric tmdb id value`() {
        val parsed = MetadataIdParser.parse("tmdb:tv:1399")
        assertEquals(AnimeIdScheme.TMDB, parsed.scheme)
        assertEquals("1399", parsed.value)
        assertEquals("tmdb:tv:1399", parsed.raw)
    }

    @Test
    fun `tmdb movie content id parses to numeric tmdb id value`() {
        val parsed = MetadataIdParser.parse("tmdb:movie:550")
        assertEquals(AnimeIdScheme.TMDB, parsed.scheme)
        assertEquals("550", parsed.value)
        assertEquals("tmdb:movie:550", parsed.raw)
    }

    @Test
    fun `untyped tmdb content id still parses to id value`() {
        val parsed = MetadataIdParser.parse("tmdb:1399")
        assertEquals(AnimeIdScheme.TMDB, parsed.scheme)
        assertEquals("1399", parsed.value)
    }

    @Test
    fun `tvdb typed tv content id parses to numeric value`() {
        val parsed = MetadataIdParser.parse("tvdb:tv:81189")
        assertEquals(AnimeIdScheme.TVDB, parsed.scheme)
        assertEquals("81189", parsed.value)
    }

    @Test
    fun `parser falls back to unknown for typed tmdb shape with empty payload`() {
        val parsed = MetadataIdParser.parse("tmdb:tv:")
        assertEquals(AnimeIdScheme.UNKNOWN, parsed.scheme)
        assertEquals("tmdb:tv:", parsed.raw)
    }

    @Test
    fun `parser falls back to unknown for typed tmdb movie shape with empty payload`() {
        val parsed = MetadataIdParser.parse("tmdb:movie:")
        assertEquals(AnimeIdScheme.UNKNOWN, parsed.scheme)
    }

    @Test
    fun `parser falls back to unknown for empty middle segment`() {
        val parsed = MetadataIdParser.parse("tmdb::1399")
        assertEquals(AnimeIdScheme.UNKNOWN, parsed.scheme)
    }
}
