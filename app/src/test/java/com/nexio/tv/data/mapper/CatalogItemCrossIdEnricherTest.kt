package com.nexio.tv.data.mapper

import com.nexio.tv.core.anime.AnimeIdMapAsset
import com.nexio.tv.core.anime.AnimeIdMapRecord
import com.nexio.tv.core.anime.AnimeIdMappingService
import com.nexio.tv.core.metadata.router.AnimeIdScheme
import com.nexio.tv.core.metadata.router.IdMapping
import com.nexio.tv.core.metadata.router.IdMappingSource
import com.nexio.tv.core.metadata.router.InMemoryIdMappingStore
import com.nexio.tv.core.metadata.router.MetadataPrimaryProvider
import com.nexio.tv.core.metadata.router.ParsedMetadataId
import com.nexio.tv.core.metadata.router.StableIdBundleResolver
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.PosterShape
import com.nexio.tv.domain.model.ProviderIds
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class CatalogItemCrossIdEnricherTest {

    private fun tmdbMoviePreview(): MetaPreview = MetaPreview(
        id = "tmdb:202555",
        type = ContentType.MOVIE,
        rawType = "movie",
        name = "Daredevil: Born Again",
        poster = "https://image.tmdb.org/p/p1.jpg",
        posterShape = PosterShape.POSTER,
        background = null,
        logo = null,
        description = null,
        releaseInfo = "2025",
        runtime = null,
        imdbRating = null,
        genres = emptyList(),
        trailerYtIds = emptyList(),
        language = null,
        firstPaintStableIds = ProviderIds(imdb = null, tmdb = "202555")
    )

    private fun kitsuPreview(): MetaPreview = MetaPreview(
        id = "kitsu:12345",
        type = ContentType.SERIES,
        rawType = "series",
        name = "Anime Show",
        poster = "https://kitsu.example/p.jpg",
        posterShape = PosterShape.POSTER,
        background = null,
        logo = null,
        description = null,
        releaseInfo = "2023",
        runtime = null,
        imdbRating = null,
        genres = emptyList(),
        trailerYtIds = emptyList(),
        language = null,
        firstPaintStableIds = ProviderIds(kitsu = "12345")
    )

    @Test
    fun `tmdb movie with cached imdb mapping returns enriched MetaPreview`() = runTest {
        val store = InMemoryIdMappingStore(
            initialMappings = listOf(
                IdMapping(
                    sourceId = ParsedMetadataId(AnimeIdScheme.TMDB, "movie:202555", "tmdb:movie:202555"),
                    provider = MetadataPrimaryProvider.IMDB,
                    providerId = "tt12345",
                    source = IdMappingSource.PROVIDER_LOOKUP,
                    evidence = "tmdbMovieToImdb"
                )
            )
        )
        val enricher = CatalogItemCrossIdEnricher(
            idMappingStore = store,
            stableIdBundleResolver = throwingResolver(),
            animeIdMappingService = AnimeIdMappingService { AnimeIdMapAsset(schemaVersion = 0) }
        )
        val preview = tmdbMoviePreview()

        val enriched = enricher.enrichFromCache(preview)

        assertEquals("tt12345", enriched.firstPaintStableIds.imdb)
        assertEquals("202555", enriched.firstPaintStableIds.tmdb)
        assertNotSame(preview, enriched)
    }

    @Test
    fun `tmdb movie with no cached mapping returns preview verbatim and does not query network`() = runTest {
        val emptyStore = InMemoryIdMappingStore()
        val resolverCalls = mutableListOf<Any>()
        val enricher = CatalogItemCrossIdEnricher(
            idMappingStore = emptyStore,
            stableIdBundleResolver = recordingResolver(resolverCalls),
            animeIdMappingService = AnimeIdMappingService { AnimeIdMapAsset(schemaVersion = 0) }
        )
        val preview = tmdbMoviePreview()

        val enriched = enricher.enrichFromCache(preview)

        assertNull(enriched.firstPaintStableIds.imdb)
        assertSame("cache miss must return the same MetaPreview reference", preview, enriched)
        assertEquals("enrichFromCache must not call the resolver (network)", 0, resolverCalls.size)
    }

    @Test
    fun `kitsu preview resolves imdb synchronously via in-memory anime map`() = runTest {
        val animeMap = AnimeIdMappingService {
            AnimeIdMapAsset(
                schemaVersion = 1,
                identityRecordsByKitsu = mapOf(
                    "12345" to AnimeIdMapRecord(
                        kitsu = "12345",
                        imdb = "tt99999",
                        tmdb = null,
                        tvdb = "555",
                        mal = null,
                        anilist = null,
                        anidb = null,
                        mediaType = "series",
                        sourceType = "tv"
                    )
                )
            )
        }
        val enricher = CatalogItemCrossIdEnricher(
            idMappingStore = InMemoryIdMappingStore(),
            stableIdBundleResolver = throwingResolver(),
            animeIdMappingService = animeMap
        )
        val preview = kitsuPreview()

        val enriched = enricher.enrichFromCache(preview)

        assertEquals("tt99999", enriched.firstPaintStableIds.imdb)
        assertEquals("555", enriched.firstPaintStableIds.tvdb)
        assertEquals("12345", enriched.firstPaintStableIds.kitsu)
    }

    @Test
    fun `already-imdb preview is returned unchanged with reference equality`() = runTest {
        val enricher = CatalogItemCrossIdEnricher(
            idMappingStore = InMemoryIdMappingStore(),
            stableIdBundleResolver = throwingResolver(),
            animeIdMappingService = AnimeIdMappingService { AnimeIdMapAsset(schemaVersion = 0) }
        )
        val preview = tmdbMoviePreview().copy(
            id = "tt12345",
            firstPaintStableIds = ProviderIds(imdb = "tt12345")
        )

        val enriched = enricher.enrichFromCache(preview)

        assertSame("imdb already present → no change, ref-equal", preview, enriched)
    }

    @Test
    fun `unrecognized provider prefix is returned verbatim, no resolver call`() = runTest {
        val resolverCalls = mutableListOf<Any>()
        val enricher = CatalogItemCrossIdEnricher(
            idMappingStore = InMemoryIdMappingStore(),
            stableIdBundleResolver = recordingResolver(resolverCalls),
            animeIdMappingService = AnimeIdMappingService { AnimeIdMapAsset(schemaVersion = 0) }
        )
        val preview = tmdbMoviePreview().copy(
            id = "mystery:abc",
            firstPaintStableIds = ProviderIds()
        )

        val enriched = enricher.enrichFromCache(preview)

        assertSame(preview, enriched)
        assertEquals(0, resolverCalls.size)
    }

    private fun throwingResolver(): StableIdBundleResolver =
        StableIdBundleResolver(
            idMappingStore = InMemoryIdMappingStore(),
            lookup = object : StableIdBundleResolver.Lookup {
                override suspend fun tmdbMovieToImdb(tmdbId: String): String? = error("not expected")
                override suspend fun imdbToTmdbMovie(imdbId: String): String? = error("not expected")
                override suspend fun tmdbTvToTvdb(tmdbId: String): String? = error("not expected")
                override suspend fun tmdbTvToImdb(tmdbId: String): String? = error("not expected")
                override suspend fun imdbToTvdbSeries(imdbId: String): String? = error("not expected")
                override suspend fun tvdbSeriesToImdb(tvdbId: String): String? = error("not expected")
            }
        )

    private fun recordingResolver(calls: MutableList<Any>): StableIdBundleResolver =
        StableIdBundleResolver(
            idMappingStore = InMemoryIdMappingStore(),
            lookup = object : StableIdBundleResolver.Lookup {
                override suspend fun tmdbMovieToImdb(tmdbId: String): String? { calls += "tmdbMovieToImdb:$tmdbId"; return null }
                override suspend fun imdbToTmdbMovie(imdbId: String): String? { calls += "imdbToTmdbMovie:$imdbId"; return null }
                override suspend fun tmdbTvToTvdb(tmdbId: String): String? { calls += "tmdbTvToTvdb:$tmdbId"; return null }
                override suspend fun tmdbTvToImdb(tmdbId: String): String? { calls += "tmdbTvToImdb:$tmdbId"; return null }
                override suspend fun imdbToTvdbSeries(imdbId: String): String? { calls += "imdbToTvdbSeries:$imdbId"; return null }
                override suspend fun tvdbSeriesToImdb(tvdbId: String): String? { calls += "tvdbSeriesToImdb:$tvdbId"; return null }
            }
        )
}
