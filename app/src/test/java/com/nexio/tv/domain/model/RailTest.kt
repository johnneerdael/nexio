package com.nexio.tv.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class RailTest {

    @Test
    fun `apiType derives from ContentType and rawType`() {
        val rail = sampleRail(type = ContentType.MOVIE, rawType = "movie")
        assertEquals(ContentType.MOVIE.toApiString("movie"), rail.apiType)
    }

    @Test
    fun `apiType uses rawType passthrough when ContentType yields it`() {
        val rail = sampleRail(type = ContentType.SERIES, rawType = "tv")
        assertEquals(ContentType.SERIES.toApiString("tv"), rail.apiType)
    }

    @Test
    fun `equality uses all structural fields`() {
        val a = sampleRail(catalogId = "cat1")
        val b = sampleRail(catalogId = "cat1")
        val c = sampleRail(catalogId = "cat2")
        assertEquals(a, b)
        assertNotEquals(a, c)
    }

    @Test
    fun `items is List of RailItemKey`() {
        val keys = listOf(
            RailItemKey(apiType = "movie", contentId = "tt1"),
            RailItemKey(apiType = "movie", contentId = "tt2")
        )
        val rail = sampleRail(items = keys)
        assertEquals(2, rail.items.size)
        assertEquals(keys, rail.items)
    }

    @Test
    fun `default flags mirror CatalogRow defaults`() {
        val rail = sampleRail()
        assertEquals(false, rail.isLoading)
        assertEquals(true, rail.hasMore)
        assertEquals(0, rail.currentPage)
        assertEquals(false, rail.supportsSkip)
        assertEquals(100, rail.skipStep)
    }

    private fun sampleRail(
        addonId: String = "addon1",
        addonName: String = "Addon",
        addonBaseUrl: String = "https://addon.example.com",
        catalogId: String = "cat1",
        catalogName: String = "Trending",
        type: ContentType = ContentType.MOVIE,
        rawType: String = type.toApiString(),
        items: List<RailItemKey> = emptyList()
    ): Rail = Rail(
        addonId = addonId,
        addonName = addonName,
        addonBaseUrl = addonBaseUrl,
        catalogId = catalogId,
        catalogName = catalogName,
        type = type,
        rawType = rawType,
        items = items
    )
}
