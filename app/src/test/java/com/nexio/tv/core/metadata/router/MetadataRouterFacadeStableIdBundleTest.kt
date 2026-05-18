package com.nexio.tv.core.metadata.router

import com.nexio.tv.core.integration.RecordingTraceSink
import com.nexio.tv.core.trace.TraceMetadataEvents
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.ProviderId
import com.nexio.tv.domain.model.ProviderIds
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class MetadataRouterFacadeStableIdBundleTest {

    @Test
    fun `preview resolveRequest does not resolve stable id bundle`() = runTest {
        val stableIdBundleResolver = mockk<StableIdBundleResolver>(relaxed = true)
        val facade = facade(stableIdBundleResolver = stableIdBundleResolver)

        facade.resolveRequest(
            MetadataRequest(
                contentId = "tmdb:550",
                contentType = ContentType.MOVIE,
                sourceContext = MetadataSourceContext(),
                depth = MetadataDepth.PREVIEW
            )
        )

        coVerify(exactly = 0) { stableIdBundleResolver.resolve(any()) }
    }

    @Test
    fun `resolveStableIdBundle routes request then resolves bundle from routed and preview context facts`() = runTest {
        val request = MetadataRequest(
            contentId = "tt0137523",
            contentType = ContentType.MOVIE,
            sourceContext = MetadataSourceContext(
                previewSourceProvider = ProviderId.TRAKT.name,
                previewStableIds = ProviderIds(
                    imdb = "tt0137523",
                    tmdb = "550",
                    trakt = "movie-42"
                ),
                previewSourceItemId = "trakt-item-42",
                previewRailSource = "popular-movies"
            ),
            depth = MetadataDepth.DETAIL_CORE
        )
        val route = MetadataRoute(
            provider = MetadataPrimaryProvider.TMDB,
            parentId = "tmdb:550",
            mediaKind = MetadataMediaKind.MOVIE,
            reason = MetadataDecisionReason.PROVIDER_NATIVE_DIRECT,
            sourceContext = request.sourceContext,
            targetIds = mapOf(MetadataPrimaryProvider.TMDB to "tmdb:550"),
            trace = emptyList()
        )
        val router = mockk<MetadataRouter>()
        coEvery { router.route(request) } returns route
        val expectedBundle = StableIdBundle(
            itemKey = "movie:imdb:tt0137523",
            itemType = ContentType.MOVIE,
            canonical = CanonicalStableIds(tmdbMovieId = "550"),
            sidecars = SidecarStableIds(imdbId = "tt0137523"),
            source = request.sourceContext.previewStableIds.toSourceStableIds(
                sourceProvider = ProviderId.TRAKT,
                sourceItemId = "trakt-item-42",
                railId = "popular-movies"
            ),
            evidence = listOf(
                StableIdEvidence(
                    source = "providerLookup.tmdbMovieToImdb",
                    target = "IMDB",
                    networkExecuted = true,
                    resultId = "tt0137523"
                )
            ),
            resolvedAtMs = 123L
        )
        val capturedRequest = mutableListOf<StableIdBundleRequest>()
        val stableIdBundleResolver = mockk<StableIdBundleResolver>()
        coEvery { stableIdBundleResolver.resolve(capture(capturedRequest)) } returns expectedBundle
        val traceSink = RecordingTraceSink()
        val facade = facade(
            router = router,
            stableIdBundleResolver = stableIdBundleResolver,
            traceEvents = TraceMetadataEvents(traceSink) { "s1" }
        )

        val bundle = facade.resolveStableIdBundle(
            request = request,
            trigger = StableIdResolutionTrigger.FOCUSED_HOME_ITEM,
            itemKey = "movie:imdb:tt0137523"
        )

        assertSame(expectedBundle, bundle)
        assertEquals(
            StableIdBundleRequest(
                itemKey = "movie:imdb:tt0137523",
                itemType = ContentType.MOVIE,
                routeProvider = MetadataPrimaryProvider.TMDB,
                knownIds = request.sourceContext.previewStableIds,
                sourceProvider = ProviderId.TRAKT,
                sourceItemId = "trakt-item-42",
                railId = "popular-movies",
                trigger = StableIdResolutionTrigger.FOCUSED_HOME_ITEM
            ),
            capturedRequest.single()
        )
        coVerifyOrder {
            router.route(request)
            stableIdBundleResolver.resolve(any())
        }

        val event = traceSink.events.single { it.eventType == "metadata.stable_id_bundle" }
        val payload = event.payload as Map<*, *>
        assertEquals("movie:imdb:tt0137523", payload["itemKey"])
        assertEquals(ContentType.MOVIE.name, payload["itemType"])
        assertEquals(StableIdBundleStatus.CANONICAL_AND_RATING_READY.name, payload["status"])
        assertEquals(StableIdResolutionTrigger.FOCUSED_HOME_ITEM.name, payload["trigger"])
        assertEquals("550", payload["tmdbMovieId"])
        assertEquals(null, payload["tvdbSeriesId"])
        assertEquals(null, payload["kitsuAnimeId"])
        assertEquals("tt0137523", payload["imdbId"])
        assertEquals(true, payload["networkExecuted"])
        assertEquals(
            listOf(
                mapOf(
                    "source" to "providerLookup.tmdbMovieToImdb",
                    "target" to "IMDB",
                    "networkExecuted" to true,
                    "resultId" to "tt0137523"
                )
            ),
            payload["evidence"]
        )
    }

    @Test
    fun `resolveStableIdBundle with resolved route reuses route without routing request again`() = runTest {
        val request = MetadataRequest(
            contentId = "tt0137523",
            contentType = ContentType.MOVIE,
            sourceContext = MetadataSourceContext(
                previewSourceProvider = ProviderId.TRAKT.name,
                previewStableIds = ProviderIds(
                    imdb = "tt0137523",
                    tmdb = "550",
                    trakt = "movie-42"
                ),
                previewSourceItemId = "trakt-item-42",
                previewRailSource = "popular-movies"
            ),
            depth = MetadataDepth.DETAIL_CORE
        )
        val route = MetadataRoute(
            provider = MetadataPrimaryProvider.TMDB,
            parentId = "tmdb:550",
            mediaKind = MetadataMediaKind.MOVIE,
            reason = MetadataDecisionReason.PROVIDER_NATIVE_DIRECT,
            sourceContext = request.sourceContext,
            targetIds = mapOf(MetadataPrimaryProvider.TMDB to "tmdb:550"),
            trace = emptyList()
        )
        val router = mockk<MetadataRouter>(relaxed = true)
        val expectedBundle = StableIdBundle(
            itemKey = "movie:imdb:tt0137523",
            itemType = ContentType.MOVIE,
            canonical = CanonicalStableIds(tmdbMovieId = "550"),
            sidecars = SidecarStableIds(imdbId = "tt0137523"),
            source = request.sourceContext.previewStableIds.toSourceStableIds(
                sourceProvider = ProviderId.TRAKT,
                sourceItemId = "trakt-item-42",
                railId = "popular-movies"
            ),
            evidence = emptyList(),
            resolvedAtMs = 123L
        )
        val capturedRequest = mutableListOf<StableIdBundleRequest>()
        val stableIdBundleResolver = mockk<StableIdBundleResolver>()
        coEvery { stableIdBundleResolver.resolve(capture(capturedRequest)) } returns expectedBundle
        val facade = facade(
            router = router,
            stableIdBundleResolver = stableIdBundleResolver
        )

        val bundle = facade.resolveStableIdBundle(
            route = route,
            request = request,
            trigger = StableIdResolutionTrigger.VISIBLE_HOME_HYDRATION,
            itemKey = "movie:imdb:tt0137523"
        )

        assertSame(expectedBundle, bundle)
        assertEquals(
            StableIdBundleRequest(
                itemKey = "movie:imdb:tt0137523",
                itemType = ContentType.MOVIE,
                routeProvider = MetadataPrimaryProvider.TMDB,
                knownIds = request.sourceContext.previewStableIds,
                sourceProvider = ProviderId.TRAKT,
                sourceItemId = "trakt-item-42",
                railId = "popular-movies",
                trigger = StableIdResolutionTrigger.VISIBLE_HOME_HYDRATION
            ),
            capturedRequest.single()
        )
        coVerify(exactly = 0) { router.route(any()) }
        coVerify(exactly = 1) { stableIdBundleResolver.resolve(any()) }
    }

    @Test
    fun `series stable bundle uses tmdb tv canonical id when preview ids include tmdb`() = runTest {
        val request = MetadataRequest(
            contentId = "tmdb:71446",
            contentType = ContentType.SERIES,
            sourceContext = MetadataSourceContext(
                previewSourceProvider = ProviderId.TRAKT.name,
                previewStableIds = ProviderIds(
                    tmdb = "71446",
                    tvdb = "81189",
                    imdb = "tt0903747"
                ),
                previewSourceItemId = "trakt-series-42",
                previewRailSource = "popular-series"
            ),
            depth = MetadataDepth.DETAIL_CORE
        )
        val traceSink = RecordingTraceSink()
        val facade = facade(
            router = MetadataRouter(
                normalizer = MetadataRequestNormalizer(
                    traceEvents = TraceMetadataEvents(traceSink) { "s1" }
                ),
                animeIdentityIndex = InMemoryAnimeIdentityIndex(),
                idMappingStore = InMemoryIdMappingStore(),
                traceEvents = TraceMetadataEvents(traceSink) { "s1" }
            ),
            stableIdBundleResolver = StableIdBundleResolver(
                idMappingStore = InMemoryIdMappingStore(),
                lookup = object : StableIdBundleResolver.Lookup {
                    override suspend fun tmdbMovieToImdb(tmdbId: String): String? = null
                    override suspend fun imdbToTmdbMovie(imdbId: String): String? = null
                    override suspend fun tmdbTvToTvdb(tmdbId: String): String? = null
                    override suspend fun tmdbTvToImdb(tmdbId: String): String? = null
                    override suspend fun imdbToTvdbSeries(imdbId: String): String? = null
                    override suspend fun tvdbSeriesToImdb(tvdbId: String): String? = null
                },
                nowEpochMs = { 123L }
            ),
            traceEvents = TraceMetadataEvents(traceSink) { "s1" }
        )

        val bundle = facade.resolveStableIdBundle(
            request = request,
            trigger = StableIdResolutionTrigger.VISIBLE_HOME_HYDRATION,
            itemKey = "series:tmdb:71446"
        )

        assertEquals("71446", bundle.canonical.tmdbTvId)
        assertEquals(null, bundle.canonical.tmdbMovieId)
        assertEquals(null, bundle.canonical.tvdbSeriesId)
        assertEquals("81189", bundle.source.observedIds.tvdb)
        assertEquals("tt0903747", bundle.sidecars.imdbId)

        val routeDecision = traceSink.events.single { it.eventType == "metadata.route_decision" }
        val routePayload = routeDecision.payload as Map<*, *>
        assertEquals(MetadataPrimaryProvider.TMDB.name, routePayload["provider"])
        assertEquals(MetadataMediaKind.SERIES.name, routePayload["mediaKind"])
        assertEquals(false, routePayload["targetIdRequiresIdentityResolution"])
    }

    private fun facade(
        router: MetadataRouter = mockk(relaxed = true),
        stableIdBundleResolver: StableIdBundleResolver = mockk(relaxed = true),
        traceEvents: TraceMetadataEvents = TraceMetadataEvents(RecordingTraceSink()) { null }
    ): MetadataRouterFacade =
        MetadataRouterFacade(
            router = router,
            providerPlanExecutor = ProviderPlanExecutor(),
            resolverOrchestrator = ResolverOrchestrator(),
            identityResolver = MetadataIdentityResolver(object : MetadataIdentityResolver.Lookup {
                override suspend fun tmdbToTvdb(tmdbId: String): String? = null
                override suspend fun tvdbToTmdb(tvdbId: String): String? = null
            }),
            providerPlanRunner = ProviderPlanRunner(emptySet()),
            fieldResolver = FieldResolver(),
            stableIdBundleResolver = stableIdBundleResolver,
            traceEvents = traceEvents
        )
}
