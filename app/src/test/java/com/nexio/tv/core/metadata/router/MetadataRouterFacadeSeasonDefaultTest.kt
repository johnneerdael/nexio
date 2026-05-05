package com.nexio.tv.core.metadata.router

import com.nexio.tv.core.integration.RecordingTraceSink
import com.nexio.tv.core.trace.TraceMetadataEvents
import com.nexio.tv.core.tvdb.TvMetadataRequest
import com.nexio.tv.domain.model.ContentType
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the contract that [MetadataRouterFacade.fetchTvEpisodeEnrichment] must NOT default
 * the episode-enrichment season to 1 when both:
 *   - [TvMetadataRequest.seasonNumbers] is empty, and
 *   - [MetadataRequest.seasonNumber] is null.
 *
 * Before the fix (line 648: `?: 1`), the captured route.seasonNumber was always 1.
 * After the fix it must be null so that KitsuMetadataService receives an unconstrained
 * season hint and returns all episodes (e.g. all 25 MHA Season 3 episodes).
 *
 * This test is expected to FAIL before the fix in MetadataRouterFacade.kt and PASS after.
 */
class MetadataRouterFacadeSeasonDefaultTest {

    /**
     * When both [TvMetadataRequest.seasonNumbers] and [MetadataRequest.seasonNumber] are absent,
     * the route built inside [fetchTvEpisodeEnrichment] must carry seasonNumber = null —
     * NOT the old defaulted value of 1.
     */
    @Test
    fun `fetchTvEpisodeEnrichment must not default seasonNumber to 1 when no season hint is provided`() = runTest {
        // Capture the route passed to providerPlanExecutor.buildPlan so we can assert on its seasonNumber.
        val capturedRoute = slot<MetadataRoute>()
        val mockPlanExecutor = mockk<ProviderPlanExecutor>()
        coEvery { mockPlanExecutor.buildPlan(capture(capturedRoute), any()) } returns ProviderExecutionPlan(
            route = MetadataRoute(
                provider = MetadataPrimaryProvider.KITSU,
                parentId = "kitsu:13881",
                mediaKind = MetadataMediaKind.ANIME,
                reason = MetadataDecisionReason.KITSU_PREFIX_DIRECT,
                sourceContext = MetadataSourceContext(),
                targetIds = mapOf(MetadataPrimaryProvider.KITSU to "kitsu:13881"),
                trace = emptyList(),
                seasonNumber = null
            ),
            depth = MetadataDepth.SEASON,
            steps = emptyList()
        )

        // ProviderPlanRunner returns an empty result (no adapters registered).
        val mockPlanRunner = mockk<ProviderPlanRunner>()
        coEvery { mockPlanRunner.run(any()) } returns ProviderPlanRunResult(
            route = MetadataRoute(
                provider = MetadataPrimaryProvider.KITSU,
                parentId = "kitsu:13881",
                mediaKind = MetadataMediaKind.ANIME,
                reason = MetadataDecisionReason.KITSU_PREFIX_DIRECT,
                sourceContext = MetadataSourceContext(),
                targetIds = mapOf(MetadataPrimaryProvider.KITSU to "kitsu:13881"),
                trace = emptyList(),
                seasonNumber = null
            ),
            depth = MetadataDepth.SEASON,
            primaryCandidate = MetadataCandidate(
                provider = MetadataPrimaryProvider.KITSU,
                fields = emptyMap()
            ),
            secondaryCandidates = emptyList(),
            stepResults = emptyList(),
            trace = emptyList()
        )

        val kitsuRoute = MetadataRoute(
            provider = MetadataPrimaryProvider.KITSU,
            parentId = "kitsu:13881",
            mediaKind = MetadataMediaKind.ANIME,
            reason = MetadataDecisionReason.KITSU_PREFIX_DIRECT,
            sourceContext = MetadataSourceContext(),
            targetIds = mapOf(MetadataPrimaryProvider.KITSU to "kitsu:13881"),
            trace = emptyList(),
            seasonNumber = null
        )

        val mockRouter = mockk<MetadataRouter>()
        coEvery { mockRouter.route(any()) } returns kitsuRoute

        val mockIdentityResolver = mockk<MetadataIdentityResolver>()
        coEvery { mockIdentityResolver.resolve(any()) } returns kitsuRoute

        val facade = MetadataRouterFacade(
            router = mockRouter,
            providerPlanExecutor = mockPlanExecutor,
            resolverOrchestrator = ResolverOrchestrator(),
            identityResolver = mockIdentityResolver,
            providerPlanRunner = mockPlanRunner,
            fieldResolver = FieldResolver(),
            traceEvents = TraceMetadataEvents(RecordingTraceSink()) { null }
        )

        val metadataRequest = MetadataRequest(
            contentId = "kitsu:13881",
            contentType = ContentType.SERIES,
            sourceContext = MetadataSourceContext(),
            seasonNumber = null  // no season hint on the metadata request
        )
        val tvRequest = TvMetadataRequest(
            contentId = "kitsu:13881",
            contentType = ContentType.SERIES,
            seasonNumbers = emptyList()  // no season hint on the TV request
        )

        facade.fetchTvEpisodeEnrichment(
            metadataRequest = metadataRequest,
            tvRequest = tvRequest
        )

        // The captured route is the one that was built inside fetchTvEpisodeEnrichment
        // (from MetadataRequest.copy(seasonNumber = ...)).
        // Before the fix: capturedRoute.captured.seasonNumber == 1  (WRONG — the bug)
        // After the fix:  capturedRoute.captured.seasonNumber == null (CORRECT)
        assertNull(
            "fetchTvEpisodeEnrichment must not default seasonNumber to 1 when no season hint is provided; " +
                "got ${capturedRoute.captured.seasonNumber}",
            capturedRoute.captured.seasonNumber
        )
    }
}
