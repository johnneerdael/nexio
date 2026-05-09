package com.nexio.tv.ui.screens.home

import com.nexio.tv.domain.model.HydratedHomeOverlay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class OverlayObserverStalePreservationTest {
    @Test
    fun `empty new map preserves prior overlays`() {
        val prior = mapOf("k1" to fakeOverlay("k1"))
        val merged = preserveStaleOverlays(prior, emptyMap())
        assertEquals(prior, merged)
    }

    @Test
    fun `non-empty new map replaces prior entries for same keys`() {
        val priorOverlay = fakeOverlay("k1", updatedAt = 100L)
        val freshOverlay = fakeOverlay("k1", updatedAt = 200L)
        val merged = preserveStaleOverlays(mapOf("k1" to priorOverlay), mapOf("k1" to freshOverlay))
        assertSame(freshOverlay, merged["k1"])
    }

    @Test
    fun `non-empty new map for different keys keeps both prior and fresh`() {
        val merged = preserveStaleOverlays(
            previous = mapOf("k1" to fakeOverlay("k1")),
            next = mapOf("k2" to fakeOverlay("k2"))
        )
        assertEquals(setOf("k1", "k2"), merged.keys)
    }

    private fun fakeOverlay(key: String, updatedAt: Long = 0L): HydratedHomeOverlay =
        com.nexio.tv.ui.screens.home.testutil.HydratedHomeOverlayFixtures.minimal(itemKey = key, updatedAtMs = updatedAt)
}
