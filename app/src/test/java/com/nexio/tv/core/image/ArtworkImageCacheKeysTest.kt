package com.nexio.tv.core.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ArtworkImageCacheKeysTest {

    @Test
    fun `poster keys include item provider and type but not locale or profile`() {
        val key = ArtworkImageCacheKeys.poster("id249854", "topposters")

        assertEquals("artwork:topposters:poster_v2:id249854:imageLang:en:policy:1", key)
        assertFalse(key.contains("nl", ignoreCase = true))
        assertFalse(key.contains("profile", ignoreCase = true))
    }

    @Test
    fun `poster keys infer rpdb provider from poster url when tag is missing`() {
        val key = ArtworkImageCacheKeys.poster(
            itemId = "tt15940132",
            providerTag = null,
            posterUrl = "https://api.ratingposterdb.com/key/imdb/poster-default/tt15940132.jpg"
        )

        assertEquals("artwork:rpdb:poster_v2:tt15940132:imageLang:en:policy:1", key)
    }

    @Test
    fun `poster keys infer top posters provider from poster url when tag is missing`() {
        val key = ArtworkImageCacheKeys.poster(
            itemId = "tt15940132",
            providerTag = null,
            posterUrl = "https://api.top-posters.com/key/imdb/poster/tt15940132.jpg"
        )

        assertEquals("artwork:top_posters:poster_v2:tt15940132:imageLang:en:policy:1", key)
    }

    @Test
    fun `native artwork keys are shared across profiles and languages`() {
        assertEquals("artwork:native:background:id249854:imageLang:en:policy:1", ArtworkImageCacheKeys.backdrop("id249854"))
        assertEquals("artwork:native:logo:id249854:imageLang:en:policy:1", ArtworkImageCacheKeys.logo("id249854"))
        assertEquals("artwork:native:thumbnail:id249854:imageLang:en:policy:1", ArtworkImageCacheKeys.thumbnail("id249854"))
    }

    @Test
    fun `poster key uses english image language`() {
        val key = ArtworkImageCacheKeys.poster(
            itemId = "tmdb:550",
            providerTag = "tmdb"
        )

        assertEquals("artwork:tmdb:poster_v2:tmdb:550:imageLang:en:policy:1", key)
        assertFalse(key.contains("lang:nl"))
    }
}
