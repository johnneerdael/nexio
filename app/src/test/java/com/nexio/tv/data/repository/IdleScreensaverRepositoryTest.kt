package com.nexio.tv.data.repository

import com.nexio.tv.domain.model.Addon
import com.nexio.tv.domain.model.AddonResource
import com.nexio.tv.domain.model.CatalogDescriptor
import com.nexio.tv.domain.model.CatalogRow
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.PosterShape
import com.nexio.tv.data.local.TraktCatalogIds
import com.nexio.tv.data.local.TraktCatalogPreferences
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `buildIdleScreensaverSlides keeps top five per row and drops items without artwork`() = runBlocking {
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
        assertEquals(94.0, slides.first().tomatoesRating ?: 0.0, 0.0)
        assertEquals(
            listOf("movie-1", "movie-2", "movie-3", "movie-4", "movie-5", "series-1"),
            slides.map { it.itemId }
        )
    }

    @Test
    fun `buildIdleScreensaverSlides applies preview enrichment before mapping slides`() = runBlocking {
        val row = buildRow(
            addonBaseUrl = "https://v3-cinemeta.strem.io",
            type = ContentType.MOVIE,
            items = listOf(buildPreview(id = "movie-1", type = ContentType.MOVIE, background = "https://image/1.jpg"))
        )

        val slides = buildIdleScreensaverSlides(listOf(row)) { preview ->
            preview.copy(tomatoesRating = 88.0)
        }

        assertEquals(1, slides.size)
        assertEquals(88.0, slides.single().tomatoesRating ?: 0.0, 0.0)
    }

    @Test
    fun `buildIdleScreensaverSlides honors a custom per-row limit for trakt-backed rotations`() = runBlocking {
        val movieRow = buildRow(
            addonBaseUrl = "https://api.trakt.tv",
            type = ContentType.MOVIE,
            items = (1..12).map { index ->
                buildPreview("movie-$index", ContentType.MOVIE, "https://image/movie-$index.jpg")
            }
        )
        val showRow = buildRow(
            addonBaseUrl = "https://api.trakt.tv",
            type = ContentType.SERIES,
            items = (1..11).map { index ->
                buildPreview("show-$index", ContentType.SERIES, "https://image/show-$index.jpg")
            }
        )

        val slides = buildIdleScreensaverSlides(
            rows = listOf(movieRow, showRow),
            itemsPerRowLimit = 10
        )

        assertEquals(20, slides.size)
        assertEquals("movie-10", slides[9].itemId)
        assertEquals("show-10", slides.last().itemId)
    }

    @Test
    fun `shouldUseTraktScreensaverSource requires auth both trending rails and populated snapshot`() {
        val prefs = TraktCatalogPreferences(
            enabledCatalogs = setOf(TraktCatalogIds.TRENDING_MOVIES, TraktCatalogIds.TRENDING_SHOWS)
        )
        val snapshot = TraktDiscoverySnapshot(
            trendingMovieItems = listOf(buildPreview("movie-1", ContentType.MOVIE, "https://image/m1.jpg")),
            trendingShowItems = listOf(buildPreview("show-1", ContentType.SERIES, "https://image/s1.jpg"))
        )

        assertTrue(shouldUseTraktScreensaverSource(true, prefs, snapshot))
        assertFalse(shouldUseTraktScreensaverSource(false, prefs, snapshot))
        assertFalse(
            shouldUseTraktScreensaverSource(
                true,
                prefs.copy(enabledCatalogs = setOf(TraktCatalogIds.TRENDING_MOVIES)),
                snapshot
            )
        )
        assertFalse(
            shouldUseTraktScreensaverSource(
                true,
                prefs,
                snapshot.copy(trendingShowItems = emptyList())
            )
        )
        assertFalse(shouldUseTraktScreensaverSource(true, prefs, null))
    }

    @Test
    fun `buildTraktScreensaverRows caps movies and shows at ten items each`() {
        val snapshot = TraktDiscoverySnapshot(
            trendingMovieItems = (1..12).map { index ->
                buildPreview("movie-$index", ContentType.MOVIE, "https://image/movie-$index.jpg")
            },
            trendingShowItems = (1..11).map { index ->
                buildPreview("show-$index", ContentType.SERIES, "https://image/show-$index.jpg")
            }
        )

        val rows = buildTraktScreensaverRows(snapshot)

        assertEquals(2, rows.size)
        assertEquals(10, rows.first { it.type == ContentType.MOVIE }.items.size)
        assertEquals(10, rows.first { it.type == ContentType.SERIES }.items.size)
        assertEquals("https://api.trakt.tv", rows.first().addonBaseUrl)
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
            tomatoesRating = 94.0,
            genres = emptyList()
        )
    }
}
