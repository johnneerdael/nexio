package com.nexio.tv.ui.screens.home

import com.nexio.tv.data.local.KitsuCatalogIds
import com.nexio.tv.data.local.KitsuCatalogPreferences
import com.nexio.tv.data.local.TmdbCatalogIds
import com.nexio.tv.data.local.TmdbCatalogPreferences
import com.nexio.tv.domain.model.HomeCatalogRail
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeCatalogRailProviderPreferencesTest {
    @Test
    fun `tmdb home rails extend effective provider preferences for home rendering`() {
        val effective = TmdbCatalogPreferences(enabledCatalogs = emptySet())
            .includingHomeCatalogRails(listOf(rail(TmdbCatalogIds.POPULAR_MOVIES)))

        assertTrue(effective.enabledCatalogIds().contains(TmdbCatalogIds.POPULAR_MOVIES))
    }

    @Test
    fun `kitsu home rails extend effective provider preferences for home rendering`() {
        val effective = KitsuCatalogPreferences(enabledCatalogs = emptySet())
            .includingHomeCatalogRails(listOf(rail(KitsuCatalogIds.TRENDING_ANIME)))

        assertTrue(effective.enabledCatalogIds().contains(KitsuCatalogIds.TRENDING_ANIME))
    }

    private fun rail(key: String) = HomeCatalogRail(
        key = key,
        family = "",
        source = "",
        title = "",
        enabled = true
    )
}
