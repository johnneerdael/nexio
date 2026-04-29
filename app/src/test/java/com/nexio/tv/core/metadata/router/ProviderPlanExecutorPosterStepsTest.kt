package com.nexio.tv.core.metadata.router

import com.nexio.tv.core.integration.PosterApiShapes
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F2-T13-A pin: ProviderPlanExecutor MUST append RPDB and TOP_POSTERS poster steps to
 * TMDB/TVDB/KITSU plans for DETAIL_CORE/DETAIL_MEDIA/DETAIL_SECONDARY depths.
 * SEASON depth MUST NOT include poster steps.
 */
class ProviderPlanExecutorPosterStepsTest {

    private val executor = ProviderPlanExecutor()

    @Test
    fun `TMDB DETAIL_CORE plan includes RPDB and TOP_POSTERS poster steps`() {
        val route = stubRoute(MetadataPrimaryProvider.TMDB, MetadataMediaKind.MOVIE)
        val plan = executor.buildPlan(route, MetadataDepth.DETAIL_CORE)

        assertHasPosterStep(plan, MetadataPrimaryProvider.RPDB, PosterApiShapes.RPDB_POSTER_TEMPLATE)
        assertHasPosterStep(plan, MetadataPrimaryProvider.TOP_POSTERS, PosterApiShapes.TOP_POSTERS_POSTER_TEMPLATE)
    }

    @Test
    fun `TMDB DETAIL_MEDIA plan includes both poster steps`() {
        val route = stubRoute(MetadataPrimaryProvider.TMDB, MetadataMediaKind.SERIES)
        val plan = executor.buildPlan(route, MetadataDepth.DETAIL_MEDIA)

        assertHasPosterStep(plan, MetadataPrimaryProvider.RPDB, PosterApiShapes.RPDB_POSTER_TEMPLATE)
        assertHasPosterStep(plan, MetadataPrimaryProvider.TOP_POSTERS, PosterApiShapes.TOP_POSTERS_POSTER_TEMPLATE)
    }

    @Test
    fun `TMDB DETAIL_SECONDARY plan includes both poster steps`() {
        val route = stubRoute(MetadataPrimaryProvider.TMDB, MetadataMediaKind.MOVIE)
        val plan = executor.buildPlan(route, MetadataDepth.DETAIL_SECONDARY)

        assertHasPosterStep(plan, MetadataPrimaryProvider.RPDB, PosterApiShapes.RPDB_POSTER_TEMPLATE)
        assertHasPosterStep(plan, MetadataPrimaryProvider.TOP_POSTERS, PosterApiShapes.TOP_POSTERS_POSTER_TEMPLATE)
    }

    @Test
    fun `TVDB DETAIL_CORE plan includes both poster steps`() {
        val route = stubRoute(MetadataPrimaryProvider.TVDB, MetadataMediaKind.SERIES)
        val plan = executor.buildPlan(route, MetadataDepth.DETAIL_CORE)

        assertHasPosterStep(plan, MetadataPrimaryProvider.RPDB, PosterApiShapes.RPDB_POSTER_TEMPLATE)
        assertHasPosterStep(plan, MetadataPrimaryProvider.TOP_POSTERS, PosterApiShapes.TOP_POSTERS_POSTER_TEMPLATE)
    }

    @Test
    fun `KITSU DETAIL_CORE plan includes both poster steps`() {
        val route = stubRoute(MetadataPrimaryProvider.KITSU, MetadataMediaKind.ANIME)
        val plan = executor.buildPlan(route, MetadataDepth.DETAIL_CORE)

        assertHasPosterStep(plan, MetadataPrimaryProvider.RPDB, PosterApiShapes.RPDB_POSTER_TEMPLATE)
        assertHasPosterStep(plan, MetadataPrimaryProvider.TOP_POSTERS, PosterApiShapes.TOP_POSTERS_POSTER_TEMPLATE)
    }

    @Test
    fun `TMDB SEASON plan does NOT include poster steps`() {
        val route = stubRoute(MetadataPrimaryProvider.TMDB, MetadataMediaKind.SERIES, seasonNumber = 1)
        val plan = executor.buildPlan(route, MetadataDepth.SEASON)

        assertNoPosterSteps(plan)
    }

    @Test
    fun `TVDB SEASON plan does NOT include poster steps`() {
        val route = stubRoute(MetadataPrimaryProvider.TVDB, MetadataMediaKind.SERIES, seasonNumber = 1)
        val plan = executor.buildPlan(route, MetadataDepth.SEASON)

        assertNoPosterSteps(plan)
    }

    private fun stubRoute(
        provider: MetadataPrimaryProvider,
        mediaKind: MetadataMediaKind,
        seasonNumber: Int? = null
    ): MetadataRoute = MetadataRoute(
        provider = provider,
        parentId = "provider:1",
        mediaKind = mediaKind,
        reason = MetadataDecisionReason.PROVIDER_NATIVE_DIRECT,
        sourceContext = MetadataSourceContext(),
        seasonNumber = seasonNumber,
        targetIds = mapOf(provider to "provider:1"),
        targetIdRequiresIdentityResolution = false,
        trace = listOf(
            MetadataRouteTrace(
                MetadataDecisionReason.PROVIDER_NATIVE_DIRECT,
                "stub route"
            )
        )
    )

    private fun assertHasPosterStep(
        plan: ProviderExecutionPlan,
        provider: MetadataPrimaryProvider,
        apiShapeId: String
    ) {
        val match = plan.steps.firstOrNull {
            it.provider == provider && it.apiShapeId == apiShapeId && it.role == ProviderPlanRole.ARTWORK
        }
        assertTrue(
            "expected ARTWORK step for $provider with $apiShapeId in plan; got steps: ${plan.steps}",
            match != null
        )
    }

    private fun assertNoPosterSteps(plan: ProviderExecutionPlan) {
        val posterSteps = plan.steps.filter {
            it.apiShapeId == PosterApiShapes.RPDB_POSTER_TEMPLATE ||
            it.apiShapeId == PosterApiShapes.TOP_POSTERS_POSTER_TEMPLATE
        }
        assertTrue(
            "expected no poster steps in plan; got: $posterSteps",
            posterSteps.isEmpty()
        )
    }
}
