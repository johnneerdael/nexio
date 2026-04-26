package com.nexio.tv.data.repository

import com.nexio.tv.core.metadata.MetadataCredentialSource
import com.nexio.tv.core.metadata.MetadataProviderCredential
import com.nexio.tv.data.local.TmdbCatalogIds
import com.nexio.tv.data.local.TmdbCatalogPreferences
import com.nexio.tv.data.remote.api.TmdbMediaResult
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.PosterShape
import com.nexio.tv.domain.model.TitleRatingSource
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Assert.assertTrue
import org.junit.Test

class TmdbDiscoveryServiceTest {
    @Test
    fun `search returns movies and series as one TMDB primary row with IMDb ids`() = runTest {
        val service = FakeTmdbDiscoveryClient(
            movieSearch = listOf(
                mediaResult(
                    id = 603,
                    title = "The Matrix",
                    backdropPath = "/matrix-backdrop.jpg",
                    posterPath = "/matrix-poster.jpg",
                    releaseDate = "1999-03-31",
                    voteAverage = 8.2,
                    originalLanguage = "en"
                )
            ),
            tvSearch = listOf(
                mediaResult(
                    id = 1399,
                    name = "Game of Thrones",
                    posterPath = "/got-poster.jpg",
                    firstAirDate = "2011-04-17",
                    voteAverage = 8.4,
                    originalLanguage = "en"
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
        assertEquals("tt0133093", row.items[0].id)
        assertEquals(ContentType.MOVIE, row.items[0].type)
        assertEquals("https://image.tmdb.org/t/p/w1280/matrix-backdrop.jpg", row.items[0].poster)
        assertEquals("https://image.tmdb.org/t/p/w1280/matrix-backdrop.jpg", row.items[0].background)
        assertEquals(PosterShape.LANDSCAPE, row.items[0].posterShape)
        assertEquals(8.2f, row.items[0].imdbRating)
        assertEquals(TitleRatingSource.TMDB, row.items[0].ratingSource)
        assertEquals("en", row.items[0].language)
        assertEquals("tt0944947", row.items[1].id)
        assertEquals(ContentType.SERIES, row.items[1].type)
        assertEquals("https://image.tmdb.org/t/p/w780/got-poster.jpg", row.items[1].poster)
        assertEquals(PosterShape.POSTER, row.items[1].posterShape)
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
            includeAdult = true,
            hideUnreleasedDigital = false
        )
        val service = FakeTmdbDiscoveryClient(
            credential = MetadataProviderCredential("", source = MetadataCredentialSource.MISSING),
            movieSearch = listOf(mediaResult(id = 603, title = "The Matrix")),
            tvSearch = listOf(mediaResult(id = 1399, name = "Game of Thrones")),
            catalogResults = mapOf(TmdbCatalogIds.TRENDING_MOVIES to listOf(mediaResult(id = 1, title = "Movie")))
        ).createService()

        val searchRows = service.search("matrix", preferences)
        val snapshot = service.refreshCatalogs(preferences, force = true)

        assertTrue(searchRows.isEmpty())
        assertTrue(snapshot.rowsByCatalog.isEmpty())
        assertTrue(snapshot.updatedAtMs > 0L)
        assertEquals(true, snapshot.includeAdult)
        assertEquals(false, snapshot.hideUnreleasedDigital)
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
            includeAdult = false,
            hideUnreleasedDigital = true
        )
        service.refreshCatalogs(preferences, force = true)
        client.credential = MetadataProviderCredential("", source = MetadataCredentialSource.MISSING)

        val snapshot = service.refreshCatalogs(preferences, force = true)

        assertTrue(snapshot.rowsByCatalog.isEmpty())
        assertEquals(false, snapshot.includeAdult)
        assertEquals(true, snapshot.hideUnreleasedDigital)
        assertEquals(setOf(TmdbCatalogIds.TRENDING_MOVIES), snapshot.catalogIdsWithCurrentPreferences)
        assertEquals(snapshot, service.observeSnapshot().first())
    }

    @Test
    fun `mapped preview ID falls back to tmdb ID when IMDb ID is missing`() = runTest {
        val service = FakeTmdbDiscoveryClient(
            movieSearch = listOf(mediaResult(id = 999, title = "Unknown Movie"))
        ).createService()

        val rows = service.search("unknown", TmdbCatalogPreferences())

        assertEquals("tmdb:999", rows.single().items.single().id)
    }

    @Test
    fun `search propagates cancellation from child`() = runTest {
        val service = FakeTmdbDiscoveryClient(
            movieSearch = listOf(mediaResult(id = 999, title = "Unknown Movie")),
            searchDelayMillis = 10_000L
        ).createService()
        var cancelled = false

        try {
            withTimeout(50) { service.search("matrix", TmdbCatalogPreferences()) }
            fail("Expected CancellationException")
        } catch (_: CancellationException) {
            cancelled = true
        }
        assertTrue(cancelled)
    }

    @Test
    fun `refreshCatalogs propagates cancellation from catalog fetch`() = runTest {
        val service = FakeTmdbDiscoveryClient(
            catalogResults = mapOf(
                TmdbCatalogIds.TRENDING_MOVIES to listOf(mediaResult(id = 603, title = "The Matrix"))
            ),
            catalogDelayMillis = 10_000L
        ).createService()
        val preferences = TmdbCatalogPreferences(
            enabledCatalogs = setOf(TmdbCatalogIds.TRENDING_MOVIES),
            catalogOrder = listOf(TmdbCatalogIds.TRENDING_MOVIES)
        )
        var cancelled = false

        try {
            withTimeout(50) { service.refreshCatalogs(preferences, force = true) }
            fail("Expected CancellationException")
        } catch (_: CancellationException) {
            cancelled = true
        }
        assertTrue(cancelled)
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
            hideUnreleasedDigital = sanitized.hideUnreleasedDigital,
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
            includeAdult = false,
            hideUnreleasedDigital = false
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
        private val movieSearch: List<TmdbMediaResult> = emptyList(),
        private val tvSearch: List<TmdbMediaResult> = emptyList(),
        private val catalogResults: Map<String, List<TmdbMediaResult>> = emptyMap(),
        private val imdbIds: Map<String, String> = emptyMap(),
        private val searchDelayMillis: Long = 0L,
        private val catalogDelayMillis: Long = 0L
    ) : TmdbDiscoveryClient {
        val requestedCatalogIds = mutableListOf<String>()

        override suspend fun credential(): MetadataProviderCredential = credential

        override suspend fun searchMovies(
            query: String,
            preferences: TmdbCatalogPreferences
        ): List<TmdbMediaResult> {
            if (searchDelayMillis > 0) {
                delay(searchDelayMillis)
            }
            return movieSearch
        }

        override suspend fun searchTv(
            query: String,
            preferences: TmdbCatalogPreferences
        ): List<TmdbMediaResult> {
            if (searchDelayMillis > 0) {
                delay(searchDelayMillis)
            }
            return tvSearch
        }

        override suspend fun fetchCatalog(
            catalogId: String,
            preferences: TmdbCatalogPreferences
        ): List<TmdbMediaResult> {
            requestedCatalogIds += catalogId
            if (catalogDelayMillis > 0) {
                delay(catalogDelayMillis)
            }
            return catalogResults[catalogId].orEmpty()
        }

        override suspend fun imdbId(tmdbId: Int, contentType: ContentType): String? {
            val key = if (contentType == ContentType.MOVIE) "movie:$tmdbId" else "series:$tmdbId"
            return imdbIds[key]
        }

        fun createService(): TmdbDiscoveryService = TmdbDiscoveryService(client = this)
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
