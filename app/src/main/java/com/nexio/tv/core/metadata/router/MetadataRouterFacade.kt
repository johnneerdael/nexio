package com.nexio.tv.core.metadata.router

import com.nexio.tv.core.tmdb.TmdbEnrichment
import com.nexio.tv.core.tvdb.TvEpisodeMetadata
import com.nexio.tv.core.tvdb.TvMetadataDiagnosticEvent
import com.nexio.tv.core.tvdb.TvMetadataDecision
import com.nexio.tv.core.tvdb.TvMetadataDecisionReason
import com.nexio.tv.core.tvdb.TvMetadataEnrichment
import com.nexio.tv.core.tvdb.TvMetadataRequest
import com.nexio.tv.core.tvdb.TvProvider
import com.nexio.tv.core.tvdb.TvSeasonEpisode
import com.nexio.tv.data.integration.metadata.MetadataSecondaryRepository
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.HomeDisplayMetadata
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MetadataRouterFacade @Inject constructor(
    private val router: MetadataRouter,
    private val providerPlanExecutor: ProviderPlanExecutor,
    private val resolverOrchestrator: ResolverOrchestrator,
    private val identityResolver: MetadataIdentityResolver,
    private val providerPlanRunner: ProviderPlanRunner,
    private val fieldResolver: FieldResolver,
    private val metadataSecondaryRepository: MetadataSecondaryRepository? = null
) {
    suspend fun routeRequest(request: MetadataRequest): MetadataRoute {
        val routed = router.route(request)
        val route = identityResolver.resolve(routed)
        if (route.targetIdRequiresIdentityResolution) {
            throw MetadataRouteFailure.IdentityResolutionFailed(route.parentId, route.provider)
        }
        return route
    }

    suspend fun resolveRequest(request: MetadataRequest): MetadataResolutionResult {
        val resolverSchedule = resolverOrchestrator.schedule(request.depth)
        val initialDisplay = request.sourceContext.addonMetadata ?: HomeDisplayMetadata()

        if (request.depth == MetadataDepth.PREVIEW) {
            val document = initialDisplay.toResolvedDocument()
            return MetadataResolutionResult(
                route = null,
                plan = null,
                resolverSchedule = resolverSchedule,
                resolvedDocument = document,
                displayMetadata = initialDisplay,
                trace = emptyList()
            )
        }

        val route = routeRequest(request)
        val plan = providerPlanExecutor.buildPlan(route = route, depth = request.depth)
        val runResult = providerPlanRunner.run(plan)
        val resolvedDocument = fieldResolver.resolve(
            primary = runResult.primaryCandidate,
            secondary = runResult.secondaryCandidates
        )
        val displayMetadata = resolvedDocument.toHomeDisplayMetadata(initialDisplay)

        return MetadataResolutionResult(
            route = route,
            plan = plan,
            resolverSchedule = resolverSchedule,
            resolvedDocument = resolvedDocument,
            displayMetadata = displayMetadata,
            trace = runResult.trace,
            providerRunResult = runResult
        )
    }

    suspend fun fetchTvEnrichment(
        metadataRequest: MetadataRequest,
        tvRequest: TvMetadataRequest
    ): TvMetadataDecision<TvMetadataEnrichment> {
        val result = resolveRequest(metadataRequest)
        return TvMetadataDecision(
            provider = result.route?.provider.toTvProvider(),
            reason = TvMetadataDecisionReason.TVDB_SUCCESS,
            value = result.resolvedDocument.toTvMetadataEnrichment(),
            diagnostics = emptyList()
        )
    }

    /**
     * Routes a TMDB enrichment fetch through the canonical resolve pipeline so that
     * `metadata.route_decision` and `metadata.field_selected` trace events fire, then
     * delegates the actual rich-shape data fetch to [MetadataSecondaryRepository].
     *
     * The resolved document from [resolveRequest] is intentionally discarded — its TMDB
     * carry-set is narrower than the 22-field [TmdbEnrichment] that downstream
     * `enrichMeta(...)` sites depend on. Migrating naively to the resolved document
     * would silently drop director/writer/cast/companies/networks/collection.
     */
    suspend fun fetchTmdbEnrichment(
        metadataRequest: MetadataRequest,
        tmdbId: String,
        contentType: ContentType
    ): TmdbEnrichment? {
        val repo = checkNotNull(metadataSecondaryRepository) {
            "fetchTmdbEnrichment requires MetadataRouterFacade to be constructed with a non-null MetadataSecondaryRepository"
        }
        // Fire canonical trace events via the resolve pipeline.
        resolveRequest(metadataRequest)
        // Delegate to the secondary repository for the rich TMDB shape.
        return repo.fetchTmdbEnrichment(tmdbId, contentType)
    }

    suspend fun fetchTvEpisodeEnrichment(
        metadataRequest: MetadataRequest,
        tvRequest: TvMetadataRequest
    ): TvMetadataDecision<Map<Pair<Int, Int>, TvEpisodeMetadata>> {
        val baseRoute = router.route(
            metadataRequest.copy(
                depth = MetadataDepth.SEASON,
                seasonNumber = tvRequest.seasonNumbers.firstOrNull() ?: metadataRequest.seasonNumber
            )
        )
        val resolvedBaseRoute = identityResolver.resolve(baseRoute)
        if (resolvedBaseRoute.targetIdRequiresIdentityResolution) {
            throw MetadataRouteFailure.IdentityResolutionFailed(resolvedBaseRoute.parentId, resolvedBaseRoute.provider)
        }
        val episodeMetadata = tvRequest.seasonNumbers
            .ifEmpty { listOfNotNull(metadataRequest.seasonNumber) }
            .ifEmpty { listOf(1) }
            .flatMap { seasonNumber ->
                val seasonRoute = resolvedBaseRoute.copy(seasonNumber = seasonNumber)
                val plan = providerPlanExecutor.buildPlan(seasonRoute, MetadataDepth.SEASON)
                providerPlanRunner.run(plan).stepResults.flatMap { stepResult ->
                    stepResult.episodeMetadata.entries
                }
            }
            .associate { it.toPair() }
        return TvMetadataDecision(
            provider = resolvedBaseRoute.provider.toTvProvider(),
            reason = TvMetadataDecisionReason.TVDB_SUCCESS,
            value = episodeMetadata,
            diagnostics = emptyList()
        )
    }

    suspend fun fetchTvSeasonEpisodes(
        metadataRequest: MetadataRequest,
        contentId: String,
        fallbackContentId: String?,
        seasonNumber: Int,
        language: String? = null
    ): TvMetadataDecision<List<TvSeasonEpisode>> {
        val route = routeRequest(
            metadataRequest.copy(
                depth = MetadataDepth.SEASON,
                seasonNumber = seasonNumber,
                language = language ?: metadataRequest.language
            )
        )
        val plan = providerPlanExecutor.buildPlan(route = route, depth = MetadataDepth.SEASON)
        val runResult = providerPlanRunner.run(plan)
        val episodes = runResult.stepResults
            .flatMap { stepResult -> stepResult.episodeMetadata.values }
            .map { episodeMetadata ->
                TvSeasonEpisode(
                    episodeNumber = episodeMetadata.episodeNumber,
                    airDate = episodeMetadata.airDate,
                    metadata = episodeMetadata
                )
            }
            .sortedWith(compareBy<TvSeasonEpisode> { it.episodeNumber ?: Int.MAX_VALUE })

        return TvMetadataDecision(
            provider = route.provider.toTvProvider(),
            reason = TvMetadataDecisionReason.TVDB_SUCCESS,
            value = episodes,
            diagnostics = runResult.trace.map { trace ->
                TvMetadataDiagnosticEvent(
                    reason = TvMetadataDecisionReason.TVDB_SUCCESS,
                    contentId = route.parentId,
                    provider = route.provider.toTvProvider(),
                    detail = trace.detail
                )
            }
        )
    }

    private fun HomeDisplayMetadata.toResolvedDocument(): ResolvedMetadataDocument =
        ResolvedMetadataDocument(
            canonicalId = null,
            title = title,
            overview = description,
            poster = poster,
            backdrop = backdrop,
            logo = logo,
            rating = imdbRating,
            runtimeMinutes = runtime?.toIntOrNull(),
            fieldOwners = emptyMap(),
            ignoredOverwrites = emptyList()
        )

    private fun ResolvedMetadataDocument.toHomeDisplayMetadata(fallback: HomeDisplayMetadata): HomeDisplayMetadata =
        fallback.copy(
            title = title ?: fallback.title,
            logo = logo ?: fallback.logo,
            description = overview ?: fallback.description,
            runtime = runtimeMinutes?.toString() ?: fallback.runtime,
            imdbRating = (rating as? Number)?.toFloat() ?: fallback.imdbRating,
            poster = poster ?: fallback.poster,
            backdrop = backdrop ?: fallback.backdrop
        )

    private fun ResolvedMetadataDocument.toTvMetadataEnrichment(): TvMetadataEnrichment =
        TvMetadataEnrichment(
            seriesTvdbId = canonicalId?.substringAfter("tvdb:")?.toIntOrNull(),
            localizedTitle = title,
            description = overview,
            backdrop = backdrop,
            logo = logo,
            poster = poster,
            rating = (rating as? Number)?.toDouble(),
            runtimeMinutes = runtimeMinutes
        )

    private fun MetadataPrimaryProvider?.toTvProvider(): TvProvider =
        when (this) {
            MetadataPrimaryProvider.KITSU -> TvProvider.KITSU
            MetadataPrimaryProvider.TMDB -> TvProvider.TMDB
            MetadataPrimaryProvider.TVDB,
            null -> TvProvider.TVDB
        }
}
