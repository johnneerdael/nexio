package com.nexio.tv.core.metadata.router

import com.nexio.tv.core.integration.KitsuApiShapes
import com.nexio.tv.core.integration.PosterApiShapes
import com.nexio.tv.core.integration.TmdbApiShapes
import com.nexio.tv.core.integration.TraktApiShapes
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
        // seasonNumber may be null for providers that support unconstrained season fetches
        // (e.g. Kitsu returns all episodes when no season filter is applied).
        // Non-Kitsu providers (TMDB, TVDB) still require a seasonNumber at SEASON depth —
        // validated per-provider inside tmdbSteps / tvdbSteps.
        // The previously blanket check(seasonNumber != null) at the buildPlan level was removed
        // as part of the anime-season-projection-phase0 fix — it blocked unconstrained Kitsu routes.
        // Enforcement is now delegated per-provider in tmdbSteps and tvdbSteps.

        if (depth == MetadataDepth.PLAYER) {
            return ProviderExecutionPlan(route = route, depth = depth, steps = emptyList())
        }

        objectEntitySteps(route, depth)?.let { steps ->
            return ProviderExecutionPlan(route = route, depth = depth, steps = steps)
        }

        val steps = when (route.provider) {
            MetadataPrimaryProvider.TMDB -> tmdbSteps(route, depth)
            MetadataPrimaryProvider.TVDB -> tvdbSteps(route, depth)
            MetadataPrimaryProvider.KITSU -> kitsuSteps(route, depth)
            // TODO: integrate TRAKT/IMDB/SIMKL provider execution adapters per future phase plan.
            // For now treat them as having no allowed mediaKinds so unknown-kind validation
            // surfaces the canonical "Invalid mediaKind" error before the missing-adapter check.
            MetadataPrimaryProvider.TRAKT,
            MetadataPrimaryProvider.IMDB,
            MetadataPrimaryProvider.SIMKL -> {
                validateMediaKind(route)
                error("No provider execution adapter for ${route.provider}")
            }
            // RPDB and TOP_POSTERS are artwork-only — they are not metadata routing providers
            // and should never appear as a route.provider in the execution plan.
            // validateMediaKind is called first so that unknown-kind validation fires before
            // the artwork-only check, keeping the test contract consistent with TRAKT/IMDB/SIMKL.
            MetadataPrimaryProvider.RPDB,
            MetadataPrimaryProvider.TOP_POSTERS -> {
                validateMediaKind(route)
                error("${route.provider} is an artwork-only provider and cannot be used as a metadata routing provider")
            }
        }

        return ProviderExecutionPlan(route = route, depth = depth, steps = steps)
    }

    private fun objectEntitySteps(route: MetadataRoute, depth: MetadataDepth): List<ProviderPlanStep>? {
        if (depth != MetadataDepth.DETAIL_SECONDARY && depth != MetadataDepth.DETAIL_FULL) return null
        val parts = route.parentId.split(":")
        if (parts.size < 3) return null
        val provider = parts[0].lowercase()
        val kind = parts[1].lowercase()
        val entityToken = parts.getOrNull(2).orEmpty()
        return when {
            provider == "tmdb" && route.provider == MetadataPrimaryProvider.TMDB && kind == "person" ->
                listOf(
                    step(
                        when {
                            route.sourceContext.itemType == PERSON_CREW_ITEM_TYPE -> TmdbApiShapes.PERSON_COMBINED_CREDITS
                            entityToken.toIntOrNull() == null -> TmdbApiShapes.PERSON_FIND_BY_NAME
                            else -> TmdbApiShapes.PERSON_DETAIL
                        },
                        MetadataPrimaryProvider.TMDB,
                        ProviderPlanRole.SECONDARY
                    )
                )
            provider == "tmdb" && route.provider == MetadataPrimaryProvider.TMDB && kind == "company" ->
                listOf(
                    step(
                        if (entityToken.toIntOrNull() == null) {
                            TmdbApiShapes.COMPANY_FIND_BY_NAME
                        } else {
                            TmdbApiShapes.COMPANY_DETAIL
                        },
                        MetadataPrimaryProvider.TMDB,
                        ProviderPlanRole.SECONDARY
                    )
                )
            provider == "tmdb" && route.provider == MetadataPrimaryProvider.TMDB && (kind == "network" || kind == "org") ->
                listOf(step(TmdbApiShapes.NETWORK_DETAIL, MetadataPrimaryProvider.TMDB, ProviderPlanRole.SECONDARY))
            provider == "tvdb" && route.provider == MetadataPrimaryProvider.TVDB && kind == "person" ->
                listOf(step(TvdbApiShapes.PERSON_EXTENDED, MetadataPrimaryProvider.TVDB, ProviderPlanRole.SECONDARY))
            else -> null
        }
    }

    private fun tmdbSteps(route: MetadataRoute, depth: MetadataDepth): List<ProviderPlanStep> {
        validateMediaKind(route, MetadataMediaKind.MOVIE, MetadataMediaKind.SERIES)
        check(depth != MetadataDepth.SEASON || route.mediaKind == MetadataMediaKind.SERIES) {
            "TMDB SEASON provider plan requires SERIES mediaKind"
        }
        check(depth != MetadataDepth.SEASON || route.seasonNumber != null) {
            "TMDB SEASON provider plan requires seasonNumber"
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

        if (depth == MetadataDepth.DETAIL_MEDIA || depth == MetadataDepth.DETAIL_SECONDARY || depth == MetadataDepth.DETAIL_FULL) {
            if (isSeries && route.seasonNumber != null) {
                steps += step(
                    apiShapeId = TmdbApiShapes.SEASON_VIDEOS,
                    provider = MetadataPrimaryProvider.TMDB,
                    role = ProviderPlanRole.MEDIA
                )
            }
            steps += step(
                apiShapeId = if (isSeries) TmdbApiShapes.TV_VIDEOS else TmdbApiShapes.MOVIE_VIDEOS,
                provider = MetadataPrimaryProvider.TMDB,
                role = ProviderPlanRole.MEDIA
            )
        }

        if (depth == MetadataDepth.DETAIL_SECONDARY || depth == MetadataDepth.DETAIL_FULL) {
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
            // F-05-02: when an IMDB id is available on the route, append a Trakt comments step
            // alongside the TMDB reviews step. TraktReviewMetadataAdapter handles dispatch and
            // emits a REVIEWS candidate the resolver merges into the aggregated review page.
            if (route.targetIds.containsKey(MetadataPrimaryProvider.IMDB)) {
                steps += step(
                    apiShapeId = if (isSeries) TraktApiShapes.SHOW_COMMENTS else TraktApiShapes.MOVIE_COMMENTS,
                    provider = MetadataPrimaryProvider.TRAKT,
                    role = ProviderPlanRole.SECONDARY,
                    required = false
                )
            }
        }

        if (depth == MetadataDepth.DETAIL_CORE ||
            depth == MetadataDepth.DETAIL_MEDIA ||
            depth == MetadataDepth.DETAIL_SECONDARY ||
            depth == MetadataDepth.DETAIL_FULL) {
            steps += posterSteps()
        }

        return steps
    }

    private fun tvdbSteps(route: MetadataRoute, depth: MetadataDepth): List<ProviderPlanStep> {
        validateMediaKind(route, MetadataMediaKind.SERIES)
        check(depth != MetadataDepth.SEASON || route.seasonNumber != null) {
            "TVDB SEASON provider plan requires seasonNumber"
        }

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

        if (depth == MetadataDepth.DETAIL_MEDIA ||
            depth == MetadataDepth.DETAIL_SECONDARY ||
            depth == MetadataDepth.DETAIL_FULL) {
            steps += step(
                apiShapeId = TvdbApiShapes.TV_TRAILERS,
                provider = MetadataPrimaryProvider.TVDB,
                role = ProviderPlanRole.MEDIA
            )
        }

        if (depth == MetadataDepth.DETAIL_CORE ||
            depth == MetadataDepth.DETAIL_MEDIA ||
            depth == MetadataDepth.DETAIL_SECONDARY ||
            depth == MetadataDepth.DETAIL_FULL) {
            steps += posterSteps()
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

        if (depth == MetadataDepth.SEASON || depth == MetadataDepth.DETAIL_SECONDARY || depth == MetadataDepth.DETAIL_FULL) {
            steps += step(
                apiShapeId = KitsuApiShapes.ANIME_EPISODES,
                provider = MetadataPrimaryProvider.KITSU,
                role = if (depth == MetadataDepth.SEASON) ProviderPlanRole.SEASON else ProviderPlanRole.SECONDARY
            )
        }

        if (depth == MetadataDepth.DETAIL_SECONDARY || depth == MetadataDepth.DETAIL_FULL) {
            steps += step(KitsuApiShapes.CASTINGS, MetadataPrimaryProvider.KITSU, ProviderPlanRole.SECONDARY)
            steps += step(KitsuApiShapes.ANIME_STAFF, MetadataPrimaryProvider.KITSU, ProviderPlanRole.SECONDARY)
            steps += step(KitsuApiShapes.ANIME_PRODUCTIONS, MetadataPrimaryProvider.KITSU, ProviderPlanRole.SECONDARY)
            steps += step(KitsuApiShapes.MEDIA_RELATIONSHIPS, MetadataPrimaryProvider.KITSU, ProviderPlanRole.SECONDARY)
        }

        if (depth == MetadataDepth.DETAIL_CORE ||
            depth == MetadataDepth.DETAIL_MEDIA ||
            depth == MetadataDepth.DETAIL_SECONDARY ||
            depth == MetadataDepth.DETAIL_FULL) {
            steps += posterSteps()
        }

        return steps
    }

    private fun posterSteps(): List<ProviderPlanStep> = listOf(
        step(
            apiShapeId = PosterApiShapes.RPDB_POSTER_TEMPLATE,
            provider = MetadataPrimaryProvider.RPDB,
            role = ProviderPlanRole.ARTWORK,
            required = false  // poster providers are optional — no API key configured = no candidate
        ),
        step(
            apiShapeId = PosterApiShapes.TOP_POSTERS_POSTER_TEMPLATE,
            provider = MetadataPrimaryProvider.TOP_POSTERS,
            role = ProviderPlanRole.ARTWORK,
            required = false
        )
    )

    private fun validateMediaKind(route: MetadataRoute, vararg allowed: MetadataMediaKind) {
        check(route.mediaKind in allowed) {
            "Invalid mediaKind ${route.mediaKind} for provider ${route.provider}; expected ${allowed.joinToString()}"
        }
    }

    private companion object {
        const val PERSON_CREW_ITEM_TYPE = "person_crew"
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
