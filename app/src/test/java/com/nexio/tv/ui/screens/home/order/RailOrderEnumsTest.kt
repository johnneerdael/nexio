package com.nexio.tv.ui.screens.home.order

import org.junit.Assert.assertEquals
import org.junit.Test

class RailOrderEnumsTest {
    @Test
    fun `RailFamily ranks define the canonical family order`() {
        assertEquals(0, RailFamily.TRAKT.familyRank)
        assertEquals(1, RailFamily.SIMKL.familyRank)
        assertEquals(2, RailFamily.MDBLIST.familyRank)
        assertEquals(3, RailFamily.TMDB.familyRank)
        assertEquals(4, RailFamily.KITSU.familyRank)
        assertEquals(5, RailFamily.ADDON.familyRank)
    }

    @Test
    fun `RailOrderMutationSource includes MIGRATION_SYNTHETIC_FALLBACK`() {
        // Asserts the enum contains the synthetic-fallback variant required by the migration spec.
        val sources = RailOrderMutationSource.values().map { it.name }
        assertEquals(true, sources.contains("MIGRATION_SYNTHETIC_FALLBACK"))
    }
}
