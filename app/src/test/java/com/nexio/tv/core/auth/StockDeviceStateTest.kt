package com.nexio.tv.core.auth

import com.nexio.tv.domain.model.HOME_CATALOG_RAILS_VERSION
import com.nexio.tv.domain.model.HomeCatalogRail
import org.junit.Assert.assertEquals
import org.junit.Test

class StockDeviceStateTest {

    @Test
    fun `stockAccountConfigSyncPayload includes present-but-empty home, trakt, simkl, mdblist, tmdb, kitsu sub-sections`() {
        val payload = stockAccountConfigSyncPayload()

        // Sanity: home/trakt/simkl/mdblist remain present-but-empty.
        assertEquals(emptyList<String>(), payload.catalogs.home?.heroCatalogKeys)
        assertEquals(emptyList<String>(), payload.catalogs.home?.homeCatalogOrderKeys)
        assertEquals(emptyList<String>(), payload.catalogs.home?.disabledHomeCatalogKeys)
        assertEquals(HOME_CATALOG_RAILS_VERSION, payload.catalogs.home?.railsVersion)
        assertEquals(emptyList<HomeCatalogRail>(), payload.catalogs.home?.rails)

        assertEquals(emptyList<String>(), payload.catalogs.trakt?.catalogEnabledSet)
        assertEquals(emptyList<String>(), payload.catalogs.trakt?.catalogOrder)
        assertEquals(emptyList<String>(), payload.catalogs.trakt?.selectedPopularListKeys)

        assertEquals(emptyList<String>(), payload.catalogs.simkl?.catalogEnabledSet)
        assertEquals(emptyList<String>(), payload.catalogs.simkl?.catalogOrder)

        assertEquals(emptyList<String>(), payload.catalogs.mdblist?.hiddenPersonalListKeys)
        assertEquals(emptyList<String>(), payload.catalogs.mdblist?.selectedTopListKeys)
        assertEquals(emptyList<String>(), payload.catalogs.mdblist?.catalogOrder)

        // Regression: tmdb/kitsu must be present-and-empty (not null) so stock reset
        // intentionally clears local TMDB/Kitsu catalog state instead of leaving it
        // untouched (null = "absent, do not change").
        assertEquals(emptyList<String>(), payload.catalogs.tmdb?.catalogEnabledSet)
        assertEquals(emptyList<String>(), payload.catalogs.tmdb?.catalogOrder)
        assertEquals(emptyList<String>(), payload.catalogs.kitsu?.catalogEnabledSet)
        assertEquals(emptyList<String>(), payload.catalogs.kitsu?.catalogOrder)
    }
}
