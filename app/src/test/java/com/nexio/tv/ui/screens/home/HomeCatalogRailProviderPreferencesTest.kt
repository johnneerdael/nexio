package com.nexio.tv.ui.screens.home

import com.nexio.tv.data.local.KitsuCatalogIds
import com.nexio.tv.data.local.KitsuCatalogPreferences
import com.nexio.tv.data.local.TmdbCatalogIds
import com.nexio.tv.data.local.TmdbCatalogPreferences
import com.nexio.tv.domain.model.HomeCatalogRail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeCatalogRailProviderPreferencesTest {
    @Test
    fun `tmdb home rails define effective provider preferences for home rendering`() {
        val effective = TmdbCatalogPreferences(enabledCatalogs = setOf(TmdbCatalogIds.POPULAR_SERIES))
            .includingHomeCatalogRails(listOf(rail(TmdbCatalogIds.POPULAR_MOVIES)))

        assertEquals(listOf(TmdbCatalogIds.POPULAR_MOVIES), effective.enabledCatalogIds().toList())
        assertFalse(effective.enabledCatalogIds().contains(TmdbCatalogIds.POPULAR_SERIES))
    }

    @Test
    fun `kitsu home rails define effective provider preferences for home rendering`() {
        val effective = KitsuCatalogPreferences(enabledCatalogs = setOf(KitsuCatalogIds.POPULAR_ANIME))
            .includingHomeCatalogRails(listOf(rail(KitsuCatalogIds.TRENDING_ANIME)))

        assertEquals(listOf(KitsuCatalogIds.TRENDING_ANIME), effective.enabledCatalogIds().toList())
        assertFalse(effective.enabledCatalogIds().contains(KitsuCatalogIds.POPULAR_ANIME))
    }

    @Test
    fun `provider preferences remain effective when no explicit home rails are configured`() {
        val effective = KitsuCatalogPreferences(enabledCatalogs = setOf(KitsuCatalogIds.POPULAR_ANIME))
            .includingHomeCatalogRails(emptyList())

        assertTrue(effective.enabledCatalogIds().contains(KitsuCatalogIds.POPULAR_ANIME))
    }

    private fun rail(key: String) = HomeCatalogRail(
        key = key,
        family = "",
        source = "",
        title = "",
        enabled = true
    )
}
