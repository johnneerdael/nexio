package com.nexio.tv.data.local

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class TmdbCatalogSettingsDataStoreTest {
    @Test
    fun `tmdb catalog defaults enable trending and latest release catalogs only`() {
        assertEquals(
            listOf(
                TmdbCatalogIds.TRENDING_MOVIES,
                TmdbCatalogIds.TRENDING_SERIES,
                TmdbCatalogIds.LATEST_RELEASES_MOVIES,
                TmdbCatalogIds.LATEST_RELEASES_SERIES,
                TmdbCatalogIds.POPULAR_MOVIES,
                TmdbCatalogIds.POPULAR_SERIES,
                TmdbCatalogIds.YEAR_MOVIES,
                TmdbCatalogIds.YEAR_SERIES,
                TmdbCatalogIds.LANGUAGE_MOVIES,
                TmdbCatalogIds.LANGUAGE_SERIES
            ),
            TmdbCatalogIds.BUILT_IN_ORDER
        )
        assertEquals(
            setOf(
                TmdbCatalogIds.TRENDING_MOVIES,
                TmdbCatalogIds.TRENDING_SERIES,
                TmdbCatalogIds.LATEST_RELEASES_MOVIES,
                TmdbCatalogIds.LATEST_RELEASES_SERIES
            ),
            TmdbCatalogIds.DEFAULT_ENABLED
        )
    }

    @Test
    fun `catalog preference sanitizer drops unknown ids and preserves known order`() {
        val prefs = TmdbCatalogPreferences(
            enabledCatalogs = setOf("unknown", TmdbCatalogIds.POPULAR_SERIES),
            catalogOrder = listOf(
                TmdbCatalogIds.POPULAR_SERIES,
                "unknown",
                TmdbCatalogIds.TRENDING_MOVIES
            ),
            includeAdult = true,
            hideUnreleasedDigital = false
        ).sanitized()

        assertEquals(setOf(TmdbCatalogIds.POPULAR_SERIES), prefs.enabledCatalogs)
        assertEquals(TmdbCatalogIds.POPULAR_SERIES, prefs.catalogOrder.first())
        assertEquals(TmdbCatalogIds.TRENDING_MOVIES, prefs.catalogOrder[1])
        assertTrue(prefs.includeAdult)
        assertFalse(prefs.hideUnreleasedDigital)
    }
}
