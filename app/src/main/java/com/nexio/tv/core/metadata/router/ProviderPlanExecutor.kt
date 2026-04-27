package com.nexio.tv.core.metadata.router

import com.nexio.tv.core.integration.KitsuApiShapes
import com.nexio.tv.core.integration.TmdbApiShapes
import com.nexio.tv.core.integration.TvdbApiShapes
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProviderPlanExecutor @Inject constructor() {
    fun buildPlan(route: MetadataRoute, depth: MetadataDepth): ProviderExecutionPlan {
        check(!route.targetIdRequiresIdentityResolution) {
            "Provider plan requires identity resolution before execution for route ${route.parentId}"
        }
        check(depth !in unsupportedDepths) {
            "Unsupported provider plan depth $depth"
        }
        check(depth != MetadataDepth.SEASON || route.seasonNumber != null) {
            "SEASON provider plan requires seasonNumber"
        }

        if (depth == MetadataDepth.PLAYER) {
            return ProviderExecutionPlan(route = route, depth = depth, steps = emptyList())
        }

        val steps = when (route.provider) {
            MetadataPrimaryProvider.TMDB -> tmdbSteps(route, depth)
            MetadataPrimaryProvider.TVDB -> tvdbSteps(route, depth)
            MetadataPrimaryProvider.KITSU -> kitsuSteps(route, depth)
        }

        return ProviderExecutionPlan(route = route, depth = depth, steps = steps)
    }

    private fun tmdbSteps(route: MetadataRoute, depth: MetadataDepth): List<ProviderPlanStep> {
        validateMediaKind(route, MetadataMediaKind.MOVIE, MetadataMediaKind.SERIES)
        check(depth != MetadataDepth.SEASON || route.mediaKind == MetadataMediaKind.SERIES) {
            "TMDB SEASON provider plan requires SERIES mediaKind"
        }

        val isSeries = route.mediaKind == MetadataMediaKind.SERIES
        val steps = mutableListOf<ProviderPlanStep>()

        steps += step(
            apiShapeId = if (isSeries) TmdbApiShapes.TV_CORE else TmdbApiShapes.MOVIE_CORE,
            provider = MetadataPrimaryProvider.TMDB,
            role = ProviderPlanRole.PRIMARY_CORE
        )

        if (depth == MetadataDepth.SEASON) {
            steps += step(
                apiShapeId = TmdbApiShapes.SEASON_EPISODES,
                provider = MetadataPrimaryProvider.TMDB,
                role = ProviderPlanRole.SEASON
            )
        }

        if (depth == MetadataDepth.DETAIL_MEDIA || depth == MetadataDepth.DETAIL_SECONDARY) {
            steps += step(
                apiShapeId = if (isSeries) TmdbApiShapes.TV_VIDEOS else TmdbApiShapes.MOVIE_VIDEOS,
                provider = MetadataPrimaryProvider.TMDB,
                role = ProviderPlanRole.MEDIA
            )
        }

        if (depth == MetadataDepth.DETAIL_SECONDARY) {
            steps += step(
                apiShapeId = if (isSeries) TmdbApiShapes.TV_REVIEWS else TmdbApiShapes.MOVIE_REVIEWS,
                provider = MetadataPrimaryProvider.TMDB,
                role = ProviderPlanRole.SECONDARY
            )
            steps += step(
                apiShapeId = if (isSeries) TmdbApiShapes.TV_RECOMMENDATIONS else TmdbApiShapes.MOVIE_RECOMMENDATIONS,
                provider = MetadataPrimaryProvider.TMDB,
                role = ProviderPlanRole.SECONDARY
            )
        }

        return steps
    }

    private fun tvdbSteps(route: MetadataRoute, depth: MetadataDepth): List<ProviderPlanStep> {
        validateMediaKind(route, MetadataMediaKind.SERIES)

        val steps = mutableListOf(
            step(
                apiShapeId = TvdbApiShapes.SERIES_EXTENDED,
                provider = MetadataPrimaryProvider.TVDB,
                role = ProviderPlanRole.PRIMARY_CORE
            )
        )

        if (depth == MetadataDepth.SEASON) {
            steps += step(
                apiShapeId = TvdbApiShapes.SERIES_EPISODES_LANGUAGE,
                provider = MetadataPrimaryProvider.TVDB,
                role = ProviderPlanRole.SEASON
            )
        }

        return steps
    }

    private fun kitsuSteps(route: MetadataRoute, depth: MetadataDepth): List<ProviderPlanStep> {
        validateMediaKind(route, MetadataMediaKind.ANIME)

        val steps = mutableListOf(
            step(
                apiShapeId = KitsuApiShapes.ANIME_CORE,
                provider = MetadataPrimaryProvider.KITSU,
                role = ProviderPlanRole.PRIMARY_CORE
            )
        )

        if (depth == MetadataDepth.SEASON || depth == MetadataDepth.DETAIL_SECONDARY) {
            steps += step(
                apiShapeId = KitsuApiShapes.ANIME_EPISODES,
                provider = MetadataPrimaryProvider.KITSU,
                role = if (depth == MetadataDepth.SEASON) ProviderPlanRole.SEASON else ProviderPlanRole.SECONDARY
            )
        }

        if (depth == MetadataDepth.DETAIL_SECONDARY) {
            steps += step(KitsuApiShapes.CASTINGS, MetadataPrimaryProvider.KITSU, ProviderPlanRole.SECONDARY)
            steps += step(KitsuApiShapes.ANIME_STAFF, MetadataPrimaryProvider.KITSU, ProviderPlanRole.SECONDARY)
            steps += step(KitsuApiShapes.ANIME_PRODUCTIONS, MetadataPrimaryProvider.KITSU, ProviderPlanRole.SECONDARY)
            steps += step(KitsuApiShapes.MEDIA_RELATIONSHIPS, MetadataPrimaryProvider.KITSU, ProviderPlanRole.SECONDARY)
        }

        return steps
    }

    private fun validateMediaKind(route: MetadataRoute, vararg allowed: MetadataMediaKind) {
        check(route.mediaKind in allowed) {
            "Invalid mediaKind ${route.mediaKind} for provider ${route.provider}; expected ${allowed.joinToString()}"
        }
    }

    private companion object {
        val unsupportedDepths = setOf(MetadataDepth.PREVIEW)
    }

    private fun step(
        apiShapeId: String,
        provider: MetadataPrimaryProvider,
        role: ProviderPlanRole,
        required: Boolean = true
    ): ProviderPlanStep =
        ProviderPlanStep(
            apiShapeId = apiShapeId,
            provider = provider,
            role = role,
            required = required
        )
}
