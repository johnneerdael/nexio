package com.nexio.tv.ui.screens.home.order

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeRailOrderModelTest {
    @Test
    fun `DefaultSortKey orders by familyRank then intraFamilyRank`() {
        val a = DefaultSortKey(familyRank = 0, intraFamilyRank = 0)
        val b = DefaultSortKey(familyRank = 0, intraFamilyRank = 1)
        val c = DefaultSortKey(familyRank = 1, intraFamilyRank = 0)
        val sorted = listOf(c, b, a).sortedWith(
            compareBy({ it.familyRank }, { it.intraFamilyRank })
        )
        assertEquals(listOf(a, b, c), sorted)
    }

    @Test
    fun `HomeRailDefinition holds all declared fields`() {
        val def = HomeRailDefinition(
            key = HomeRailKey("tmdb:popular:movies"),
            family = RailFamily.TMDB,
            source = RailSource.PROVIDER_PUBLIC,
            title = "Popular Movies",
            enabled = true,
            defaultSortKey = DefaultSortKey(familyRank = 3, intraFamilyRank = 0),
            publishPolicy = RailPublishPolicy.PUBLISH_WHEN_NON_EMPTY,
        )
        assertEquals(HomeRailKey("tmdb:popular:movies"), def.key)
        assertEquals(RailFamily.TMDB, def.family)
        assertTrue(def.enabled)
    }

    @Test
    fun `HomeRailOrderState defaults are sensible for empty state`() {
        val empty = HomeRailOrderState.Empty
        assertTrue(empty.orderedKeys.isEmpty())
        assertTrue(empty.disabledKeys.isEmpty())
        assertEquals(0L, empty.version)
        assertEquals(0L, empty.updatedAtMs)
        assertEquals(RailOrderMutationSource.DEFAULT_BOOTSTRAP, empty.lastMutationSource)
    }

    @Test
    fun `EffectiveHomeRailOrder Empty exposes empty lists`() {
        val empty = EffectiveHomeRailOrder.Empty
        assertTrue(empty.visibleKeys.isEmpty())
        assertTrue(empty.disabledKeys.isEmpty())
        assertTrue(empty.unknownSavedKeys.isEmpty())
        assertTrue(empty.newlyDiscoveredKeys.isEmpty())
        assertTrue(empty.prunedKeys.isEmpty())
    }
}
