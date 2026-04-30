package com.nexio.tv.data.catalog.rails

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TraktRailIdCodecTest {

    private val codec = TraktRailIdCodec()

    @Test
    fun `enum exposes exactly the four supported global rail kinds`() {
        val expected = setOf("TRENDING_MOVIES", "TRENDING_SHOWS", "POPULAR_MOVIES", "POPULAR_SHOWS")
        val actual = TraktRailIdCodec.Kind.values().map { it.name }.toSet()
        assertEquals(expected, actual)
    }

    @Test
    fun `each kind exposes its stable token`() {
        assertEquals("trending_movies", TraktRailIdCodec.Kind.TRENDING_MOVIES.token)
        assertEquals("trending_shows", TraktRailIdCodec.Kind.TRENDING_SHOWS.token)
        assertEquals("popular_movies", TraktRailIdCodec.Kind.POPULAR_MOVIES.token)
        assertEquals("popular_shows", TraktRailIdCodec.Kind.POPULAR_SHOWS.token)
    }

    @Test
    fun `encode produces stable trakt-prefixed string per kind`() {
        assertEquals("trakt::trending_movies", codec.encode(TraktRailIdCodec.Kind.TRENDING_MOVIES))
        assertEquals("trakt::trending_shows", codec.encode(TraktRailIdCodec.Kind.TRENDING_SHOWS))
        assertEquals("trakt::popular_movies", codec.encode(TraktRailIdCodec.Kind.POPULAR_MOVIES))
        assertEquals("trakt::popular_shows", codec.encode(TraktRailIdCodec.Kind.POPULAR_SHOWS))
    }

    @Test
    fun `decode round-trips every kind`() {
        TraktRailIdCodec.Kind.values().forEach { kind ->
            val railId = codec.encode(kind)
            assertEquals(kind, codec.decode(railId))
        }
    }

    @Test
    fun `decode returns null for prefix mismatch`() {
        assertNull(codec.decode("addon::trending_movies"))
        assertNull(codec.decode("simkl::trending_movies"))
    }

    @Test
    fun `decode returns null for unknown kind token`() {
        assertNull(codec.decode("trakt::watchlist"))
        assertNull(codec.decode("trakt::"))
    }

    @Test
    fun `decode returns null for blank input`() {
        assertNull(codec.decode(""))
        assertNull(codec.decode("   "))
    }

    @Test
    fun `decode returns null for malformed structure`() {
        assertNull(codec.decode("trakt"))
        assertNull(codec.decode("trakt::trending_movies::extra"))
    }
}
