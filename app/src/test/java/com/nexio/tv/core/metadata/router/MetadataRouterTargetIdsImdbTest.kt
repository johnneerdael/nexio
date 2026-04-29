package com.nexio.tv.core.metadata.router

import com.nexio.tv.core.integration.RecordingTraceSink
import com.nexio.tv.core.trace.TraceMetadataEvents
import com.nexio.tv.data.integration.tmdb.TmdbExternalIdLookupProvider
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.ProviderIds
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Verifies MetadataRouter populates MetadataRoute.targetIds with cross-provider IMDB ids
 * so adapters that prefer IMDB (Trakt, etc.) can operate without a second
 * identity-resolution hop on TMDB-primary routes (F-05-02).
 */
class MetadataRouterTargetIdsImdbTest {

    @Test
    fun `tmdb-primary movie route surfaces IMDB id from external-id lookup`() = runTest {
        val lookup = FakeTmdbExternalIdLookup(
            imdbForMovie = mapOf(550 to "tt0137523")
        )
        val router = router(lookup = lookup)

        val route = router.route(request("tmdb:550", ContentType.MOVIE))

        assertEquals("tmdb:550", route.targetIds[MetadataPrimaryProvider.TMDB])
        assertEquals("tt0137523", route.targetIds[MetadataPrimaryProvider.IMDB])
        assertEquals(listOf(LookupCall.FindImdb(550, "movie")), lookup.calls)
    }

    @Test
    fun `tmdb-primary route uses movie media type for movie content`() = runTest {
        // A series-typed contentId on a TMDB scheme conflicts with the native MOVIE type
        // and routes to TVDB, so cross-provider IMDB lookup only fires for MOVIE-typed
        // routes today. This test pins the media-type derivation for movie content.
        val lookup = FakeTmdbExternalIdLookup(
            imdbForMovie = mapOf(680 to "tt0110912")
        )
        val router = router(lookup = lookup)

        val route = router.route(request("tmdb:680", ContentType.MOVIE))

        assertEquals(MetadataPrimaryProvider.TMDB, route.provider)
        assertEquals("tt0110912", route.targetIds[MetadataPrimaryProvider.IMDB])
        assertEquals(listOf(LookupCall.FindImdb(680, "movie")), lookup.calls)
    }

    @Test
    fun `imdb-prefixed contentId surfaces IMDB id and attempts TMDB target lookup`() = runTest {
        val lookup = FakeTmdbExternalIdLookup()
        val router = router(lookup = lookup)

        val route = router.route(request("imdb:tt0133093", ContentType.MOVIE))

        // Even when IMDb id can't be mapped to Kitsu, the IMDB id is still surfaced.
        assertEquals("tt0133093", route.targetIds[MetadataPrimaryProvider.IMDB])
        assertEquals(listOf(LookupCall.FindTmdb("tt0133093", "movie")), lookup.calls)
    }

    @Test
    fun `addon preview stable TMDB id wins over raw IMDB movie content id`() = runTest {
        val lookup = FakeTmdbExternalIdLookup()
        val router = router(lookup = lookup)

        val route = router.route(
            request(
                id = "tt12042730",
                type = ContentType.MOVIE,
                sourceContext = MetadataSourceContext(
                    previewSourceRole = SourceRole.ADDON_PREVIEW,
                    previewStableIds = ProviderIds(
                        imdb = "tt12042730",
                        tmdb = "687163"
                    )
                )
            )
        )

        assertEquals(MetadataPrimaryProvider.TMDB, route.provider)
        assertEquals("tmdb:687163", route.targetIds[MetadataPrimaryProvider.TMDB])
        assertEquals("tt12042730", route.targetIds[MetadataPrimaryProvider.IMDB])
        assertEquals(false, route.targetIdRequiresIdentityResolution)
        assertEquals(emptyList<LookupCall>(), lookup.calls)
    }

    @Test
    fun `addon preview stable TVDB id wins over raw IMDB series content id`() = runTest {
        val lookup = FakeTmdbExternalIdLookup()
        val router = router(lookup = lookup)

        val route = router.route(
            request(
                id = "tt0903747",
                type = ContentType.SERIES,
                sourceContext = MetadataSourceContext(
                    previewSourceRole = SourceRole.ADDON_PREVIEW,
                    previewStableIds = ProviderIds(
                        imdb = "tt0903747",
                        tvdb = "81189"
                    )
                )
            )
        )

        assertEquals(MetadataPrimaryProvider.TVDB, route.provider)
        assertEquals("tvdb:81189", route.targetIds[MetadataPrimaryProvider.TVDB])
        assertEquals("tt0903747", route.targetIds[MetadataPrimaryProvider.IMDB])
        assertEquals(false, route.targetIdRequiresIdentityResolution)
        assertEquals(emptyList<LookupCall>(), lookup.calls)
    }

    @Test
    fun `raw IMDB movie addon content id resolves TMDB target through external-id lookup`() = runTest {
        val lookup = FakeTmdbExternalIdLookup(
            tmdbForImdb = mapOf("tt12042730" to 687163)
        )
        val router = router(lookup = lookup)

        val route = router.route(
            request(
                id = "tt12042730",
                type = ContentType.MOVIE,
                sourceContext = MetadataSourceContext(
                    previewSourceRole = SourceRole.ADDON_PREVIEW,
                    previewStableIds = ProviderIds(imdb = "tt12042730")
                )
            )
        )

        assertEquals(MetadataPrimaryProvider.TMDB, route.provider)
        assertEquals("tmdb:687163", route.targetIds[MetadataPrimaryProvider.TMDB])
        assertEquals("tt12042730", route.targetIds[MetadataPrimaryProvider.IMDB])
        assertEquals(false, route.targetIdRequiresIdentityResolution)
        assertEquals(listOf(LookupCall.FindTmdb("tt12042730", "movie")), lookup.calls)
    }

