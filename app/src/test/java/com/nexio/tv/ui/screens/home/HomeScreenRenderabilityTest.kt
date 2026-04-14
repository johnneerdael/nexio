package com.nexio.tv.ui.screens.home

import com.nexio.tv.domain.model.CatalogRow
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.HomeLayout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeScreenRenderabilityTest {

    @Test
    fun `modern home treats loading catalog rows as renderable`() {
        val state = HomeUiState(
            homeLayout = HomeLayout.MODERN,
            catalogRows = listOf(loadingRow())
        )

        assertTrue(hasRenderableHomeContent(state))
    }

    @Test
    fun `classic and grid homes do not treat loading only catalog rows as renderable`() {
        val classic = HomeUiState(
            homeLayout = HomeLayout.CLASSIC,
            catalogRows = listOf(loadingRow())
        )
        val grid = HomeUiState(
            homeLayout = HomeLayout.GRID,
            catalogRows = listOf(loadingRow())
        )

        assertFalse(hasRenderableHomeContent(classic))
        assertFalse(hasRenderableHomeContent(grid))
    }

    private fun loadingRow(): CatalogRow {
        return CatalogRow(
            addonId = "simkl",
            addonName = "SIMKL",
            addonBaseUrl = "https://data.simkl.in",
            catalogId = "simkl_tv_trending_today",
            catalogName = "SIMKL Trending TV (Today)",
            type = ContentType.SERIES,
            items = emptyList(),
            isLoading = true
        )
    }
}
