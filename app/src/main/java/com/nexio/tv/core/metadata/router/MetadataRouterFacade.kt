package com.nexio.tv.core.metadata.router

import com.nexio.tv.core.metadata.router.resolver.OrganizationPersonResolver
import com.nexio.tv.core.metadata.router.resolver.RecommendationResolver
import com.nexio.tv.core.metadata.router.resolver.ReviewResolver
import com.nexio.tv.core.metadata.router.resolver.TrailerResolver
import com.nexio.tv.core.trace.NoopRuntimeTraceSink
import com.nexio.tv.core.trace.TraceMetadataEvents
import com.nexio.tv.core.tmdb.TmdbEnrichment
import com.nexio.tv.core.tmdb.TmdbOrganizationService
import com.nexio.tv.domain.model.MetaCompanyKind
import com.nexio.tv.domain.model.OrganizationDiscoverType
import com.nexio.tv.domain.model.TmdbOrganizationDetail
import com.nexio.tv.core.tvdb.TvEpisodeMetadata
import com.nexio.tv.core.tvdb.TvMetadataDiagnosticEvent
import com.nexio.tv.core.tvdb.TvMetadataDecision
import com.nexio.tv.core.tvdb.TvMetadataDecisionReason
import com.nexio.tv.core.tvdb.TvMetadataEnrichment
import com.nexio.tv.core.tvdb.TvMetadataRequest
import com.nexio.tv.core.tvdb.TvProvider
import com.nexio.tv.core.tvdb.TvSeasonEpisode
import com.nexio.tv.data.integration.metadata.MetadataSecondaryRepository
import com.nexio.tv.data.trailer.TrailerPlaybackSource
import com.nexio.tv.data.trailer.TrailerResolutionResult
import com.nexio.tv.data.trailer.TrailerService
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.HomeDisplayMetadata
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.MetaReview
import com.nexio.tv.domain.model.PersonDetail
import com.nexio.tv.domain.model.ProviderId
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MetadataRouterFacade(
    private val router: MetadataRouter,
    private val providerPlanExecutor: ProviderPlanExecutor,
    private val resolverOrchestrator: ResolverOrchestrator,
    private val identityResolver: MetadataIdentityResolver,
    private val providerPlanRunner: ProviderPlanRunner,
    private val fieldResolver: FieldResolver,
    private val stableIdBundleResolver: StableIdBundleResolver = StableIdBundleResolver(
        idMappingStore = InMemoryIdMappingStore(),
        lookup = object : StableIdBundleResolver.Lookup {
            override suspend fun tmdbMovieToImdb(tmdbId: String): String? = null
            override suspend fun imdbToTmdbMovie(imdbId: String): String? = null
            override suspend fun tmdbTvToImdb(tmdbId: String): String? = null
            override suspend fun imdbToTvdbSeries(imdbId: String): String? = null
            override suspend fun tvdbSeriesToImdb(tvdbId: String): String? = null
        }
    ),
    private val traceEvents: TraceMetadataEvents = TraceMetadataEvents(
        sink = NoopRuntimeTraceSink,
        sessionId = { null }
    ),
    private val metadataSecondaryRepository: MetadataSecondaryRepository? = null,
    private val trailerService: TrailerService? = null,
    private val trailerResolver: TrailerResolver? = null,
    private val reviewResolver: ReviewResolver? = null,
    private val recommendationResolver: RecommendationResolver? = null,
    private val organizationPersonResolver: OrganizationPersonResolver? = null,
    private val tmdbOrganizationService: TmdbOrganizationService? = null
) {
    @Inject
    constructor(
        router: MetadataRouter,
        providerPlanExecutor: ProviderPlanExecutor,
        resolverOrchestrator: ResolverOrchestrator,
        identityResolver: MetadataIdentityResolver,
        providerPlanRunner: ProviderPlanRunner,
        fieldResolver: FieldResolver,
        idMappingStore: IdMappingStore,
        stableIdBundleLookup: StableIdBundleResolver.Lookup,
        traceEvents: TraceMetadataEvents,
        metadataSecondaryRepository: MetadataSecondaryRepository? = null,
        trailerService: TrailerService? = null,
        trailerResolver: TrailerResolver? = null,
        reviewResolver: ReviewResolver? = null,
        recommendationResolver: RecommendationResolver? = null,
        organizationPersonResolver: OrganizationPersonResolver? = null,
        tmdbOrganizationService: TmdbOrganizationService? = null
    ) : this(
        router = router,
        providerPlanExecutor = providerPlanExecutor,
        resolverOrchestrator = resolverOrchestrator,
        identityResolver = identityResolver,
        providerPlanRunner = providerPlanRunner,
        fieldResolver = fieldResolver,
        stableIdBundleResolver = StableIdBundleResolver(
            idMappingStore = idMappingStore,
            lookup = stableIdBundleLookup
        ),
        traceEvents = traceEvents,
        metadataSecondaryRepository = metadataSecondaryRepository,
        trailerService = trailerService,
        trailerResolver = trailerResolver,
        reviewResolver = reviewResolver,
        recommendationResolver = recommendationResolver,
        organizationPersonResolver = organizationPersonResolver,
        tmdbOrganizationService = tmdbOrganizationService
    )

    suspend fun routeRequest(request: MetadataRequest): MetadataRoute {
        val routed = router.route(request)
        val route = identityResolver.resolve(routed)
        if (route.targetIdRequiresIdentityResolution) {
            throw MetadataRouteFailure.IdentityResolutionFailed(route.parentId, route.provider)
        }
        return route
    }

    suspend fun resolveStableIdBundle(
        request: MetadataRequest,
        trigger: StableIdResolutionTrigger,
        itemKey: String
    ): StableIdBundle {
        val route = routeRequest(request)
        return resolveStableIdBundle(
            route = route,
            request = request,
            trigger = trigger,
            itemKey = itemKey
        )
    }

    suspend fun resolveStableIdBundle(
        route: MetadataRoute,
        request: MetadataRequest,
        trigger: StableIdResolutionTrigger,
        itemKey: String
    ): StableIdBundle {
        val bundle = stableIdBundleResolver.resolve(
            StableIdBundleRequest(
                itemKey = itemKey,
                itemType = request.contentType,
                routeProvider = route.provider,
                knownIds = request.sourceContext.previewStableIds,
                sourceProvider = request.sourceContext.previewSourceProvider
                    ?.let { raw -> ProviderId.entries.firstOrNull { it.name == raw } },
                sourceItemId = request.sourceContext.previewSourceItemId,
                railId = request.sourceContext.previewRailSource,
                trigger = trigger
            )
        )
        traceEvents.emitStableIdBundle(bundle, trigger)
        return bundle
    }

    suspend fun resolveRequest(request: MetadataRequest): MetadataResolutionResult {
        val resolverSchedule = resolverOrchestrator.schedule(request.depth)
        val initialDisplay = request.sourceContext.addonMetadata ?: HomeDisplayMetadata()

        if (request.depth == MetadataDepth.PREVIEW) {
            // F-B-01: route through FieldResolver so fieldOwners are populated (preview is a real owner)
            // rather than synthesized with emptyMap().
            val previewCandidate = request.sourceContext.toPreviewCandidate(MetadataPrimaryProvider.TMDB)
            val document = if (previewCandidate != null) {
                fieldResolver.resolveWithPreview(
                    preview = previewCandidate,
                    primary = null,
                    secondary = emptyList(),
                    requestContentId = request.contentId
                )
            } else {
                // No preview fields available — return an empty document with empty fieldOwners
                // (consistent with the no-data case, NOT the prior empty-fieldOwners workaround).
                ResolvedMetadataDocument(
                    canonicalId = null,
                    title = null,
                    overview = null,
                    poster = null,
                    backdrop = null,
                    logo = null,
                    rating = null,
                    runtimeMinutes = null,
                    fieldOwners = emptyMap(),
                    ignoredOverwrites = emptyList()
                )
            }
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
        val previewCandidate = request.sourceContext.toPreviewCandidate(route.provider)
        val resolvedDocument = fieldResolver.resolveWithPreview(
            preview = previewCandidate,
            primary = runResult.primaryCandidate,
            secondary = runResult.secondaryCandidates,
            requestContentId = request.contentId
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

    /**
     * F2-B-08 (non-discard): Routes a TV enrichment fetch through the canonical resolve pipeline
     * and USES the resulting [MetadataResolutionResult] to build [TvMetadataEnrichment].
     *
     * Unlike the other `fetch*` methods in this facade, the resolved document is NOT discarded —
     * [result.route] provides the winning [TvProvider] and [result.resolvedDocument] supplies
     * the enrichment fields (title, overview, poster, backdrop, logo, rating, runtimeMinutes).
     *
     * If you add a richer TV-enrichment shape in the future, map its fields via
     * [ResolvedMetadataDocument.toTvMetadataEnrichment] rather than bypassing this pipeline,
     * so trace events (`metadata.route_decision`, `metadata.field_selected`) continue to fire.
     */
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
     * F2-B-08: trace-only [resolveRequest] discard pattern. The internal `resolveRequest(req)`
     * call is fired purely so canonical trace events emit (`metadata.route_decision`,
     * `metadata.field_selected`, etc.). The result is intentionally discarded because the rich
     * 22-field [TmdbEnrichment] shape is not carried by [ResolvedMetadataDocument] — the second
     * call to [MetadataSecondaryRepository.fetchTmdbEnrichment] returns the rich payload.
     *
     * Specifically, [ResolvedMetadataDocument] omits director, writer, full cast, production
     * companies, networks, and collection data that downstream `enrichMeta(...)` call sites
     * depend on. Changing this method to return the resolved document instead of delegating
     * will silently drop all of those fields.
     *
     * See cluster D Task 7 and cluster H F2-B-08 for the architecture decision.
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
     * F2-B-08: trace-only [resolveRequest] discard pattern. The internal `resolveRequest(req)`
     * call is fired purely so canonical trace events emit (`metadata.route_decision`,
     * `metadata.field_selected`, etc.). The result is intentionally discarded because the
     * trailer pipeline produces a player-ready [TrailerResolutionResult] (In-App YouTube
     * extraction, separate video/audio adaptive URLs) which is richer than anything carried
     * by [ResolvedMetadataDocument] or the `ResolvedField.TRAILERS` carry-set.
     *
     * If you change this method to return the resolved document instead of delegating to
     * [TrailerService], you will lose adaptive-URL resolution, YouTube extraction, and all
     * fallback/retry logic that [TrailerService] encapsulates.
     *
     * See cluster H F2-B-08 for the architecture decision.
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
     * F2-TM-01: Season trailer path routed through canonical facade.
     *
     * Fires canonical trace events (`metadata.route_decision`, `metadata.resolver_schedule`)
     * via [resolveRequest] at depth [MetadataDepth.DETAIL_MEDIA], then delegates the actual
     * season-trailer resolution to [TrailerService.getSeasonTrailerPlaybackSource].
     *
     * The resolved [TrailerPlaybackSource] is richer than anything carried by
     * [ResolvedMetadataDocument] — [TrailerService] handles adaptive-URL selection and
     * YouTube extraction internally.
     */
    suspend fun fetchSeasonTrailer(
        metadataRequest: MetadataRequest,
        title: String,
        year: String? = null,
        tmdbId: String? = null,
        type: String? = null,
        seasonNumber: Int? = null,
        contentId: String? = null
    ): TrailerPlaybackSource? {
        val service = checkNotNull(trailerService) {
            "fetchSeasonTrailer requires MetadataRouterFacade to be constructed with a non-null TrailerService"
        }
        // Fire canonical trace events (metadata.route_decision, metadata.resolver_schedule).
        resolveRequest(metadataRequest)
        return service.getSeasonTrailerPlaybackSource(
            title = title,
            year = year,
            tmdbId = tmdbId,
            type = type,
            seasonNumber = seasonNumber,
            contentId = contentId
        )
    }

    /**
     * F2-TM-01: Season recap path routed through canonical facade.
     *
     * Fires canonical trace events (`metadata.route_decision`, `metadata.resolver_schedule`)
     * via [resolveRequest] at depth [MetadataDepth.DETAIL_MEDIA], then delegates the actual
     * season-recap resolution to [TrailerService.getSeasonRecapPlaybackSource].
     *
     * See [fetchSeasonTrailer] for the architecture rationale (same pattern).
     */
    suspend fun fetchSeasonRecap(
        metadataRequest: MetadataRequest,
        title: String,
        year: String? = null,
        tmdbId: String? = null,
        type: String? = null,
        seasonNumber: Int? = null,
        contentId: String? = null
    ): TrailerPlaybackSource? {
        val service = checkNotNull(trailerService) {
            "fetchSeasonRecap requires MetadataRouterFacade to be constructed with a non-null TrailerService"
        }
        // Fire canonical trace events (metadata.route_decision, metadata.resolver_schedule).
        resolveRequest(metadataRequest)
        return service.getSeasonRecapPlaybackSource(
            title = title,
            year = year,
            tmdbId = tmdbId,
            type = type,
            seasonNumber = seasonNumber,
            contentId = contentId
        )
    }

    /**
     * Routes a review fetch through the canonical resolve pipeline at depth
     * [MetadataDepth.DETAIL_SECONDARY], aggregating REVIEWS candidates from every
     * registered review adapter (TMDB, Trakt). If no adapter produced reviews
     * (e.g. plan didn't include a review step), falls back to the direct
     * [MetadataSecondaryRepository.fetchReviews] TMDB call so existing TMDB-only
     * flows keep working.
     *
     * Trace events fire via the resolver pipeline: `metadata.route_decision`,
     * `metadata.provider_plan`, `metadata.field_selected(REVIEWS)`.
     */
    suspend fun fetchReviews(
        metadataRequest: MetadataRequest,
        tmdbId: String,
        contentType: ContentType
    ): List<MetaReview> = fetchReviewsPage(
        metadataRequest = metadataRequest,
        tmdbId = tmdbId,
        contentType = contentType,
        page = DEFAULT_REVIEWS_PAGE,
        limit = DEFAULT_REVIEWS_LIMIT
    ).reviews

    /**
     * Paginated variant of [fetchReviews]. Routes through the canonical resolver pipeline
     * just like [fetchReviews] (firing the same `metadata.route_decision` /
     * `metadata.field_selected` trace events), but additionally threads [page] and [limit]
     * to participating adapters via [MetadataRequest.pagination] -> [MetadataRoute.pagination].
     *
     * Returns the aggregated [ReviewsPage] with continuation state so the VM load-more flow
     * (Task 6d) can request page N+1 without losing track of pagination.
     *
     * Backwards-compat fallback: when no resolver candidate produces reviews (e.g. plan didn't
     * include a review step yet), falls back to the direct
     * [MetadataSecondaryRepository.fetchReviews] TMDB call — the same fallback the existing
     * non-paginated [fetchReviews] uses — and returns it as a single page with `hasMore=false`.
     */
    suspend fun fetchReviewsPage(
        metadataRequest: MetadataRequest,
        tmdbId: String,
        contentType: ContentType,
        page: Int = DEFAULT_REVIEWS_PAGE,
        limit: Int = DEFAULT_REVIEWS_LIMIT
    ): ReviewsPage {
        val repo = checkNotNull(metadataSecondaryRepository) {
            "fetchReviewsPage requires MetadataRouterFacade to be constructed with a non-null MetadataSecondaryRepository"
        }
        val paginatedRequest = metadataRequest.copy(
            pagination = PaginationCursor(page = page, limit = limit)
        )
        // Resolve through the canonical pipeline (depth = DETAIL_SECONDARY) — fires
        // metadata.route_decision + per-step provider_plan + per-field field_selected events.
        val resolution = resolveRequest(paginatedRequest)

        // Collect REVIEWS-bearing candidates from every adapter that produced one
        // (TmdbReviewMetadataAdapter, TraktReviewMetadataAdapter, etc.).
        val resolverReviews: List<MetaReview> = resolution.providerRunResult
            ?.stepResults
            ?.mapNotNull { stepResult ->
                @Suppress("UNCHECKED_CAST")
                stepResult.candidate?.fields?.get(ResolvedField.REVIEWS)?.value as? List<MetaReview>
            }
            ?.flatten()
            .orEmpty()

        if (resolverReviews.isEmpty()) {
            // Backwards-compat fallback: legacy TMDB-only flow with no continuation state.
            val fallback = repo.fetchReviews(tmdbId, contentType)
            return ReviewsPage(reviews = fallback, hasMore = false, nextPage = null)
        }

        // Single-adapter "full page" heuristic: if any adapter returned exactly `limit`
        // reviews, assume there's at least one more page available. The VM is the source
        // of truth for the cursor it next requests; this just exposes whether load-more
        // should be offered.
        val hasMore = resolverReviews.size >= limit
        return ReviewsPage(
            reviews = resolverReviews,
            hasMore = hasMore,
            nextPage = if (hasMore) page + 1 else null
        )
    }

    /**
     * Routes a recommendations fetch through the canonical resolve pipeline at depth
     * [MetadataDepth.DETAIL_SECONDARY] so that `metadata.route_decision` and
     * `metadata.field_selected` trace events fire. Resolver output is consumed FIRST
     * (from [TmdbRecommendationMetadataAdapter] or another [ResolvedField.RECOMMENDATIONS]
     * producer in the step results). Falls back to [MetadataSecondaryRepository.fetchMoreLikeThis]
     * only when the resolver pipeline returns an empty list, preserving the existing
     * TMDB-only path for plans that do not yet include a recommendation step.
     *
     * F2-TM-02: prior to this fix the resolver output was discarded and the repo was
     * always called; resolver output now takes precedence.
     */
    suspend fun fetchRecommendations(
        metadataRequest: MetadataRequest,
        tmdbId: String,
        contentType: ContentType
    ): List<MetaPreview> {
        val repo = checkNotNull(metadataSecondaryRepository) {
            "fetchRecommendations requires MetadataRouterFacade to be constructed with a non-null MetadataSecondaryRepository"
        }
        // Resolve through the canonical pipeline — fires metadata.route_decision +
        // per-step provider_plan + per-field field_selected events.
        val resolution = resolveRequest(metadataRequest)

        // Collect RECOMMENDATIONS from every step result that produced one.
        val resolverRecommendations: List<MetaPreview> = resolution.providerRunResult
            ?.stepResults
            ?.mapNotNull { stepResult ->
                @Suppress("UNCHECKED_CAST")
                stepResult.candidate?.fields?.get(ResolvedField.RECOMMENDATIONS)?.value as? List<MetaPreview>
            }
            ?.flatten()
            .orEmpty()

        // Use resolver output when non-empty; fall back to legacy repo call only when
        // no adapter produced recommendations (e.g. plan didn't include a recommendation step).
        return resolverRecommendations.takeIf { it.isNotEmpty() }
            ?: repo.fetchMoreLikeThis(tmdbId, contentType)
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
        // Resolve through the canonical pipeline (fires metadata.route_decision +
        // per-step provider_plan + per-field field_selected events).
        val resolution = resolveRequest(metadataRequest)

        // If the request's contentId carries the TVDB person prefix, the resolver pipeline
        // dispatches TvdbOrganizationPersonAdapter; surface its CAST candidate as PersonDetail.
        if (metadataRequest.contentId.startsWith("tvdb:person:")) {
            val tvdbPersonDetail = resolution.providerRunResult
                ?.stepResults
                ?.firstOrNull { it.candidate?.provider == MetadataPrimaryProvider.TVDB }
                ?.candidate
                ?.fields
                ?.get(ResolvedField.CAST)
                ?.value as? PersonDetail
            if (tvdbPersonDetail != null) return tvdbPersonDetail
        }

        // Default TMDB path: delegate to the secondary repository for the rich shape.
        val repo = checkNotNull(metadataSecondaryRepository) {
            "fetchPersonDetail requires MetadataRouterFacade to be constructed with a non-null MetadataSecondaryRepository"
        }
        return repo.fetchPersonDetail(personId, preferCrewCredits)
    }

    /**
     * F2-B-08 (pipeline bypass — intentional): Unlike the other `fetch*` methods in this
     * facade, episode enrichment does NOT call [resolveRequest] at all. It drives routing
     * directly through [MetadataRouter.route] + [MetadataIdentityResolver.resolve] +
     * [ProviderPlanExecutor.buildPlan] + [ProviderPlanRunner.run] at [MetadataDepth.SEASON].
     *
     * This bypass is intentional: the season/episode step-result shape
     * (`stepResult.episodeMetadata`) is not surfaced by [ResolvedMetadataDocument] — it
     * lives exclusively in [ProviderPlanRunResult.stepResults]. Routing through the full
     * [resolveRequest] pipeline and then discarding its output (as `fetchTmdbEnrichment`
     * and `fetchTrailer` do) would re-run unnecessary field-resolution and network-resolver
     * dispatches that have no episode-metadata analogue.
     *
     * Additionally, this method implements its own identity-resolution fallback loop
     * (primary route -> fallback content-id) which requires direct access to intermediate
     * routing state not exposed by [resolveRequest].
     *
     * If you need trace events here, add them at the [ProviderPlanRunner] level rather than
     * piping this method through [resolveRequest]. See cluster H F2-B-08 and F2-T-02-nit.
     */
    suspend fun fetchTvEpisodeEnrichment(
        metadataRequest: MetadataRequest,
        tvRequest: TvMetadataRequest
    ): TvMetadataDecision<Map<Pair<Int, Int>, TvEpisodeMetadata>> {
        val seasonMetadataRequest = metadataRequest.copy(
            depth = MetadataDepth.SEASON,
            // Default to season 1 when no explicit season is provided — the episode metadata
            // fetcher will expand to all available seasons via fetchEpisodeMetadataForRoute.
            seasonNumber = tvRequest.seasonNumbers.firstOrNull() ?: metadataRequest.seasonNumber ?: 1
        )
        val baseRoute = router.route(seasonMetadataRequest)
        val resolvedBaseRoute = identityResolver.resolve(baseRoute)
        if (resolvedBaseRoute.targetIdRequiresIdentityResolution) {
            val fallbackRoute = fallbackRouteForDistinctContentId(
                metadataRequest = seasonMetadataRequest,
                fallbackContentId = tvRequest.fallbackContentId
            )
            val fallbackMetadata = fallbackRoute?.takeUnless { it.targetIdRequiresIdentityResolution }?.let { route ->
                fetchEpisodeMetadataForRoute(
                    route = route,
                    seasonNumbers = tvRequest.seasonNumbers,
                    metadataSeasonNumber = metadataRequest.seasonNumber
                )
            }
            if (!fallbackMetadata.isNullOrEmpty()) {
                return TvMetadataDecision(
                    provider = fallbackRoute.provider.toTvProvider(),
                    reason = TvMetadataDecisionReason.TVDB_SUCCESS,
                    value = fallbackMetadata,
                    diagnostics = emptyList()
                )
            }
            throw MetadataRouteFailure.IdentityResolutionFailed(resolvedBaseRoute.parentId, resolvedBaseRoute.provider)
        }
        val episodeMetadata = fetchEpisodeMetadataForRoute(
            route = resolvedBaseRoute,
            seasonNumbers = tvRequest.seasonNumbers,
            metadataSeasonNumber = metadataRequest.seasonNumber
        )
        if (episodeMetadata.isNotEmpty()) {
            return TvMetadataDecision(
                provider = resolvedBaseRoute.provider.toTvProvider(),
                reason = TvMetadataDecisionReason.TVDB_SUCCESS,
                value = episodeMetadata,
                diagnostics = emptyList()
            )
        }

        val fallbackRoute = fallbackRouteForDistinctContentId(
            metadataRequest = seasonMetadataRequest,
            fallbackContentId = tvRequest.fallbackContentId
        )
        val fallbackMetadata = fallbackRoute?.takeUnless { it.targetIdRequiresIdentityResolution }?.let { route ->
            fetchEpisodeMetadataForRoute(
                route = route,
                seasonNumbers = tvRequest.seasonNumbers,
                metadataSeasonNumber = metadataRequest.seasonNumber
            )
        }
        return TvMetadataDecision(
            provider = fallbackRoute?.provider?.takeIf { !fallbackMetadata.isNullOrEmpty() }?.toTvProvider()
                ?: resolvedBaseRoute.provider.toTvProvider(),
            reason = TvMetadataDecisionReason.TVDB_SUCCESS,
            value = fallbackMetadata?.takeIf { it.isNotEmpty() } ?: episodeMetadata,
            diagnostics = emptyList()
        )
    }

    private suspend fun fetchEpisodeMetadataForRoute(
        route: MetadataRoute,
        seasonNumbers: List<Int>,
        metadataSeasonNumber: Int?
    ): Map<Pair<Int, Int>, TvEpisodeMetadata> {
        return seasonNumbers
            .ifEmpty { listOfNotNull(metadataSeasonNumber) }
            .ifEmpty { listOf(1) }
            .flatMap { seasonNumber ->
                val seasonRoute = route.copy(seasonNumber = seasonNumber)
                val plan = providerPlanExecutor.buildPlan(seasonRoute, MetadataDepth.SEASON)
                providerPlanRunner.run(plan).stepResults.flatMap { stepResult ->
                    stepResult.episodeMetadata.entries
                }
            }
            .associate { it.toPair() }
    }

    private suspend fun fallbackRouteForDistinctContentId(
        metadataRequest: MetadataRequest,
        fallbackContentId: String?
    ): MetadataRoute? {
        val fallbackId = fallbackContentId?.takeIf { it.isNotBlank() } ?: return null
        if (fallbackId == metadataRequest.contentId) return null

        val fallbackRoute = router.route(
            metadataRequest.copy(
                contentId = fallbackId,
                sourceContext = metadataRequest.sourceContext.copy(previewSourceItemId = fallbackId)
            )
        )
        return identityResolver.resolve(fallbackRoute)
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

    /**
     * F2-TM-03: Routes an organization-detail fetch through the canonical resolve pipeline so
     * that `metadata.route_decision` and `metadata.resolver_schedule` trace events fire, then
     * delegates the actual fetch to [TmdbOrganizationService].
     *
     * Since there is no canonical content ID for an organization entity, a synthetic content ID
     * (`tmdb:org:<entityId>`) is used for the [MetadataRequest] to satisfy the pipeline. The
     * resolved document is intentionally discarded — the rich [TmdbOrganizationDetail] shape
     * (company details, discover results) is not carried by [ResolvedMetadataDocument].
     *
     * See the F2-B-08 discard pattern in [fetchTmdbEnrichment] for the architecture rationale.
     */
    suspend fun fetchOrganizationDetail(
        entityId: Int,
        kind: MetaCompanyKind,
        discoverType: OrganizationDiscoverType,
        language: String? = "en-US",
        maxItems: Int = 20
    ): TmdbOrganizationDetail? {
        val service = checkNotNull(tmdbOrganizationService) {
            "fetchOrganizationDetail requires MetadataRouterFacade to be constructed with a non-null TmdbOrganizationService"
        }
        // Construct a synthetic MetadataRequest for trace observability.
        // The resolved document is intentionally discarded — organization shape is not
        // carried by ResolvedMetadataDocument.
        val syntheticRequest = MetadataRequest(
            contentId = "tmdb:org:$entityId",
            contentType = ContentType.MOVIE,
            sourceContext = MetadataSourceContext(itemType = "organization"),
            depth = MetadataDepth.DETAIL_SECONDARY
        )
        resolveRequest(syntheticRequest)
        return service.fetchOrganizationDetail(
            entityId = entityId,
            kind = kind,
            discoverType = discoverType,
            language = language,
            maxItems = maxItems
        )
    }

    private fun MetadataSourceContext.toPreviewCandidate(
        fallbackProvider: MetadataPrimaryProvider
    ): MetadataCandidate? {
        val metadata = addonMetadata ?: return null
        val fields = metadata.toPreviewFields()
        if (fields.isEmpty()) return null

        return MetadataCandidate(
            provider = previewSourceProvider.toMetadataPrimaryProvider() ?: fallbackProvider,
            fields = fields,
            sourceProvider = previewSourceProvider ?: addonId ?: sourceName ?: fallbackProvider.name,
            sourceRole = previewSourceRole
        )
    }

    private fun HomeDisplayMetadata.toPreviewFields(): Map<ResolvedField, FieldValue> =
        buildMap {
            title?.let { put(ResolvedField.TITLE, FieldValue(it, FieldOwner.PRIMARY)) }
            description?.let { put(ResolvedField.OVERVIEW, FieldValue(it, FieldOwner.PRIMARY)) }
            poster?.let { put(ResolvedField.POSTER, FieldValue(it, FieldOwner.PRIMARY)) }
            backdrop?.let { put(ResolvedField.BACKDROP, FieldValue(it, FieldOwner.PRIMARY)) }
            logo?.let { put(ResolvedField.LOGO, FieldValue(it, FieldOwner.PRIMARY)) }
            imdbRating?.let { put(ResolvedField.RATING, FieldValue(it, FieldOwner.PRIMARY)) }
            runtime?.toIntOrNull()?.let { put(ResolvedField.RUNTIME, FieldValue(it, FieldOwner.PRIMARY)) }
            releaseInfo?.let { put(ResolvedField.RELEASE_DATE, FieldValue(it, FieldOwner.PRIMARY)) }
            if (genres.isNotEmpty()) put(ResolvedField.GENRES, FieldValue(genres, FieldOwner.PRIMARY))
        }

    private fun String?.toMetadataPrimaryProvider(): MetadataPrimaryProvider? =
        this?.let { providerName ->
            MetadataPrimaryProvider.entries.firstOrNull { provider ->
                provider.name.equals(providerName, ignoreCase = true)
            }
        }

    private fun ResolvedMetadataDocument.toHomeDisplayMetadata(fallback: HomeDisplayMetadata): HomeDisplayMetadata =
        fallback.copy(
            title = title ?: fallback.title,
            logo = logo ?: fallback.logo,
            description = overview ?: fallback.description,
            runtime = runtimeMinutes?.toString() ?: fallback.runtime,
            imdbRating = (rating as? Number)?.toFloat() ?: fallback.imdbRating,
            poster = poster ?: fallback.poster,
            backdrop = backdrop ?: fallback.backdrop,
            releaseInfo = releaseDate ?: fallback.releaseInfo,
            genres = genres.ifEmpty { fallback.genres }
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
            runtimeMinutes = runtimeMinutes,
            genres = genres,
            releaseInfo = releaseDate,
            ageRating = ageRating,
            countries = countries.takeIf { it.isNotEmpty() },
            language = language,
            castMembers = castMembers,
            productionCompanies = productionCompanies,
            networks = networks,
            airsTime = airsTime,
            originalCountry = originalCountry,
            originalNetwork = originalNetwork,
            latestNetwork = latestNetwork,
            platformName = platformName
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
            // RPDB and TOP_POSTERS are artwork-only providers — not used in metadata routing context.
            MetadataPrimaryProvider.RPDB,
            MetadataPrimaryProvider.TOP_POSTERS -> TvProvider.TVDB
        }

    private companion object {
        const val DEFAULT_REVIEWS_PAGE = 1
        const val DEFAULT_REVIEWS_LIMIT = 20
    }
}
