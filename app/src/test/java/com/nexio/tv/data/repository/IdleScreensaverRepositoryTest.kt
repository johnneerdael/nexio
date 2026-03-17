package com.nexio.tv.data.repository

import com.nexio.tv.domain.model.Addon
import com.nexio.tv.domain.model.AddonResource
import com.nexio.tv.domain.model.CatalogDescriptor
import com.nexio.tv.domain.model.CatalogRow
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.PosterShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IdleScreensaverRepositoryTest {
    @Test
    fun `findStockCinemetaPopularCatalogRequest matches hidden stock cinemeta popular catalog by type`() {
        val addons = listOf(
            buildAddon(
                baseUrl = "https://v3-cinemeta.strem.io",
                catalogs = listOf(
                    CatalogDescriptor(type = ContentType.MOVIE, id = "top", name = "Top"),
                    CatalogDescriptor(type = ContentType.MOVIE, id = "popular", name = "Popular"),
                    CatalogDescriptor(type = ContentType.SERIES, id = "popularSeries", name = "Popular")
                )
            )
        )

        val movie = findStockCinemetaPopularCatalogRequest(addons, ContentType.MOVIE)
        val series = findStockCinemetaPopularCatalogRequest(addons, ContentType.SERIES)

        requireNotNull(movie)
        requireNotNull(series)
        assertEquals("popular", movie.catalogId)
        assertEquals("popularSeries", series.catalogId)
    }

    @Test
    fun `findStockCinemetaPopularCatalogRequest ignores non cinemeta addons`() {
        val addons = listOf(
            buildAddon(
                baseUrl = "https://example.com",
                catalogs = listOf(
                    CatalogDescriptor(type = ContentType.MOVIE, id = "popular", name = "Popular")
                )
            )
        )

        assertNull(findStockCinemetaPopularCatalogRequest(addons, ContentType.MOVIE))
    }

    @Test
    fun `buildIdleScreensaverSlides keeps top five per row and drops items without artwork`() {
        val movieRow = buildRow(
            addonBaseUrl = "https://v3-cinemeta.strem.io",
            type = ContentType.MOVIE,
            items = (1..6).map { index ->
                buildPreview(
                    id = "movie-$index",
                    type = ContentType.MOVIE,
                    background = if (index == 6) null else "https://image/$index.jpg",
                    poster = if (index == 6) null else "https://poster/$index.jpg"
                )
            }
        )
        val seriesRow = buildRow(
            addonBaseUrl = "https://v3-cinemeta.strem.io",
            type = ContentType.SERIES,
            items = listOf(
                buildPreview(id = "series-1", type = ContentType.SERIES, background = "https://image/s1.jpg")
            )
        )

        val slides = buildIdleScreensaverSlides(listOf(movieRow, seriesRow))

        assertEquals(6, slides.size)
        assertTrue(slides.none { it.itemId == "movie-6" })
        assertEquals(
            listOf("movie-1", "movie-2", "movie-3", "movie-4", "movie-5", "series-1"),
            slides.map { it.itemId }
        )
    }

    private fun buildAddon(
        baseUrl: String,
        catalogs: List<CatalogDescriptor>
    ): Addon {
        return Addon(
            id = "cinemeta",
            name = "Cinemeta",
            version = "1.0.0",
            description = null,
            logo = null,
            baseUrl = baseUrl,
            catalogs = catalogs,
            types = listOf(ContentType.MOVIE, ContentType.SERIES),
            resources = listOf(AddonResource(name = "catalog", types = listOf("movie", "series"), idPrefixes = null))
        )
    }

    private fun buildRow(
        addonBaseUrl: String,
        type: ContentType,
        items: List<MetaPreview>
    ): CatalogRow {
        return CatalogRow(
            addonId = "cinemeta",
            addonName = "Cinemeta",
            addonBaseUrl = addonBaseUrl,
            catalogId = "popular",
            catalogName = "Popular",
            type = type,
            items = items
        )
    }

    private fun buildPreview(
        id: String,
        type: ContentType,
        background: String?,
        poster: String? = background
    ): MetaPreview {
        return MetaPreview(
            id = id,
            type = type,
            name = id,
            poster = poster,
            posterShape = PosterShape.POSTER,
            background = background,
            logo = null,
            description = "Description for $id",
            releaseInfo = "2024",
            runtime = "110",
            imdbRating = 7.4f,
            genres = emptyList()
        )
    }
}