    @Test
    fun `malformed addon preview provider stable ids are ignored`() = runTest {
        val lookup = FakeTmdbExternalIdLookup()
        val router = router(lookup = lookup)

        val route = router.route(
            request(
                id = "tt0903747",
                type = ContentType.SERIES,
                sourceContext = MetadataSourceContext(
                    previewSourceRole = SourceRole.ADDON_PREVIEW,
                    previewStableIds = ProviderIds(
                        imdb = "tt0903747",
                        tvdb = "tt-not-tvdb",
                        tmdb = "tt-not-tmdb"
                    )
                )
            )
        )

        assertEquals(MetadataPrimaryProvider.TVDB, route.provider)
        assertEquals("tt0903747", route.targetIds[MetadataPrimaryProvider.IMDB])
        assertNull(route.targetIds[MetadataPrimaryProvider.TVDB])
        assertNull(route.targetIds[MetadataPrimaryProvider.TMDB])
        assertEquals(true, route.targetIdRequiresIdentityResolution)
        assertEquals(emptyList<LookupCall>(), lookup.calls)
    }

    @Test
    fun `raw IMDB series without TVDB stable id remains identity resolution required`() = runTest {
        val lookup = FakeTmdbExternalIdLookup()
        val router = router(lookup = lookup)

        val route = router.route(request("tt0903747", ContentType.SERIES))

        assertEquals(MetadataPrimaryProvider.TVDB, route.provider)
        assertEquals("tt0903747", route.targetIds[MetadataPrimaryProvider.IMDB])
        assertNull(route.targetIds[MetadataPrimaryProvider.TVDB])
        assertEquals(true, route.targetIdRequiresIdentityResolution)
        assertEquals(emptyList<LookupCall>(), lookup.calls)
    }

    @Test
    fun `tmdb route with no IMDB match leaves IMDB key absent`() = runTest {
        val lookup = FakeTmdbExternalIdLookup() // returns null for everything
        val router = router(lookup = lookup)

        val route = router.route(request("tmdb:99999999", ContentType.MOVIE))

        assertEquals("tmdb:99999999", route.targetIds[MetadataPrimaryProvider.TMDB])
        assertNull(route.targetIds[MetadataPrimaryProvider.IMDB])
    }

    @Test
    fun `tmdb route works without injected lookup provider`() = runTest {
        // No lookup wired - prior behavior: only the primary provider's id is present.
        val router = MetadataRouter(
            normalizer = MetadataRequestNormalizer(traceEvents = noopEvents()),
            animeIdentityIndex = InMemoryAnimeIdentityIndex(),
            idMappingStore = InMemoryIdMappingStore(),
            traceEvents = noopEvents()
        )

        val route = router.route(request("tmdb:550", ContentType.MOVIE))

        assertEquals("tmdb:550", route.targetIds[MetadataPrimaryProvider.TMDB])
        assertNull(route.targetIds[MetadataPrimaryProvider.IMDB])
    }

    private fun router(lookup: TmdbExternalIdLookupProvider): MetadataRouter = MetadataRouter(
        normalizer = MetadataRequestNormalizer(traceEvents = noopEvents()),
        animeIdentityIndex = InMemoryAnimeIdentityIndex(),
        idMappingStore = InMemoryIdMappingStore(),
        traceEvents = noopEvents(),
        tmdbExternalIdLookup = lookup
    )

    private fun noopEvents() = TraceMetadataEvents(RecordingTraceSink()) { null }

    private fun request(
        id: String,
        type: ContentType,
        sourceContext: MetadataSourceContext = MetadataSourceContext()
    ): MetadataRequest = MetadataRequest(
        contentId = id,
        contentType = type,
        sourceContext = sourceContext,
        depth = MetadataDepth.DETAIL_CORE
    )

    private sealed class LookupCall {
        data class FindImdb(val tmdbId: Int, val mediaType: String) : LookupCall()
        data class FindTmdb(val imdbId: String, val mediaType: String) : LookupCall()
    }

    private class FakeTmdbExternalIdLookup(
        private val imdbForMovie: Map<Int, String> = emptyMap(),
        private val imdbForTv: Map<Int, String> = emptyMap(),
        private val tmdbForImdb: Map<String, Int> = emptyMap()
    ) : TmdbExternalIdLookupProvider {
        val calls = mutableListOf<LookupCall>()

        override suspend fun findTmdbIdByImdbId(imdbId: String, mediaType: String): Int? {
            calls += LookupCall.FindTmdb(imdbId, mediaType)
            return tmdbForImdb[imdbId]
        }

        override suspend fun findImdbIdByTmdbId(tmdbId: Int, mediaType: String): String? {
            calls += LookupCall.FindImdb(tmdbId, mediaType)
            return when (mediaType) {
                "tv" -> imdbForTv[tmdbId]
                else -> imdbForMovie[tmdbId]
            }
        }
    }
}
