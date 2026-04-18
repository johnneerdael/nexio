package com.nexio.tv.core.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ArtworkImageCacheKeysTest {

    @Test
    fun `poster keys include item provider and type but not locale or profile`() {
        val key = ArtworkImageCacheKeys.poster("id249854", "topposters")

        assertEquals("id249854_topposters_poster", key)
        assertFalse(key.contains("en", ignoreCase = true))
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

        assertEquals("tt15940132_rpdb_poster", key)
    }

    @Test
    fun `poster keys infer top posters provider from poster url when tag is missing`() {
        val key = ArtworkImageCacheKeys.poster(
            itemId = "tt15940132",
            providerTag = null,
            posterUrl = "https://api.top-posters.com/key/imdb/poster/tt15940132.jpg"
        )

        assertEquals("tt15940132_top_posters_poster", key)
    }

    @Test
    fun `native artwork keys are shared across profiles and languages`() {
        assertEquals("id249854_native_background", ArtworkImageCacheKeys.backdrop("id249854"))
        assertEquals("id249854_native_logo", ArtworkImageCacheKeys.logo("id249854"))
        assertEquals("id249854_native_thumbnail", ArtworkImageCacheKeys.thumbnail("id249854"))
    }
}
