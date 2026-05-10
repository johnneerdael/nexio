package com.nexio.tv.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class RailItemKeyTest {

    @Test
    fun `key derives from apiType and contentId via homeDisplayItemKey`() {
        val key = RailItemKey(apiType = "movie", contentId = "tt0111161")
        assertEquals(homeDisplayItemKey("movie", "tt0111161"), key.key)
    }

    @Test
    fun `equality uses apiType + contentId`() {
        val a = RailItemKey(apiType = "movie", contentId = "tt1")
        val b = RailItemKey(apiType = "movie", contentId = "tt1")
        val c = RailItemKey(apiType = "series", contentId = "tt1")
        val d = RailItemKey(apiType = "movie", contentId = "tt2")
        assertEquals(a, b)
        assertNotEquals(a, c)
        assertNotEquals(a, d)
    }

    @Test
    fun `hashCode is stable across instances with equal fields`() {
        val a = RailItemKey(apiType = "movie", contentId = "tt1")
        val b = RailItemKey(apiType = "movie", contentId = "tt1")
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `copy preserves field semantics`() {
        val original = RailItemKey(apiType = "movie", contentId = "tt1")
        val updated = original.copy(contentId = "tt2")
        assertEquals("movie", updated.apiType)
        assertEquals("tt2", updated.contentId)
        assertEquals(homeDisplayItemKey("movie", "tt2"), updated.key)
    }
}
