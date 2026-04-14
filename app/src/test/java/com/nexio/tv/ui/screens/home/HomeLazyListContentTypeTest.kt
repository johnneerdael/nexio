package com.nexio.tv.ui.screens.home

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeLazyListContentTypeTest {
    @Test
    fun `classic catalog row content type uses row api type`() {
        val row = TestHomeRows.catalogRow(apiType = "anime")

        assertEquals("anime", catalogRowContentType(row))
    }

    @Test
    fun `modern home row content type uses row api type with fallback`() {
        assertEquals("movie", modernHomeRowContentType(TestHomeRows.heroRow(apiType = "movie")))
        assertEquals("modern_home_row", modernHomeRowContentType(TestHomeRows.heroRow(apiType = null)))
    }

    @Test
    fun `modern row item content type uses catalog item type`() {
        val item = TestHomeRows.modernCatalogItem(itemType = "series")

        assertEquals("series", modernRowItemContentType(item))
    }
}
