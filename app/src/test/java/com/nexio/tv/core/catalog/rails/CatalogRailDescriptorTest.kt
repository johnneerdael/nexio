package com.nexio.tv.core.catalog.rails

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CatalogRailDescriptorTest {

    @Test
    fun `valid descriptor stores its fields verbatim`() {
        val d = CatalogRailDescriptor(
            railId = "trakt:trending:movies",
            providerId = CatalogRailProvider.TRAKT,
            displayTitle = "Trending Movies",
            sortOrder = 5
        )
        assertEquals("trakt:trending:movies", d.railId)
        assertEquals(CatalogRailProvider.TRAKT, d.providerId)
        assertEquals("Trending Movies", d.displayTitle)
        assertEquals(5, d.sortOrder)
    }

    @Test
    fun `blank railId is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            CatalogRailDescriptor(
                railId = "",
                providerId = CatalogRailProvider.TRAKT,
                displayTitle = "x",
                sortOrder = 0
            )
        }
    }

    @Test
    fun `blank displayTitle is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            CatalogRailDescriptor(
                railId = "x",
                providerId = CatalogRailProvider.TRAKT,
                displayTitle = "",
                sortOrder = 0
            )
        }
    }

    @Test
    fun `negative sortOrder is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            CatalogRailDescriptor(
                railId = "x",
                providerId = CatalogRailProvider.TRAKT,
                displayTitle = "x",
                sortOrder = -1
            )
        }
    }
}
