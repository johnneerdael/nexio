package com.nexio.tv.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class CatalogRowToRailTest {

    @Test
    fun `toRail copies structural fields verbatim`() {
        val row = sampleRow(
            addonId = "addon1",
            addonName = "Addon One",
            addonBaseUrl = "https://a.example.com",
            catalogId = "cat1",
            catalogName = "Trending"
        )
        val rail = row.toRail()
        assertEquals("addon1", rail.addonId)
        assertEquals("Addon One", rail.addonName)
        assertEquals("https://a.example.com", rail.addonBaseUrl)
        assertEquals("cat1", rail.catalogId)
        assertEquals("Trending", rail.catalogName)
    }

    @Test
    fun `toRail copies pagination + loading flags`() {
        val row = sampleRow(
            isLoading = true,
            hasMore = false,
            currentPage = 3,
            supportsSkip = true,
            skipStep = 50
        )
        val rail = row.toRail()
        assertEquals(true, rail.isLoading)
        assertEquals(false, rail.hasMore)
        assertEquals(3, rail.currentPage)
        assertEquals(true, rail.supportsSkip)
        assertEquals(50, rail.skipStep)
    }

    @Test
    fun `toRail maps items to RailItemKey list`() {
        val row = sampleRow(
            items = listOf(
                samplePreview(id = "tt1", apiType = "movie"),
                samplePreview(id = "tt2", apiType = "movie")
            )
        )
        val rail = row.toRail()
        assertEquals(2, rail.items.size)
        assertEquals(RailItemKey(apiType = "movie", contentId = "tt1"), rail.items[0])
        assertEquals(RailItemKey(apiType = "movie", contentId = "tt2"), rail.items[1])
    }

    @Test
    fun `toRail preserves item order`() {
        val row = sampleRow(
            items = listOf(
                samplePreview(id = "c", apiType = "movie"),
                samplePreview(id = "a", apiType = "movie"),
                samplePreview(id = "b", apiType = "movie")
            )
        )
        val rail = row.toRail()
        assertEquals(listOf("c", "a", "b"), rail.items.map { it.contentId })
    }

    @Test
    fun `toRail apiType matches original`() {
        val row = sampleRow(type = ContentType.SERIES, rawType = "tv")
        val rail = row.toRail()
        assertEquals(row.apiType, rail.apiType)
    }

    private fun sampleRow(
        addonId: String = "a",
        addonName: String = "A",
        addonBaseUrl: String = "https://a",
        catalogId: String = "c",
        catalogName: String = "C",
        type: ContentType = ContentType.MOVIE,
        rawType: String = type.toApiString(),
        items: List<MetaPreview> = emptyList(),
        isLoading: Boolean = false,
        hasMore: Boolean = true,
        currentPage: Int = 0,
        supportsSkip: Boolean = false,
        skipStep: Int = 100
    ): CatalogRow = CatalogRow(
        addonId = addonId,
        addonName = addonName,
        addonBaseUrl = addonBaseUrl,
        catalogId = catalogId,
        catalogName = catalogName,
        type = type,
        rawType = rawType,
        items = items,
        isLoading = isLoading,
        hasMore = hasMore,
        currentPage = currentPage,
        supportsSkip = supportsSkip,
        skipStep = skipStep
    )

    private fun samplePreview(
        id: String,
        apiType: String,
        name: String = "x"
    ): MetaPreview = MetaPreview(
        id = id,
        type = ContentType.fromString(apiType),
        rawType = apiType,
        name = name,
        poster = null,
        posterShape = PosterShape.POSTER,
        background = null,
        logo = null,
        description = null,
        releaseInfo = null,
        imdbRating = null,
        genres = emptyList()
    )
}
