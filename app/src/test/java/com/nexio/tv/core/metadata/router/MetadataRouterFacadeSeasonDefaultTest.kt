package com.nexio.tv.core.metadata.router

import com.nexio.tv.core.integration.RecordingTraceSink
import com.nexio.tv.core.trace.TraceMetadataEvents
import com.nexio.tv.core.tvdb.TvMetadataRequest
import com.nexio.tv.domain.model.ContentType
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the routing contract that standard TV routes use TMDB's season-episode behavior.
 */
class MetadataRouterFacadeSeasonDefaultTest {

    /**
     * TMDB requires a season at SEASON depth. When no caller hint exists for standard TV/SERIES,
     * the facade defaults the provider plan to season 1.
     */
    @Test
    fun `fetchTvEpisodeEnrichment defaults standard tv tmdb episode route to season 1 when no season hint is provided`() = runTest {
        // Capture the route passed to providerPlanExecutor.buildPlan so we can assert on its seasonNumber.
        val capturedRoute = slot<MetadataRoute>()
        val mockPlanExecutor = mockk<ProviderPlanExecutor>()
        coEvery { mockPlanExecutor.buildPlan(capture(capturedRoute), any()) } returns ProviderExecutionPlan(
            route = MetadataRoute(
                provider = MetadataPrimaryProvider.TMDB,
                parentId = "tmdb:71446",
                mediaKind = MetadataMediaKind.SERIES,
                reason = MetadataDecisionReason.PROVIDER_NATIVE_DIRECT,
                sourceContext = MetadataSourceContext(),
                targetIds = mapOf(MetadataPrimaryProvider.TMDB to "tmdb:71446"),
                trace = emptyList(),
                seasonNumber = 1
            ),
            depth = MetadataDepth.SEASON,
            steps = emptyList()
        )

        // ProviderPlanRunner returns an empty result (no adapters registered).
        val mockPlanRunner = mockk<ProviderPlanRunner>()
        coEvery { mockPlanRunner.run(any()) } returns ProviderPlanRunResult(
            route = MetadataRoute(
                provider = MetadataPrimaryProvider.TMDB,
                parentId = "tmdb:71446",
                mediaKind = MetadataMediaKind.SERIES,
                reason = MetadataDecisionReason.PROVIDER_NATIVE_DIRECT,
                sourceContext = MetadataSourceContext(),
                targetIds = mapOf(MetadataPrimaryProvider.TMDB to "tmdb:71446"),
                trace = emptyList(),
                seasonNumber = 1
            ),
            depth = MetadataDepth.SEASON,
            primaryCandidate = MetadataCandidate(
                provider = MetadataPrimaryProvider.TMDB,
                fields = emptyMap()
            ),
            secondaryCandidates = emptyList(),
            stepResults = emptyList(),
            trace = emptyList()
        )

        val router = MetadataRouter(
            normalizer = MetadataRequestNormalizer(
                traceEvents = TraceMetadataEvents(RecordingTraceSink()) { null }
            ),
            animeIdentityIndex = InMemoryAnimeIdentityIndex(),
            idMappingStore = InMemoryIdMappingStore(),
            traceEvents = TraceMetadataEvents(RecordingTraceSink()) { null }
        )
        val identityResolver = MetadataIdentityResolver(
            lookup = object : MetadataIdentityResolver.Lookup {
                override suspend fun tmdbToTvdb(tmdbId: String): String? = null
                override suspend fun tvdbToTmdb(tvdbId: String): String? = null
            }
        )

        val facade = MetadataRouterFacade(
            router = router,
            providerPlanExecutor = mockPlanExecutor,
            resolverOrchestrator = ResolverOrchestrator(),
            identityResolver = identityResolver,
            providerPlanRunner = mockPlanRunner,
            fieldResolver = FieldResolver(),
            traceEvents = TraceMetadataEvents(RecordingTraceSink()) { null }
        )

        val metadataRequest = MetadataRequest(
            contentId = "tmdb:71446",
            contentType = ContentType.SERIES,
            sourceContext = MetadataSourceContext(),
            seasonNumber = null  // no season hint on the metadata request
        )
        val tvRequest = TvMetadataRequest(
            contentId = "tmdb:71446",
            contentType = ContentType.SERIES,
            seasonNumbers = emptyList()  // no season hint on the TV request
        )

        facade.fetchTvEpisodeEnrichment(
            metadataRequest = metadataRequest,
            tvRequest = tvRequest
        )

        assertEquals(
            "fetchTvEpisodeEnrichment must default standard TV TMDB episode routing to season 1 " +
                "when no season hint is provided",
            1,
            capturedRoute.captured.seasonNumber
        )
        assertEquals(MetadataPrimaryProvider.TMDB, capturedRoute.captured.provider)
        assertEquals(MetadataMediaKind.SERIES, capturedRoute.captured.mediaKind)
        assertEquals("tmdb:71446", capturedRoute.captured.targetIds[MetadataPrimaryProvider.TMDB])
        assertEquals(false, capturedRoute.captured.targetIdRequiresIdentityResolution)
    }
}
