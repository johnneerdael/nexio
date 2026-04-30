package com.nexio.tv.core.catalog.rails

import org.junit.Assert.assertEquals
import org.junit.Test

class CatalogRailProviderTest {

    @Test
    fun `enum has exactly the six known providers`() {
        val expected = setOf("TRAKT", "MDBLIST", "SIMKL", "TMDB_BUILTIN", "KITSU_BUILTIN", "ADDON")
        val actual = CatalogRailProvider.values().map { it.name }.toSet()
        assertEquals(expected, actual)
    }

    @Test
    fun `each provider exposes its stable identifier string`() {
        assertEquals("trakt", CatalogRailProvider.TRAKT.id)
        assertEquals("mdblist", CatalogRailProvider.MDBLIST.id)
        assertEquals("simkl", CatalogRailProvider.SIMKL.id)
        assertEquals("tmdb-builtin", CatalogRailProvider.TMDB_BUILTIN.id)
        assertEquals("kitsu-builtin", CatalogRailProvider.KITSU_BUILTIN.id)
        assertEquals("addon", CatalogRailProvider.ADDON.id)
    }
}
