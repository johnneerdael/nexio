package com.nexio.tv.core.metadata.router

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Direct coverage for [providerNativeIdFromContentId]. Pinned shapes:
 *  - typed tv:    `tmdb:tv:<id>`    → id
 *  - typed movie: `tmdb:movie:<id>` → id
 *  - untyped:     `tmdb:<id>`       → id
 *  - mismatched provider, blank input, missing payload → null
 */
class MetadataRouterContentIdParserTest {

    @Test
    fun `provider native id parser extracts numeric tmdb tv id`() {
        assertEquals("1399", providerNativeIdFromContentId("tmdb:tv:1399", "tmdb"))
        assertEquals("550", providerNativeIdFromContentId("tmdb:movie:550", "tmdb"))
        assertEquals("1399", providerNativeIdFromContentId("tmdb:1399", "tmdb"))
    }

    @Test
    fun `provider native id parser is case insensitive on scheme and middle segment`() {
        assertEquals("1399", providerNativeIdFromContentId("TMDB:TV:1399", "tmdb"))
        assertEquals("550", providerNativeIdFromContentId("Tmdb:Movie:550", "tmdb"))
        assertEquals("1399", providerNativeIdFromContentId("tmdb:1399", "TMDB"))
    }

    @Test
    fun `provider native id parser returns null for mismatched provider`() {
        assertNull(providerNativeIdFromContentId("imdb:tt1", "tmdb"))
        assertNull(providerNativeIdFromContentId("tvdb:81189", "tmdb"))
    }

    @Test
    fun `provider native id parser returns null for blank or empty`() {
        assertNull(providerNativeIdFromContentId("", "tmdb"))
        assertNull(providerNativeIdFromContentId("   ", "tmdb"))
    }

    @Test
    fun `provider native id parser returns null when only provider prefix present`() {
        assertNull(providerNativeIdFromContentId("tmdb", "tmdb"))
        assertNull(providerNativeIdFromContentId("tmdb:", "tmdb"))
    }

    @Test
    fun `provider native id parser preserves substring as-found for imdb tt prefix`() {
        // The helper does not normalize case; callers must validate canonical shape.
        assertEquals("tt0133093", providerNativeIdFromContentId("imdb:tt0133093", "imdb"))
        assertEquals("TT0133093", providerNativeIdFromContentId("imdb:TT0133093", "imdb"))
    }

    @Test
    fun `provider native id parser rejects trailing colon empty payload`() {
        assertNull(providerNativeIdFromContentId("tmdb:tv:", "tmdb"))
        assertNull(providerNativeIdFromContentId("tmdb:movie:", "tmdb"))
        assertNull(providerNativeIdFromContentId("tmdb:", "tmdb"))
    }

    @Test
    fun `provider native id parser rejects empty middle segment`() {
        assertNull(providerNativeIdFromContentId("tmdb::1399", "tmdb"))
    }
}
