package com.nexio.tv.core.metadata.router

import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.ProviderId
import com.nexio.tv.domain.model.ProviderIds
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StableIdBundleResolverTest {
    @Test
    fun `trakt tv rail with known tvdb and imdb resolves without network`() = runTest {
        val lookup = RecordingLookup()
        val resolver = resolver(lookup = lookup)

        val bundle = resolver.resolve(
            request(
                itemType = ContentType.SERIES,
                routeProvider = MetadataPrimaryProvider.TVDB,
                knownIds = ProviderIds(tvdb = " 81189 ", imdb = " tt0903747 ", trakt = "1"),
                sourceProvider = ProviderId.TRAKT,
                sourceItemId = "trakt:show:1",
                railId = "trakt:popular"
            )
        )

        assertEquals("81189", bundle.canonical.tvdbSeriesId)
        assertEquals("tt0903747", bundle.sidecars.imdbId)
        assertEquals(0, lookup.callCount)
    }

    @Test
    fun `kitsu known id resolves canonical without provider network`() = runTest {
        val lookup = RecordingLookup()
        val resolver = resolver(lookup = lookup)

        val bundle = resolver.resolve(
            request(
                routeProvider = MetadataPrimaryProvider.KITSU,
                knownIds = ProviderIds(kitsu = " 7442 ")
            )
        )

        assertEquals("7442", bundle.canonical.kitsuAnimeId)
        assertEquals(0, lookup.callCount)
    }

    @Test
    fun `kitsu route tries later source ids when earlier store mapping is absent`() = runTest {
        val store = InMemoryIdMappingStore(
            initialMappings = listOf(
                IdMapping(
                    sourceId = MetadataIdParser.parse("anilist:16498"),
                    provider = MetadataPrimaryProvider.KITSU,
                    providerId = "7442",
                    source = IdMappingSource.FRIBB,
                    evidence = "seed"
                )
            ),
            nowEpochMs = { 10L }
        )
        val lookup = RecordingLookup()
        val resolver = resolver(store = store, lookup = lookup)

        val bundle = resolver.resolve(
            request(
                routeProvider = MetadataPrimaryProvider.KITSU,
                knownIds = ProviderIds(mal = "21", anilist = "16498")
            )
        )

        assertEquals("7442", bundle.canonical.kitsuAnimeId)
        assertEquals(0, lookup.callCount)
    }

    @Test
    fun `tmdb tv rail resolves direct tvdb before imdb sidecar lookup`() = runTest {
        val lookup = RecordingLookup(
            tmdbTvToTvdbResult = "121361",
            tmdbTvToImdbResult = "tt0944947"
        )
        val resolver = resolver(lookup = lookup)

        val bundle = resolver.resolve(
            request(
                itemType = ContentType.SERIES,
                routeProvider = MetadataPrimaryProvider.TVDB,
                knownIds = ProviderIds(tmdb = "1399")
            )
        )

        assertEquals("121361", bundle.canonical.tvdbSeriesId)
        assertEquals("tt0944947", bundle.sidecars.imdbId)
        assertEquals(listOf("tmdbTvToTvdb:1399", "tmdbTvToImdb:1399"), lookup.calls)
        assertEquals(
            listOf(
                StableIdEvidence("providerLookup.tmdbTvToTvdb", "TVDB", true, "121361"),
                StableIdEvidence("providerLookup.tmdbTvToImdb", "IMDB", true, "tt0944947")
            ),
            bundle.evidence
        )
    }

    @Test
    fun `tmdb series preview resolves tvdb and imdb sidecars for home hydration`() = runTest {
        val resolver = resolver(
            lookup = object : StableIdBundleResolver.Lookup {
                override suspend fun tmdbMovieToImdb(tmdbId: String): String? = null
                override suspend fun imdbToTmdbMovie(imdbId: String): String? = null
                override suspend fun tmdbTvToTvdb(tmdbId: String): String? = "371572"
                override suspend fun tmdbTvToImdb(tmdbId: String): String? = "tt11198330"
                override suspend fun imdbToTvdbSeries(imdbId: String): String? = null
                override suspend fun tvdbSeriesToImdb(tvdbId: String): String? = null
            }
        )

        val bundle = resolver.resolve(
            StableIdBundleRequest(
                itemKey = "series:tmdb:94997",
                itemType = ContentType.SERIES,
                routeProvider = MetadataPrimaryProvider.TVDB,
                knownIds = ProviderIds(tmdb = "94997"),
                sourceProvider = ProviderId.TMDB,
                sourceItemId = "tmdb:94997",
                railId = null,
                trigger = StableIdResolutionTrigger.VISIBLE_HOME_HYDRATION
            )
        )

        assertEquals("371572", bundle.canonical.tvdbSeriesId)
        assertEquals("tt11198330", bundle.sidecars.imdbId)
    }

    @Test
    fun `tmdb tv rail falls back through imdb when direct tvdb lookup misses`() = runTest {
        val lookup = RecordingLookup(
            tmdbTvToTvdbResult = null,
            tmdbTvToImdbResult = "tt0903747",
            imdbToTvdbSeriesResult = "81189"
        )
        val resolver = resolver(lookup = lookup)

        val bundle = resolver.resolve(
            request(
                itemType = ContentType.SERIES,
                routeProvider = MetadataPrimaryProvider.TVDB,
                knownIds = ProviderIds(tmdb = "1396")
            )
        )

        assertEquals("81189", bundle.canonical.tvdbSeriesId)
        assertEquals("tt0903747", bundle.sidecars.imdbId)
        assertEquals(listOf("tmdbTvToTvdb:1396", "tmdbTvToImdb:1396", "imdbToTvdbSeries:tt0903747"), lookup.calls)
        assertEquals(
            listOf(
                StableIdEvidence("providerLookup.tmdbTvToTvdb", "TVDB", true, null),
                StableIdEvidence("providerLookup.tmdbTvToImdb", "IMDB", true, "tt0903747"),
                StableIdEvidence("providerLookup.imdbToTvdbSeries", "TVDB", true, "81189")
            ),
            bundle.evidence
        )
    }

    @Test
    fun `tvdb route bridges through tmdb tv after known imdb misses tvdb`() = runTest {
        val lookup = RecordingLookup(
            tmdbTvToImdbResult = "tt0944947",
            imdbToTvdbSeriesResults = mapOf(
                "tt_bad" to null,
                "tt0944947" to "121361"
            )
        )
        val resolver = resolver(lookup = lookup)

        val bundle = resolver.resolve(
            request(
                itemType = ContentType.SERIES,
                routeProvider = MetadataPrimaryProvider.TVDB,
                knownIds = ProviderIds(imdb = "tt_bad", tmdb = "1399")
            )
        )

        assertEquals("121361", bundle.canonical.tvdbSeriesId)
        assertEquals("tt0944947", bundle.sidecars.imdbId)
        assertEquals(
            listOf(
                "imdbToTvdbSeries:tt_bad",
                "tmdbTvToTvdb:1399",
                "tmdbTvToImdb:1399",
                "imdbToTvdbSeries:tt0944947"
            ),
            lookup.calls
        )
    }

    @Test
    fun `negative tmdb tv imdb lookup does not suppress tmdb movie imdb lookup for same id`() = runTest {
        val store = InMemoryIdMappingStore(nowEpochMs = { 10L })
        val lookup = RecordingLookup(
            tmdbTvToImdbResult = null,
            tmdbMovieToImdbResult = "tt0137523"
        )
        val resolver = resolver(store = store, lookup = lookup)

        resolver.resolve(
            request(
                itemType = ContentType.SERIES,
                routeProvider = MetadataPrimaryProvider.TVDB,
                knownIds = ProviderIds(tmdb = "550")
            )
        )
        val movieBundle = resolver.resolve(
            request(
                itemType = ContentType.MOVIE,
                routeProvider = MetadataPrimaryProvider.TMDB,
                knownIds = ProviderIds(tmdb = "550")
            )
        )

        assertEquals("tt0137523", movieBundle.sidecars.imdbId)
        assertEquals(listOf("tmdbTvToTvdb:550", "tmdbTvToImdb:550", "tmdbMovieToImdb:550"), lookup.calls)
    }

    @Test
    fun `tmdb movie with known tmdb resolves imdb sidecar through provider lookup`() = runTest {
        val lookup = RecordingLookup(tmdbMovieToImdbResult = "tt0137523")
        val resolver = resolver(lookup = lookup)

        val bundle = resolver.resolve(
            request(
                itemType = ContentType.MOVIE,
                routeProvider = MetadataPrimaryProvider.TMDB,
                knownIds = ProviderIds(tmdb = "550")
            )
        )

        assertEquals("550", bundle.canonical.tmdbMovieId)
        assertEquals("tt0137523", bundle.sidecars.imdbId)
        assertEquals(listOf("tmdbMovieToImdb:550"), lookup.calls)
    }

    @Test
    fun `cache hit resolves tmdb movie from imdb without provider lookup`() = runTest {
        val store = InMemoryIdMappingStore(
            initialMappings = listOf(
                IdMapping(
                    sourceId = MetadataIdParser.parse("tt0137523"),
                    provider = MetadataPrimaryProvider.TMDB,
                    providerId = "550",
                    source = IdMappingSource.PROVIDER_LOOKUP,
                    evidence = "seed"
                )
            )
        )
        val lookup = RecordingLookup(imdbToTmdbMovieResult = "999")
        val resolver = resolver(store = store, lookup = lookup)

        val bundle = resolver.resolve(
            request(
                itemType = ContentType.MOVIE,
                routeProvider = MetadataPrimaryProvider.TMDB,
                knownIds = ProviderIds(imdb = "tt0137523")
            )
        )

        assertEquals("550", bundle.canonical.tmdbMovieId)
        assertEquals("tt0137523", bundle.sidecars.imdbId)
        assertEquals(0, lookup.callCount)
        assertEquals(
            listOf(StableIdEvidence("store.PROVIDER_LOOKUP.imdbToTmdbMovie", "TMDB", false, "550")),
            bundle.evidence
        )
    }

    @Test
    fun `provider miss persists negative and second resolve does not call provider`() = runTest {
        val store = InMemoryIdMappingStore(nowEpochMs = { 10L })
        val lookup = RecordingLookup(imdbToTmdbMovieResult = null)
        val resolver = resolver(store = store, lookup = lookup)
        val request = request(
            itemType = ContentType.MOVIE,
            routeProvider = MetadataPrimaryProvider.TMDB,
            knownIds = ProviderIds(imdb = "tt0000000")
        )

        resolver.resolve(request)
        resolver.resolve(request)

        assertEquals(listOf("imdbToTmdbMovie:tt0000000"), lookup.calls)
        val negative = store.readRaw(
            provider = MetadataPrimaryProvider.TMDB,
            sourceId = MetadataIdParser.parse("tt0000000")
        )
        assertEquals(IdMappingSource.NEGATIVE, negative?.source)
    }

    @Test
    fun `tracking route providers return no canonical ids and do not call provider network`() = runTest {
        val lookup = RecordingLookup(
            tmdbMovieToImdbResult = "tt0137523",
            imdbToTmdbMovieResult = "550",
            imdbToTvdbSeriesResult = "81189"
        )
        val resolver = resolver(lookup = lookup)

        val traktBundle = resolver.resolve(
            request(
                routeProvider = MetadataPrimaryProvider.TRAKT,
                knownIds = ProviderIds(imdb = "tt0137523", trakt = "1")
            )
        )
        val simklBundle = resolver.resolve(
            request(
                routeProvider = MetadataPrimaryProvider.SIMKL,
                knownIds = ProviderIds(imdb = "tt0137523", simkl = "49108")
            )
        )

        assertNull(traktBundle.canonical.tmdbMovieId)
        assertNull(traktBundle.canonical.tvdbSeriesId)
        assertNull(traktBundle.canonical.kitsuAnimeId)
        assertNull(simklBundle.canonical.tmdbMovieId)
        assertNull(simklBundle.canonical.tvdbSeriesId)
        assertNull(simklBundle.canonical.kitsuAnimeId)
        assertEquals(0, lookup.callCount)
    }

    private fun resolver(
        store: IdMappingStore = InMemoryIdMappingStore(nowEpochMs = { 10L }),
        lookup: StableIdBundleResolver.Lookup = RecordingLookup()
    ): StableIdBundleResolver =
        StableIdBundleResolver(
            idMappingStore = store,
            lookup = lookup,
            nowEpochMs = { 10L }
        )

    private fun request(
        itemType: ContentType = ContentType.SERIES,
        routeProvider: MetadataPrimaryProvider,
        knownIds: ProviderIds,
        sourceProvider: ProviderId? = null,
        sourceItemId: String = "source-item",
        railId: String? = null
    ): StableIdBundleRequest =
        StableIdBundleRequest(
            itemKey = knownIds.bestStableItemKey(itemType, sourceItemId),
            itemType = itemType,
            routeProvider = routeProvider,
            knownIds = knownIds,
            sourceProvider = sourceProvider,
            sourceItemId = sourceItemId,
            railId = railId,
            trigger = StableIdResolutionTrigger.FOCUSED_HOME_ITEM
        )

    private class RecordingLookup(
        private val tmdbMovieToImdbResult: String? = null,
        private val imdbToTmdbMovieResult: String? = null,
        private val tmdbTvToTvdbResult: String? = null,
        private val tmdbTvToImdbResult: String? = null,
        private val imdbToTvdbSeriesResult: String? = null,
        private val imdbToTvdbSeriesResults: Map<String, String?> = emptyMap(),
        private val tvdbSeriesToImdbResult: String? = null
    ) : StableIdBundleResolver.Lookup {
        val calls = mutableListOf<String>()
        val callCount: Int get() = calls.size

        override suspend fun tmdbMovieToImdb(tmdbId: String): String? {
            calls += "tmdbMovieToImdb:$tmdbId"
            return tmdbMovieToImdbResult
        }

        override suspend fun imdbToTmdbMovie(imdbId: String): String? {
            calls += "imdbToTmdbMovie:$imdbId"
            return imdbToTmdbMovieResult
        }

        override suspend fun tmdbTvToTvdb(tmdbId: String): String? {
            calls += "tmdbTvToTvdb:$tmdbId"
            return tmdbTvToTvdbResult
        }

        override suspend fun tmdbTvToImdb(tmdbId: String): String? {
            calls += "tmdbTvToImdb:$tmdbId"
            return tmdbTvToImdbResult
        }

        override suspend fun imdbToTvdbSeries(imdbId: String): String? {
            calls += "imdbToTvdbSeries:$imdbId"
            return if (imdbToTvdbSeriesResults.containsKey(imdbId)) {
                imdbToTvdbSeriesResults[imdbId]
            } else {
                imdbToTvdbSeriesResult
            }
        }

        override suspend fun tvdbSeriesToImdb(tvdbId: String): String? {
            calls += "tvdbSeriesToImdb:$tvdbId"
            return tvdbSeriesToImdbResult
        }
    }
}
