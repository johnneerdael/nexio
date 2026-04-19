package com.nexio.tv.core.anime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AnimeStremioIdTest {
    @Test
    fun `parses supported anime lookup prefixes`() {
        assertEquals(AnimeIdSource.KITSU to "12", AnimeStremioId.parse("kitsu:12")?.let { it.source to it.value })
        assertEquals(AnimeIdSource.MAL to "5114", AnimeStremioId.parse("mal:5114")?.let { it.source to it.value })
        assertEquals(AnimeIdSource.ANILIST to "21", AnimeStremioId.parse("anilist:21")?.let { it.source to it.value })
        assertEquals(AnimeIdSource.ANIDB to "69", AnimeStremioId.parse("anidb:69")?.let { it.source to it.value })
        assertEquals(AnimeIdSource.TMDB to "31911", AnimeStremioId.parse("tmdb:31911")?.let { it.source to it.value })
        assertEquals(AnimeIdSource.TVDB to "85249", AnimeStremioId.parse("tvdb:85249")?.let { it.source to it.value })
        assertEquals(AnimeIdSource.IMDB to "tt1355642", AnimeStremioId.parse("tt1355642:1:1")?.let { it.source to it.value })
        assertEquals(AnimeIdSource.IMDB to "tt1355642", AnimeStremioId.parse("imdb:tt1355642")?.let { it.source to it.value })
    }

    @Test
    fun `rejects unsupported prefixes`() {
        assertNull(AnimeStremioId.parse("trakt:123"))
        assertNull(AnimeStremioId.parse("simkl:123"))
        assertNull(AnimeStremioId.parse(""))
    }
}
