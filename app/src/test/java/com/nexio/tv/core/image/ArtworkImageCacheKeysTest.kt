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
    fun `native artwork keys are shared across profiles and languages`() {
        assertEquals("id249854_native_background", ArtworkImageCacheKeys.backdrop("id249854"))
        assertEquals("id249854_native_logo", ArtworkImageCacheKeys.logo("id249854"))
        assertEquals("id249854_native_thumbnail", ArtworkImageCacheKeys.thumbnail("id249854"))
    }
}
