package com.nexio.tv.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KitsuCatalogSettingsDataStoreTest {
    @Test
    fun `kitsu catalog defaults keep all anime rails disabled`() {
        assertEquals(
            listOf(
                KitsuCatalogIds.TRENDING_ANIME,
                KitsuCatalogIds.HIGHEST_RATED_ANIME,
                KitsuCatalogIds.POPULAR_ANIME,
                KitsuCatalogIds.POPULAR_ACTION_ANIME,
                KitsuCatalogIds.POPULAR_DRAMA_ANIME,
                KitsuCatalogIds.POPULAR_COMEDY_ANIME,
                KitsuCatalogIds.POPULAR_FANTASY_ANIME,
                KitsuCatalogIds.POPULAR_ROMANCE_ANIME,
                KitsuCatalogIds.POPULAR_ADVENTURE_ANIME
            ),
            KitsuCatalogIds.BUILT_IN_ORDER
        )
        assertTrue(KitsuCatalogIds.DEFAULT_ENABLED.isEmpty())
    }

    @Test
    fun `catalog preference sanitizer drops unknown ids and preserves known order`() {
        val prefs = KitsuCatalogPreferences(
            enabledCatalogs = setOf("unknown", KitsuCatalogIds.POPULAR_ANIME),
            catalogOrder = listOf(
                KitsuCatalogIds.POPULAR_ANIME,
                "unknown",
                KitsuCatalogIds.TRENDING_ANIME
            )
        ).sanitized()

        assertEquals(setOf(KitsuCatalogIds.POPULAR_ANIME), prefs.enabledCatalogs)
        assertEquals(KitsuCatalogIds.POPULAR_ANIME, prefs.catalogOrder.first())
        assertEquals(KitsuCatalogIds.TRENDING_ANIME, prefs.catalogOrder[1])
        assertFalse("unknown" in prefs.catalogOrder)
    }
}
