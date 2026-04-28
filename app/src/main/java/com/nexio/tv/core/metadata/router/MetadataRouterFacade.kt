package com.nexio.tv.core.metadata.router

import com.nexio.tv.core.metadata.router.resolver.OrganizationPersonResolver
import com.nexio.tv.core.metadata.router.resolver.RecommendationResolver
import com.nexio.tv.core.metadata.router.resolver.ReviewResolver
import com.nexio.tv.core.metadata.router.resolver.TrailerResolver
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
import com.nexio.tv.data.trailer.TrailerResolutionResult
import com.nexio.tv.data.trailer.TrailerService
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.HomeDisplayMetadata
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.MetaReview
import com.nexio.tv.domain.model.PersonDetail
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
    private val metadataSecondaryRepository: MetadataSecondaryRepository? = null,
    private val trailerService: TrailerService? = null,
    private val trailerResolver: TrailerResolver? = null,
    private val reviewResolver: ReviewResolver? = null,
    private val recommendationResolver: RecommendationResolver? = null,
    private val organizationPersonResolver: OrganizationPersonResolver? = null
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

        // F-B-04: dispatch scheduled networkResolvers and emit metadata.field_selected events for each.
        // Each resolver consumes candidates produced by ProviderPlanRunner and either picks a winner
        // (TrailerResolver, RecommendationResolver) or aggregates (ReviewResolver, OrganizationPersonResolver).
        // ARTWORK / ADDON_DISPLAY / RATING / TRACKING participate via FieldResolver / orchestrator local
        // pass — no separate dispatch needed. SKIP_SEGMENTS was removed in Task 20 (F-12-01); player
        // skip is owned by SkipIntroRepository, not the resolver pipeline.
        resolverSchedule.networkResolvers.forEach { resolverType ->
            when (resolverType) {
                ResolverType.TRAILERS -> trailerResolver?.resolve(
                    contentId = request.contentId,
                    primary = runResult.primaryCandidateFor(ResolvedField.TRAILERS),
                    secondary = runResult.secondaryCandidatesFor(ResolvedField.TRAILERS)
                )
                ResolverType.REVIEWS -> reviewResolver?.resolve(
                    contentId = request.contentId,
                    primary = runResult.primaryCandidateFor(ResolvedField.REVIEWS),
                    secondary = runResult.secondaryCandidatesFor(ResolvedField.REVIEWS)
                )
                ResolverType.RECOMMENDATIONS -> recommendationResolver?.resolve(
                    contentId = request.contentId,
                    primary = runResult.primaryCandidateFor(ResolvedField.RECOMMENDATIONS),
                    secondary = runResult.secondaryCandidatesFor(ResolvedField.RECOMMENDATIONS)
                )
                ResolverType.ORGANIZATION_PERSON -> organizationPersonResolver?.resolve(
                    contentId = request.contentId,
                    primary = runResult.primaryCandidateFor(ResolvedField.CAST)
                        ?: runResult.primaryCandidateFor(ResolvedField.CREW)
                        ?: runResult.primaryCandidateFor(ResolvedField.ORGANIZATION_LIST),
                    secondary = (
                        runResult.secondaryCandidatesFor(ResolvedField.CAST) +
                            runResult.secondaryCandidatesFor(ResolvedField.CREW) +
                            runResult.secondaryCandidatesFor(ResolvedField.ORGANIZATION_LIST)
                        ).distinct()
                )
                ResolverType.ARTWORK,
                ResolverType.ADDON_DISPLAY,
                ResolverType.RATING,
                ResolverType.TRACKING -> Unit
            }
        }

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

    /**
     * Routes a trailer fetch through the canonical resolve pipeline at depth
     * [MetadataDepth.DETAIL_MEDIA] so that `metadata.route_decision` and
     * `metadata.field_selected` trace events fire, then delegates the actual
     * playback-ready resolution to [TrailerService].
     *
     * The resolved document from [resolveRequest] is intentionally discarded —
     * the trailer pipeline produces a player-ready [TrailerResolutionResult]
     * (with In-App YouTube extraction, separate video/audio adaptive URLs)
     * which is richer than the resolver's `ResolvedField.TRAILERS` carry-set.
     */
    suspend fun fetchTrailer(
        metadataRequest: MetadataRequest,
        title: String,
        year: String? = null,
        tmdbId: String? = null,
        type: String? = null,
        seasonNumber: Int? = null,
        contentId: String? = null,
        fallbackYtIds: List<String> = emptyList()
    ): TrailerResolutionResult? {
        val service = checkNotNull(trailerService) {
            "fetchTrailer requires MetadataRouterFacade to be constructed with a non-null TrailerService"
        }
        // Fire canonical trace events via the resolve pipeline (depth = DETAIL_MEDIA).
        resolveRequest(metadataRequest)
        // Delegate to TrailerService for the actual playback-ready resolution.
        return service.resolveTrailer(
            title = title,
            year = year,
            tmdbId = tmdbId,
            type = type,
            seasonNumber = seasonNumber,
            contentId = contentId,
            fallbackYtIds = fallbackYtIds
        )
    }

    /**
     * Routes a TMDB review fetch through the canonical resolve pipeline at depth
     * [MetadataDepth.DETAIL_SECONDARY] so that `metadata.route_decision` and
     * `metadata.field_selected` trace events fire, then delegates the actual
     * review-list fetch to [MetadataSecondaryRepository].
     *
     * The Trakt review path (see `ReviewsRepository.fetchTraktReviewPage`) is intentionally
     * NOT migrated here yet — that requires adding `MetadataPrimaryProvider.TRAKT` to the
     * provider enum, which would touch 10+ exhaustive `when` statements (deferred per
     * Task 12 scope decision). Routing the TMDB half through the facade still delivers
     * the canonical trace observability for the audit's primary goal.
     */
    suspend fun fetchReviews(
        metadataRequest: MetadataRequest,
        tmdbId: String,
        contentType: ContentType
    ): List<MetaReview> {
        val repo = checkNotNull(metadataSecondaryRepository) {
            "fetchReviews requires MetadataRouterFacade to be constructed with a non-null MetadataSecondaryRepository"
        }
        // Fire canonical trace events via the resolve pipeline (depth = DETAIL_SECONDARY).
        resolveRequest(metadataRequest)
        // Delegate to the secondary repository for the TMDB review list.
        return repo.fetchReviews(tmdbId, contentType)
    }

    /**
     * Routes a TMDB recommendations (more-like-this) fetch through the canonical resolve pipeline
     * at depth [MetadataDepth.DETAIL_SECONDARY] so that `metadata.route_decision` and
     * `metadata.field_selected` trace events fire, then delegates the actual recommendations
     * list fetch to [MetadataSecondaryRepository].
     */
    suspend fun fetchRecommendations(
        metadataRequest: MetadataRequest,
        tmdbId: String,
        contentType: ContentType
    ): List<MetaPreview> {
        val repo = checkNotNull(metadataSecondaryRepository) {
            "fetchRecommendations requires MetadataRouterFacade to be constructed with a non-null MetadataSecondaryRepository"
        }
        // Fire canonical trace events via the resolve pipeline (depth = DETAIL_SECONDARY).
        resolveRequest(metadataRequest)
        // Delegate to the secondary repository for the TMDB recommendations list.
        return repo.fetchMoreLikeThis(tmdbId, contentType)
    }

    /**
     * Routes a TMDB person-id-by-name lookup through the canonical resolve pipeline so
     * that `metadata.route_decision` and `metadata.field_selected` trace events fire,
     * then delegates the exact-name search to [MetadataSecondaryRepository].
     *
     * The Kitsu-bridge actor hydration path (see `hydrateKitsuNavigationTargetsAsync`)
     * uses this to resolve TMDB person ids for actors that arrived via Kitsu metadata.
     * The [metadataRequest] is supplied by the caller for trace observability — there
     * is no canonical content-id for a "person by exact name" query.
     */
    suspend fun findPersonIdByExactName(
        metadataRequest: MetadataRequest,
        name: String
    ): Int? {
        val repo = checkNotNull(metadataSecondaryRepository) {
            "findPersonIdByExactName requires MetadataRouterFacade to be constructed with a non-null MetadataSecondaryRepository"
        }
        // Fire canonical trace events via the resolve pipeline (depth = DETAIL_SECONDARY).
        resolveRequest(metadataRequest)
        return repo.findPersonIdByExactName(name)
    }

    /**
     * Routes a TMDB company-id-by-name lookup through the canonical resolve pipeline so
     * that `metadata.route_decision` and `metadata.field_selected` trace events fire,
     * then delegates the exact-name search to [MetadataSecondaryRepository].
     *
     * Used by the Kitsu-bridge production-company hydration path to resolve TMDB
     * company ids for studios that arrived via Kitsu metadata.
     */
    suspend fun findCompanyIdByExactName(
        metadataRequest: MetadataRequest,
        name: String
    ): Int? {
        val repo = checkNotNull(metadataSecondaryRepository) {
            "findCompanyIdByExactName requires MetadataRouterFacade to be constructed with a non-null MetadataSecondaryRepository"
        }
        // Fire canonical trace events via the resolve pipeline (depth = DETAIL_SECONDARY).
        resolveRequest(metadataRequest)
        return repo.findCompanyIdByExactName(name)
    }

    /**
     * Routes a TMDB person-detail fetch through the canonical resolve pipeline so that
     * `metadata.route_decision` and `metadata.field_selected` trace events fire, then
     * delegates the rich person-detail (biography, known-for, credits) fetch to
     * [MetadataSecondaryRepository].
     */
    suspend fun fetchPersonDetail(
        metadataRequest: MetadataRequest,
        personId: Int,
        preferCrewCredits: Boolean = false
    ): PersonDetail? {
        val repo = checkNotNull(metadataSecondaryRepository) {
            "fetchPersonDetail requires MetadataRouterFacade to be constructed with a non-null MetadataSecondaryRepository"
        }
        // Fire canonical trace events via the resolve pipeline (depth = DETAIL_SECONDARY).
        resolveRequest(metadataRequest)
        return repo.fetchPersonDetail(personId, preferCrewCredits)
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
            MetadataPrimaryProvider.IMDB,
            MetadataPrimaryProvider.TRAKT,
            MetadataPrimaryProvider.SIMKL,
            null -> TvProvider.TVDB
        }
}
