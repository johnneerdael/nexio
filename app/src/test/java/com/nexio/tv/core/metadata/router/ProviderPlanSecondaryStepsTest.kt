package com.nexio.tv.core.metadata.router

import com.nexio.tv.core.integration.KitsuApiShapes
import com.nexio.tv.core.integration.PosterApiShapes
import com.nexio.tv.core.integration.TmdbApiShapes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderPlanSecondaryStepsTest {
    private val executor = ProviderPlanExecutor()

    @Test
    fun `TMDB movie DETAIL_SECONDARY plan owns rich secondary steps`() {
        val plan = executor.buildPlan(tmdbRoute(MetadataMediaKind.MOVIE), MetadataDepth.DETAIL_SECONDARY)

        assertStep(plan, TmdbApiShapes.MOVIE_CORE, MetadataPrimaryProvider.TMDB, ProviderPlanRole.PRIMARY_CORE)
        assertStep(plan, TmdbApiShapes.MOVIE_VIDEOS, MetadataPrimaryProvider.TMDB, ProviderPlanRole.MEDIA)
        assertStep(plan, TmdbApiShapes.MOVIE_REVIEWS, MetadataPrimaryProvider.TMDB, ProviderPlanRole.SECONDARY)
        assertStep(plan, TmdbApiShapes.MOVIE_RECOMMENDATIONS, MetadataPrimaryProvider.TMDB, ProviderPlanRole.SECONDARY)
        assertOptionalPosterSteps(plan)
    }

    @Test
    fun `TMDB series DETAIL_SECONDARY plan owns rich secondary steps`() {
        val plan = executor.buildPlan(tmdbRoute(MetadataMediaKind.SERIES), MetadataDepth.DETAIL_SECONDARY)

        assertStep(plan, TmdbApiShapes.TV_CORE, MetadataPrimaryProvider.TMDB, ProviderPlanRole.PRIMARY_CORE)
        assertStep(plan, TmdbApiShapes.TV_VIDEOS, MetadataPrimaryProvider.TMDB, ProviderPlanRole.MEDIA)
        assertStep(plan, TmdbApiShapes.TV_REVIEWS, MetadataPrimaryProvider.TMDB, ProviderPlanRole.SECONDARY)
        assertStep(plan, TmdbApiShapes.TV_RECOMMENDATIONS, MetadataPrimaryProvider.TMDB, ProviderPlanRole.SECONDARY)
        assertOptionalPosterSteps(plan)
    }

    @Test
    fun `TMDB series DETAIL_SECONDARY plan owns selected season video step`() {
        val plan = executor.buildPlan(
            tmdbRoute(
                mediaKind = MetadataMediaKind.SERIES,
                seasonNumber = 2
            ),
            MetadataDepth.DETAIL_SECONDARY
        )

        assertStep(plan, TmdbApiShapes.TV_VIDEOS, MetadataPrimaryProvider.TMDB, ProviderPlanRole.MEDIA)
        assertStep(plan, TmdbApiShapes.SEASON_VIDEOS, MetadataPrimaryProvider.TMDB, ProviderPlanRole.MEDIA)
    }

    @Test
    fun `Kitsu DETAIL_SECONDARY plan owns rich secondary steps`() {
        val plan = executor.buildPlan(kitsuRoute(), MetadataDepth.DETAIL_SECONDARY)

        assertStep(plan, KitsuApiShapes.ANIME_CORE, MetadataPrimaryProvider.KITSU, ProviderPlanRole.PRIMARY_CORE)
        assertStep(plan, KitsuApiShapes.ANIME_EPISODES, MetadataPrimaryProvider.KITSU, ProviderPlanRole.SECONDARY)
        assertStep(plan, KitsuApiShapes.CASTINGS, MetadataPrimaryProvider.KITSU, ProviderPlanRole.SECONDARY)
        assertStep(plan, KitsuApiShapes.ANIME_STAFF, MetadataPrimaryProvider.KITSU, ProviderPlanRole.SECONDARY)
        assertStep(plan, KitsuApiShapes.ANIME_PRODUCTIONS, MetadataPrimaryProvider.KITSU, ProviderPlanRole.SECONDARY)
        assertStep(plan, KitsuApiShapes.MEDIA_RELATIONSHIPS, MetadataPrimaryProvider.KITSU, ProviderPlanRole.SECONDARY)
        assertOptionalPosterSteps(plan)
    }

    private fun assertStep(
        plan: ProviderExecutionPlan,
        apiShapeId: String,
        provider: MetadataPrimaryProvider,
        role: ProviderPlanRole
    ) {
        assertTrue(
            "expected $provider/$role step $apiShapeId in ${plan.steps}",
            plan.steps.any { step ->
                step.apiShapeId == apiShapeId &&
                    step.provider == provider &&
                    step.role == role &&
                    step.required
            }
        )
    }

    private fun assertOptionalPosterSteps(plan: ProviderExecutionPlan) {
        assertOptionalStep(plan, PosterApiShapes.RPDB_POSTER_TEMPLATE, MetadataPrimaryProvider.RPDB)
        assertOptionalStep(plan, PosterApiShapes.TOP_POSTERS_POSTER_TEMPLATE, MetadataPrimaryProvider.TOP_POSTERS)
    }

    private fun assertOptionalStep(
        plan: ProviderExecutionPlan,
        apiShapeId: String,
        provider: MetadataPrimaryProvider
    ) {
        val step = plan.steps.single { it.apiShapeId == apiShapeId }
        assertEquals(provider, step.provider)
        assertEquals(ProviderPlanRole.ARTWORK, step.role)
        assertEquals(false, step.required)
    }

    private fun tmdbRoute(
        mediaKind: MetadataMediaKind,
        seasonNumber: Int? = null
    ): MetadataRoute =
        MetadataRoute(
            provider = MetadataPrimaryProvider.TMDB,
            parentId = "tmdb:603",
            mediaKind = mediaKind,
            reason = MetadataDecisionReason.PROVIDER_NATIVE_DIRECT,
            sourceContext = MetadataSourceContext(),
            seasonNumber = seasonNumber,
            targetIds = mapOf(MetadataPrimaryProvider.TMDB to "603"),
            trace = emptyList()
        )

    private fun kitsuRoute(): MetadataRoute =
        MetadataRoute(
            provider = MetadataPrimaryProvider.KITSU,
            parentId = "kitsu:1",
            mediaKind = MetadataMediaKind.ANIME,
            reason = MetadataDecisionReason.KITSU_PREFIX_DIRECT,
            sourceContext = MetadataSourceContext(),
            targetIds = mapOf(MetadataPrimaryProvider.KITSU to "1"),
            trace = emptyList()
        )
}
