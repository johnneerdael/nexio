package com.nexio.tv.data.repository

import com.nexio.tv.core.metadata.MetadataCredentialSource
import com.nexio.tv.core.metadata.MetadataProviderCredential
import com.nexio.tv.data.local.TmdbCatalogIds
import com.nexio.tv.data.local.TmdbCatalogPreferences
import com.nexio.tv.data.remote.api.TmdbMediaResult
import com.nexio.tv.data.remote.api.TmdbMultiSearchResult
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.PosterShape
import com.nexio.tv.domain.model.TitleRatingSource
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TmdbDiscoveryServiceTest {
    @Test
    fun `search returns one TMDB row sorted by popularity with movies tv and persons`() = runTest {
        val service = FakeTmdbDiscoveryClient(
            multiSearch = mapOf(
                1 to listOf(
                    multiResult(
                        id = 603,
                        mediaType = "movie",
                        title = "The Matrix",
                        posterPath = "/matrix-poster.jpg",
                        releaseDate = "1999-03-31",
                        voteAverage = 8.2,
                        originalLanguage = "en",
                        popularity = 50.0
                    ),
                    multiResult(
                        id = 1399,
                        mediaType = "tv",
                        name = "Game of Thrones",
                        posterPath = "/got-poster.jpg",
                        firstAirDate = "2011-04-17",
                        voteAverage = 8.4,
                        originalLanguage = "en",
                        popularity = 80.0
                    ),
                    multiResult(
                        id = 287,
                        mediaType = "person",
                        name = "Brad Pitt",
                        profilePath = "/brad.jpg",
                        popularity = 30.0
                    )
                )
            ),
            imdbIds = mapOf(
                "movie:603" to "tt0133093",
                "series:1399" to "tt0944947"
            )
        ).createService()

        val rows = service.search("matrix", TmdbCatalogPreferences())

        assertEquals(1, rows.size)
        val row = rows.single()
        assertEquals("tmdb_search", row.catalogId)
        assertEquals("TMDB Search", row.catalogName)
        assertEquals(ContentType.UNKNOWN, row.type)
        assertEquals("catalog", row.rawType)
        assertFalse(row.hasMore)
        assertFalse(row.supportsSkip)

        assertEquals(3, row.items.size)
        assertEquals("tt0944947", row.items[0].id)
        assertEquals(ContentType.SERIES, row.items[0].type)
        assertEquals("Game of Thrones (2011)", row.items[0].name)
        assertEquals("https://image.tmdb.org/t/p/w780/got-poster.jpg", row.items[0].poster)
        assertEquals(null, row.items[0].background)
        assertEquals(PosterShape.POSTER, row.items[0].posterShape)
        assertEquals(8.4f, row.items[0].imdbRating)
        assertEquals(TitleRatingSource.TMDB, row.items[0].ratingSource)

        assertEquals("tt0133093", row.items[1].id)
        assertEquals(ContentType.MOVIE, row.items[1].type)
        assertEquals("The Matrix (1999)", row.items[1].name)
        assertEquals("https://image.tmdb.org/t/p/w780/matrix-poster.jpg", row.items[1].poster)
        assertEquals(PosterShape.POSTER, row.items[1].posterShape)
        assertEquals("en", row.items[1].language)

        assertEquals("tmdb_person:287", row.items[2].id)
        assertEquals(ContentType.PERSON, row.items[2].type)
        assertEquals("Brad Pitt", row.items[2].name)
        assertEquals("https://image.tmdb.org/t/p/w780/brad.jpg", row.items[2].poster)
        assertEquals(null, row.items[2].releaseInfo)
        assertEquals(null, row.items[2].imdbRating)
    }

    @Test
    fun `search merges two pages and caps at forty popularity-sorted items`() = runTest {
        val page1 = (0 until 25).map { i ->
            multiResult(
                id = 1000 + i,
                mediaType = "movie",
                title = "Page1Movie$i",
                posterPath = "/p1-$i.jpg",
                releaseDate = "2020-01-01",
                popularity = 100.0 - i
            )
        }
        val page2 = (0 until 25).map { i ->
            multiResult(
                id = 2000 + i,
                mediaType = "movie",
                title = "Page2Movie$i",
                posterPath = "/p2-$i.jpg",
                releaseDate = "2021-01-01",
                popularity = 50.0 - i
            )
        }
        val service = FakeTmdbDiscoveryClient(
            multiSearch = mapOf(1 to page1, 2 to page2)
        ).createService()

        val rows = service.search("foo", TmdbCatalogPreferences())

        val items = rows.single().items
        assertEquals(40, items.size)
        // popularity-desc top 40 across both pages: all 25 from page1 (popularity 100..76) +
        // top 15 from page2 (popularity 50..36). So first = Page1Movie0, last = Page2Movie14.
        assertEquals("Page1Movie0 (2020)", items.first().name)
        assertEquals("Page2Movie14 (2021)", items.last().name)
    }

    @Test
    fun `search omits poster when poster path is missing and never falls back to backdrop`() = runTest {
        // The multi-search DTO deliberately exposes no backdrop_path field, so the mapping
        // can't reach for one even when the API returns one.
        val service = FakeTmdbDiscoveryClient(
            multiSearch = mapOf(
                1 to listOf(
                    multiResult(
                        id = 1,
                        mediaType = "movie",
                        title = "No Poster",
                        posterPath = null,
                        popularity = 10.0
                    )
                )
            )
        ).createService()

        val rows = service.search("foo", TmdbCatalogPreferences())

        val item = rows.single().items.single()
        assertEquals(null, item.poster)
        assertEquals(null, item.background)
        assertEquals(PosterShape.POSTER, item.posterShape)
    }

    @Test
    fun `enabled stock catalogs fetch only requested catalog IDs`() = runTest {
        val requestedCatalogs = setOf(
            TmdbCatalogIds.POPULAR_MOVIES,
            TmdbCatalogIds.LANGUAGE_SERIES
        )
        val client = FakeTmdbDiscoveryClient(
            catalogResults = mapOf(
                TmdbCatalogIds.POPULAR_MOVIES to listOf(mediaResult(id = 1, title = "Movie")),
                TmdbCatalogIds.LANGUAGE_SERIES to listOf(mediaResult(id = 2, name = "Series"))
            )
        )
        val service = client.createService()
        val preferences = TmdbCatalogPreferences(
            enabledCatalogs = requestedCatalogs,
            catalogOrder = listOf(
                TmdbCatalogIds.LANGUAGE_SERIES,
                TmdbCatalogIds.POPULAR_MOVIES
            )
        )

        val snapshot = service.refreshCatalogs(preferences, force = true)

        assertEquals(requestedCatalogs, client.requestedCatalogIds.toSet())
        assertEquals(requestedCatalogs, snapshot.rowsByCatalog.keys)
        val stockRow = snapshot.rowsByCatalog.getValue(TmdbCatalogIds.POPULAR_MOVIES)
        assertFalse(stockRow.hasMore)
        assertFalse(stockRow.supportsSkip)
    }

    @Test
    fun `missing TMDB credential returns empty search and empty catalog snapshot`() = runTest {
        val preferences = TmdbCatalogPreferences(
            enabledCatalogs = setOf(TmdbCatalogIds.TRENDING_MOVIES, TmdbCatalogIds.POPULAR_MOVIES),
            catalogOrder = listOf(TmdbCatalogIds.TRENDING_MOVIES, TmdbCatalogIds.POPULAR_MOVIES),
            includeAdult = true
        )
        val service = FakeTmdbDiscoveryClient(
            credential = MetadataProviderCredential("", source = MetadataCredentialSource.MISSING),
            multiSearch = mapOf(
                1 to listOf(
                    multiResult(id = 603, mediaType = "movie", title = "The Matrix"),
                    multiResult(id = 1399, mediaType = "tv", name = "Game of Thrones")
                )
            ),
            catalogResults = mapOf(TmdbCatalogIds.TRENDING_MOVIES to listOf(mediaResult(id = 1, title = "Movie")))
        ).createService()

        val searchRows = service.search("matrix", preferences)
        val snapshot = service.refreshCatalogs(preferences, force = true)

        assertTrue(searchRows.isEmpty())
        assertTrue(snapshot.rowsByCatalog.isEmpty())
        assertTrue(snapshot.updatedAtMs > 0L)
        assertEquals(true, snapshot.includeAdult)
        assertEquals(
            setOf(TmdbCatalogIds.TRENDING_MOVIES, TmdbCatalogIds.POPULAR_MOVIES),
            snapshot.catalogIdsWithCurrentPreferences
        )
    }

    @Test
    fun `missing TMDB credential clears a previously populated catalog snapshot`() = runTest {
        val client = FakeTmdbDiscoveryClient(
            catalogResults = mapOf(
                TmdbCatalogIds.TRENDING_MOVIES to listOf(mediaResult(id = 1, title = "Trending"))
            )
        )
        val service = client.createService()
        val preferences = TmdbCatalogPreferences(
            enabledCatalogs = setOf(TmdbCatalogIds.TRENDING_MOVIES),
            catalogOrder = listOf(TmdbCatalogIds.TRENDING_MOVIES),
            includeAdult = false
        )
        service.refreshCatalogs(preferences, force = true)
        client.credential = MetadataProviderCredential("", source = MetadataCredentialSource.MISSING)

        val snapshot = service.refreshCatalogs(preferences, force = true)

        assertTrue(snapshot.rowsByCatalog.isEmpty())
        assertEquals(false, snapshot.includeAdult)
        assertEquals(setOf(TmdbCatalogIds.TRENDING_MOVIES), snapshot.catalogIdsWithCurrentPreferences)
        assertEquals(snapshot, service.observeSnapshot().first())
    }

    @Test
    fun `mapped preview ID falls back to tmdb ID when IMDb ID is missing`() = runTest {
        val service = FakeTmdbDiscoveryClient(
            multiSearch = mapOf(
                1 to listOf(multiResult(id = 999, mediaType = "movie", title = "Unknown Movie", popularity = 1.0))
            )
        ).createService()

        val rows = service.search("unknown", TmdbCatalogPreferences())

        assertEquals("tmdb:999", rows.single().items.single().id)
    }

    @Test
    fun `observeSnapshot emits refreshed catalog snapshot after refreshCatalogs`() = runTest {
        val service = FakeTmdbDiscoveryClient(
            catalogResults = mapOf(
                TmdbCatalogIds.TRENDING_MOVIES to listOf(mediaResult(id = 603, title = "The Matrix"))
            )
        ).createService()
        val emission = async { service.observeSnapshot().drop(1).first() }

        val refreshed = service.refreshCatalogs(
            TmdbCatalogPreferences(enabledCatalogs = setOf(TmdbCatalogIds.TRENDING_MOVIES)),
            force = true
        )

        assertEquals(refreshed, emission.await())
        assertEquals(setOf(TmdbCatalogIds.TRENDING_MOVIES), refreshed.rowsByCatalog.keys)
    }

    @Test
    fun `currentRowsFor excludes disabled catalog ids even when snapshot has current provenance`() {
        val enabledPrefs = TmdbCatalogPreferences(
            enabledCatalogs = setOf(TmdbCatalogIds.TRENDING_MOVIES, TmdbCatalogIds.POPULAR_MOVIES),
            catalogOrder = listOf(TmdbCatalogIds.TRENDING_MOVIES, TmdbCatalogIds.POPULAR_MOVIES)
        )
        val sanitized = enabledPrefs.sanitized()
        val snapshot = TmdbDiscoverySnapshot(
            rowsByCatalog = mapOf(
                TmdbCatalogIds.TRENDING_MOVIES to catalogRow(TmdbCatalogIds.TRENDING_MOVIES),
                TmdbCatalogIds.POPULAR_MOVIES to catalogRow(TmdbCatalogIds.POPULAR_MOVIES)
            ),
            updatedAtMs = 123L,
            includeAdult = sanitized.includeAdult,
            catalogIdsWithCurrentPreferences = setOf(
                TmdbCatalogIds.TRENDING_MOVIES,
                TmdbCatalogIds.POPULAR_MOVIES
            )
        )

        val currentRows = snapshot.currentRowsFor(
            enabledPrefs.copy(enabledCatalogs = setOf(TmdbCatalogIds.TRENDING_MOVIES))
        )

        assertEquals(setOf(TmdbCatalogIds.TRENDING_MOVIES), currentRows.keys)
    }

    @Test
    fun `subset catalog refresh fetches missing selected enabled ids and preserves unrelated rows`() = runTest {
        val client = FakeTmdbDiscoveryClient(
            catalogResults = mapOf(
                TmdbCatalogIds.TRENDING_MOVIES to listOf(mediaResult(id = 1, title = "Trending")),
                TmdbCatalogIds.POPULAR_MOVIES to listOf(mediaResult(id = 2, title = "Popular"))
            )
        )
        val service = client.createService()
        val initialPreferences = TmdbCatalogPreferences(
            enabledCatalogs = setOf(TmdbCatalogIds.POPULAR_MOVIES),
            catalogOrder = listOf(TmdbCatalogIds.TRENDING_MOVIES, TmdbCatalogIds.POPULAR_MOVIES)
        )
        val currentPreferences = TmdbCatalogPreferences(
            enabledCatalogs = setOf(TmdbCatalogIds.TRENDING_MOVIES, TmdbCatalogIds.POPULAR_MOVIES),
            catalogOrder = listOf(TmdbCatalogIds.TRENDING_MOVIES, TmdbCatalogIds.POPULAR_MOVIES)
        )
        val initial = service.refreshCatalogs(initialPreferences, force = true)
        client.requestedCatalogIds.clear()

        val refreshed = service.refreshCatalogs(
            preferences = currentPreferences,
            force = false,
            catalogIds = setOf(TmdbCatalogIds.TRENDING_MOVIES)
        )

        assertEquals(listOf(TmdbCatalogIds.TRENDING_MOVIES), client.requestedCatalogIds)
        assertEquals(initial.rowsByCatalog.getValue(TmdbCatalogIds.POPULAR_MOVIES), refreshed.rowsByCatalog[TmdbCatalogIds.POPULAR_MOVIES])
        assertEquals(
            setOf(TmdbCatalogIds.TRENDING_MOVIES, TmdbCatalogIds.POPULAR_MOVIES),
            refreshed.rowsByCatalog.keys
        )
    }

    @Test
    fun `subset catalog refresh skips fetch when requested ids are already current`() = runTest {
        val client = FakeTmdbDiscoveryClient(
            catalogResults = mapOf(
                TmdbCatalogIds.TRENDING_MOVIES to listOf(mediaResult(id = 1, title = "Trending")),
                TmdbCatalogIds.POPULAR_MOVIES to listOf(mediaResult(id = 2, title = "Popular"))
            )
        )
        val service = client.createService()
        val preferences = TmdbCatalogPreferences(
            enabledCatalogs = setOf(TmdbCatalogIds.TRENDING_MOVIES, TmdbCatalogIds.POPULAR_MOVIES),
            catalogOrder = listOf(TmdbCatalogIds.TRENDING_MOVIES, TmdbCatalogIds.POPULAR_MOVIES)
        )
        val initial = service.refreshCatalogs(preferences, force = true)
        client.requestedCatalogIds.clear()

        val refreshed = service.refreshCatalogs(
            preferences = preferences,
            force = false,
            catalogIds = setOf(TmdbCatalogIds.TRENDING_MOVIES)
        )

        assertTrue(client.requestedCatalogIds.isEmpty())
        assertEquals(initial.rowsByCatalog, refreshed.rowsByCatalog)
        assertEquals(initial.catalogIdsWithCurrentPreferences, refreshed.catalogIdsWithCurrentPreferences)
    }

    @Test
    fun `subset catalog refresh drops previously current rows when preference provenance changes`() = runTest {
        val client = FakeTmdbDiscoveryClient(
            catalogResults = mapOf(
                TmdbCatalogIds.TRENDING_MOVIES to listOf(mediaResult(id = 1, title = "Trending")),
                TmdbCatalogIds.POPULAR_MOVIES to listOf(mediaResult(id = 2, title = "Popular"))
            )
        )
        val service = client.createService()
        val previousPreferences = TmdbCatalogPreferences(
            enabledCatalogs = setOf(TmdbCatalogIds.TRENDING_MOVIES, TmdbCatalogIds.POPULAR_MOVIES),
            catalogOrder = listOf(TmdbCatalogIds.TRENDING_MOVIES, TmdbCatalogIds.POPULAR_MOVIES),
            includeAdult = false
        )
        service.refreshCatalogs(previousPreferences, force = true)
        client.requestedCatalogIds.clear()
        val currentPreferences = previousPreferences.copy(includeAdult = true)

        val refreshed = service.refreshCatalogs(
            preferences = currentPreferences,
            force = false,
            catalogIds = setOf(TmdbCatalogIds.TRENDING_MOVIES)
        )

        assertEquals(listOf(TmdbCatalogIds.TRENDING_MOVIES), client.requestedCatalogIds)
        assertEquals(setOf(TmdbCatalogIds.TRENDING_MOVIES), refreshed.rowsByCatalog.keys)
        assertEquals(setOf(TmdbCatalogIds.TRENDING_MOVIES), refreshed.catalogIdsWithCurrentPreferences)
        assertFalse(TmdbCatalogIds.POPULAR_MOVIES in refreshed.currentRowsFor(currentPreferences))
    }

    private class FakeTmdbDiscoveryClient(
        var credential: MetadataProviderCredential = MetadataProviderCredential(
            "key",
            source = MetadataCredentialSource.BUILT_IN
        ),
        private val multiSearch: Map<Int, List<TmdbMultiSearchResult>> = emptyMap(),
        private val catalogResults: Map<String, List<TmdbMediaResult>> = emptyMap(),
        private val imdbIds: Map<String, String> = emptyMap()
    ) : TmdbDiscoveryClient {
        val requestedCatalogIds = mutableListOf<String>()

        override suspend fun credential(): MetadataProviderCredential = credential

        override suspend fun searchMulti(
            query: String,
            page: Int,
            preferences: TmdbCatalogPreferences
        ): List<TmdbMultiSearchResult> = multiSearch[page].orEmpty()

        override suspend fun fetchCatalog(
            catalogId: String,
            preferences: TmdbCatalogPreferences
        ): List<TmdbMediaResult> {
            requestedCatalogIds += catalogId
            return catalogResults[catalogId].orEmpty()
        }

        override suspend fun imdbId(tmdbId: Int, contentType: ContentType): String? {
            val key = if (contentType == ContentType.MOVIE) "movie:$tmdbId" else "series:$tmdbId"
            return imdbIds[key]
        }

        fun createService(): TmdbDiscoveryService = TmdbDiscoveryService(client = this)
    }

    private fun multiResult(
        id: Int,
        mediaType: String,
        title: String? = null,
        name: String? = null,
        originalLanguage: String? = null,
        posterPath: String? = null,
        profilePath: String? = null,
        releaseDate: String? = null,
        firstAirDate: String? = null,
        voteAverage: Double? = null,
        popularity: Double? = null
    ): TmdbMultiSearchResult {
        return TmdbMultiSearchResult(
            id = id,
            mediaType = mediaType,
            title = title,
            name = name,
            originalLanguage = originalLanguage,
            posterPath = posterPath,
            profilePath = profilePath,
            releaseDate = releaseDate,
            firstAirDate = firstAirDate,
            voteAverage = voteAverage,
            popularity = popularity
        )
    }

    private fun catalogRow(catalogId: String): com.nexio.tv.domain.model.CatalogRow {
        return com.nexio.tv.domain.model.CatalogRow(
            addonId = "tmdb",
            addonName = "TMDB",
            addonBaseUrl = "https://api.themoviedb.org/3",
            catalogId = catalogId,
            catalogName = catalogId,
            type = ContentType.MOVIE,
            items = emptyList(),
            hasMore = false,
            supportsSkip = false
        )
    }

    private fun mediaResult(
        id: Int,
        title: String? = null,
        name: String? = null,
        originalTitle: String? = null,
        originalName: String? = null,
        mediaType: String? = null,
        originalLanguage: String? = null,
        posterPath: String? = null,
        backdropPath: String? = null,
        overview: String? = null,
        releaseDate: String? = null,
        firstAirDate: String? = null,
        voteAverage: Double? = null
    ): TmdbMediaResult {
        return TmdbMediaResult(
            id = id,
            title = title,
            name = name,
            originalTitle = originalTitle,
            originalName = originalName,
            mediaType = mediaType,
            originalLanguage = originalLanguage,
            posterPath = posterPath,
            backdropPath = backdropPath,
            overview = overview,
            releaseDate = releaseDate,
            firstAirDate = firstAirDate,
            voteAverage = voteAverage
        )
    }
}
