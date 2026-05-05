package com.nexio.tv.data.local

import com.nexio.tv.ui.screens.home.order.HomeRailKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SyntheticHomeCatalogStoreContentOnlyTest {
    @Test
    fun `persisted groups are accessible by HomeRailKey lookup, not by iteration index`() {
        val groups = listOf(
            PersistedSyntheticCatalogGroup(orderKey = "tmdb:popular", rows = emptyList()),
            PersistedSyntheticCatalogGroup(orderKey = "tmdb:top-rated", rows = emptyList()),
        )
        val byKey = groups.associateBy { HomeRailKey(it.orderKey) }
        assertEquals(groups[0], byKey[HomeRailKey("tmdb:popular")])
        assertEquals(groups[1], byKey[HomeRailKey("tmdb:top-rated")])
        assertNull(byKey[HomeRailKey("trakt:popular")])
    }
}
