package com.nexio.tv.ui.screens.home

import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

class ResolvedDisplayProjectionCacheRailsListTest {

    private fun rail(id: String): ResolvedRailRow =
        ResolvedRailRow(catalogId = id, title = id.uppercase(), items = emptyList())

    @Test
    fun `internRailsList returns same instance when rails are reference-equal`() {
        val cache = ResolvedDisplayProjectionCache()
        val a = rail("a")
        val b = rail("b")
        val first = listOf(a, b)
        val firstInterned = cache.internRailsList(first)
        // Different list instance, same elements (reference-identical).
        val second = listOf(a, b)
        val secondInterned = cache.internRailsList(second)
        assertSame(firstInterned, secondInterned)
    }

    @Test
    fun `internRailsList returns new instance when an element ref differs`() {
        val cache = ResolvedDisplayProjectionCache()
        // a1 and a2 are value-equal but reference-different.
        val a1 = rail("a")
        val a2 = rail("a")
        val first = cache.internRailsList(listOf(a1))
        val second = cache.internRailsList(listOf(a2))
        assertNotSame(first, second)
    }

    @Test
    fun `internRailsList returns new instance when size differs`() {
        val cache = ResolvedDisplayProjectionCache()
        val a = rail("a")
        val b = rail("b")
        val first = cache.internRailsList(listOf(a))
        val second = cache.internRailsList(listOf(a, b))
        assertNotSame(first, second)
    }

    @Test
    fun `internRailsList caches the latest list for subsequent comparisons`() {
        val cache = ResolvedDisplayProjectionCache()
        val a = rail("a")
        val b = rail("b")
        val c = rail("c")
        // Seed with [a, b]
        val first = cache.internRailsList(listOf(a, b))
        // Switch to [a, c] — different content, returns the new list.
        val second = cache.internRailsList(listOf(a, c))
        assertNotSame(first, second)
        // Now [a, c] with the same refs should return the prior `second` instance.
        val third = cache.internRailsList(listOf(a, c))
        assertSame(second, third)
    }
}
