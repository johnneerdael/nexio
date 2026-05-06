package com.nexio.tv.core.metadata.router

import com.nexio.tv.core.artwork.ArtworkBundle
import com.nexio.tv.core.artwork.ArtworkOwnerKey
import com.nexio.tv.core.artwork.ArtworkProviderId
import com.nexio.tv.core.artwork.EpisodeArtworkContext
import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.core.integration.TmdbApiShapes
import com.nexio.tv.core.metadata.router.resolver.OrganizationPersonResolver
import com.nexio.tv.core.metadata.router.resolver.RecommendationResolver
import com.nexio.tv.core.metadata.router.resolver.ReviewResolver
import com.nexio.tv.core.metadata.router.resolver.TrailerPlaybackRef
import com.nexio.tv.core.metadata.router.resolver.TrailerResolver
import com.nexio.tv.core.metadata.router.resolver.TrailerResolveRequest
import com.nexio.tv.core.metadata.router.resolver.TrailerResolution
import com.nexio.tv.core.metadata.router.resolver.TrailerSurface
import com.nexio.tv.core.poster.PosterRatingsUrlResolver
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
import com.nexio.tv.data.remote.api.TmdbVideoResult
import com.nexio.tv.data.trailer.ProviderSeasonTrailerRefResolver
import com.nexio.tv.data.trailer.SeasonMediaAvailability
import com.nexio.tv.data.trailer.SeasonTrailerRefRequest
import com.nexio.tv.data.trailer.SeasonTrailerRefResolver
import com.nexio.tv.data.trailer.TrailerPlaybackSource
import com.nexio.tv.data.trailer.TrailerResolutionResult
import com.nexio.tv.data.trailer.TrailerService
import com.nexio.tv.data.trailer.rankedTmdbTrailerPlaybackRefs
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.HomeDisplayMetadata
import com.nexio.tv.domain.model.MetaCompany
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.MetaReview
import com.nexio.tv.domain.model.PersonDetail
import com.nexio.tv.domain.model.ProviderId
import com.nexio.tv.domain.model.ProviderIds
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
            override suspend fun tmdbTvToTvdb(tmdbId: String): String? = null
            override suspend fun tmdbTvToImdb(tmdbId: String): String? = null
            override suspend fun imdbToTvdbSeries(imdbId: String): String? = null
            override suspend fun tvdbSeriesToImdb(tvdbId: String): String? = null
        }
    ),
    private val traceEvents: TraceMetadataEvents = TraceMetadataEvents(
        sink = NoopRuntimeTraceSink,
        sessionId = { null }
    ),
    private val trailerService: TrailerService? = null,
    private val trailerResolver: TrailerResolver? = null,
    private val reviewResolver: ReviewResolver? = null,
    private val recommendationResolver: RecommendationResolver? = null,
    private val organizationPersonResolver: OrganizationPersonResolver? = null,
    private val tmdbOrganizationService: TmdbOrganizationService? = null,
    private val posterRatingsUrlResolver: PosterRatingsUrlResolver? = null,
    private val seasonTrailerRefResolver: SeasonTrailerRefResolver? = null
) {
    private val effectiveTrailerResolver: TrailerResolver by lazy {
        trailerResolver ?: TrailerResolver(traceEvents)
    }

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
        trailerService: TrailerService? = null,
        trailerResolver: TrailerResolver? = null,
        reviewResolver: ReviewResolver? = null,
        recommendationResolver: RecommendationResolver? = null,
        organizationPersonResolver: OrganizationPersonResolver? = null,
        tmdbOrganizationService: TmdbOrganizationService? = null,
        posterRatingsUrlResolver: PosterRatingsUrlResolver? = null,
        seasonTrailerRefResolver: ProviderSeasonTrailerRefResolver
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
        trailerService = trailerService,
        trailerResolver = trailerResolver,
        reviewResolver = reviewResolver,
        recommendationResolver = recommendationResolver,
        organizationPersonResolver = organizationPersonResolver,
        tmdbOrganizationService = tmdbOrganizationService,
        posterRatingsUrlResolver = posterRatingsUrlResolver,
        seasonTrailerRefResolver = seasonTrailerRefResolver
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
        // pass — no separate dispatch needed. Player skip uses SkipSegmentResolver outside this
        // scheduled metadata pipeline.
        resolverSchedule.networkResolvers.forEach { resolverType ->
            when (resolverType) {
                ResolverType.TRAILERS -> effectiveTrailerResolver.resolve(
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

    suspend fun fetchTmdbEnrichment(
        metadataRequest: MetadataRequest,
        tmdbId: String,
        contentType: ContentType
    ): TmdbEnrichment? {
        val enrichmentRequest = if (metadataRequest.depth == MetadataDepth.DETAIL_SECONDARY) {
            metadataRequest
        } else {
            metadataRequest.copy(depth = MetadataDepth.DETAIL_SECONDARY)
        }
        val resolution = resolveRequest(enrichmentRequest)
        return resolution.resolvedDocument.toLegacyTmdbEnrichmentOrNull()
    }

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
        val trailerRequest = metadataRequest.copy(
            depth = if (metadataRequest.depth == MetadataDepth.DETAIL_SECONDARY) {
                MetadataDepth.DETAIL_SECONDARY
            } else {
                MetadataDepth.DETAIL_MEDIA
            },
            seasonNumber = seasonNumber ?: metadataRequest.seasonNumber
        )
        val resolution = resolveRequest(trailerRequest)
        val trailerResolution = resolveTrailer(
            TrailerResolveRequest(
                itemKey = contentId ?: metadataRequest.contentId,
                title = title,
                year = year,
                stableIds = ProviderIds(
                    tmdb = tmdbId?.trim()?.takeIf { it.isNotBlank() }
                ),
                fallbackYtIds = fallbackYtIds,
                surface = TrailerSurface.DETAIL,
                type = type,
                seasonNumber = seasonNumber,
                contentId = contentId ?: metadataRequest.contentId,
                providerCandidates = resolution.providerRunResult.toTrailerPlaybackRefs()
            )
        )
        return trailerResolution.selected?.let { ref ->
            trailerService?.resolvePlaybackSource(
                ref = ref,
                title = title,
                year = year
            )
        }
    }

    fun resolveTrailer(request: TrailerResolveRequest): TrailerResolution {
        return effectiveTrailerResolver.resolveTrailer(request)
    }

    suspend fun fetchTitleMediaAvailability(
        metadataRequest: MetadataRequest,
        tmdbId: String? = null,
        type: String? = null,
        contentId: String? = null,
        fallbackYtIds: List<String> = emptyList()
    ): Boolean {
        val resolution = resolveRequest(metadataRequest.copy(depth = MetadataDepth.DETAIL_MEDIA))
        return resolveTrailer(
            TrailerResolveRequest(
                itemKey = contentId ?: metadataRequest.contentId,
                title = metadataRequest.contentId,
                stableIds = ProviderIds(
                    tmdb = tmdbId?.trim()?.takeIf { it.isNotBlank() }
                ),
                fallbackYtIds = fallbackYtIds,
                surface = TrailerSurface.DETAIL,
                type = type,
                contentId = contentId ?: metadataRequest.contentId,
                providerCandidates = resolution.providerRunResult.toTrailerPlaybackRefs()
            )
        ).availability.available
    }

    internal suspend fun fetchSeasonMediaAvailability(
        metadataRequest: MetadataRequest,
        tmdbId: String? = null,
        type: String? = null,
        seasonNumber: Int? = null,
        contentId: String? = null
    ): SeasonMediaAvailability {
        val effectiveSeason = seasonNumber ?: metadataRequest.seasonNumber
        val resolution = resolveRequest(
            metadataRequest.copy(
                depth = MetadataDepth.DETAIL_MEDIA,
                seasonNumber = effectiveSeason
            )
        )

        val refRequest = seasonRefRequest(
            title = metadataRequest.contentId,
            year = null,
            tmdbId = tmdbId,
            type = type,
            seasonNumber = effectiveSeason,
            contentId = contentId
        )
        val seasonTrailerRefs = seasonTrailerRefResolver
            ?.resolveSeasonTrailerRefs(refRequest)
            .orEmpty()
        val trailerAvailability = resolveTrailer(
            seasonTrailerResolveRequest(
                metadataRequest = metadataRequest,
                title = metadataRequest.contentId,
                year = null,
                tmdbId = tmdbId,
                type = type,
                seasonNumber = effectiveSeason,
                contentId = contentId,
                providerCandidates = resolution.providerRunResult.toSeasonTrailerPlaybackRefs() + seasonTrailerRefs
            )
        ).availability.available

        val seasonRecapRefs = seasonTrailerRefResolver
            ?.resolveSeasonRecapRefs(refRequest)
            .orEmpty()
        val recapAvailability = resolveTrailer(
            seasonTrailerResolveRequest(
                metadataRequest = metadataRequest,
                title = metadataRequest.contentId,
                year = null,
                tmdbId = tmdbId,
                type = type,
                seasonNumber = effectiveSeason,
                contentId = contentId,
                providerCandidates = seasonRecapRefs,
                itemKeySuffix = "recap"
            )
        ).availability.available

        return SeasonMediaAvailability(
            hasTrailerOrTeaser = trailerAvailability,
            hasRecap = recapAvailability
        )
    }

    /**
     * F2-TM-01: Season trailer path routed through canonical facade.
     *
     * Provider-plan candidates and resolver-owned season-ref adapter candidates are selected by
     * [TrailerResolver]. [TrailerService] remains only a transport adapter for the selected ref.
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
        val service = trailerService
        val effectiveSeason = seasonNumber ?: metadataRequest.seasonNumber
        val resolution = resolveRequest(
            metadataRequest.copy(
                depth = MetadataDepth.DETAIL_MEDIA,
                seasonNumber = effectiveSeason
            )
        )
        val providerSelection = resolveTrailer(
            seasonTrailerResolveRequest(
                metadataRequest = metadataRequest,
                title = title,
                year = year,
                tmdbId = tmdbId,
                type = type,
                seasonNumber = effectiveSeason,
                contentId = contentId,
                providerCandidates = resolution.providerRunResult.toSeasonTrailerPlaybackRefs()
            )
        )
        providerSelection.toFirstSeasonPlaybackSource(service, title, year)?.let { source ->
            return source
        }

        val seasonTrailerRefs = seasonTrailerRefResolver
            ?.resolveSeasonTrailerRefs(
                seasonRefRequest(
                    title = title,
                    year = year,
                    tmdbId = tmdbId,
                    type = type,
                    seasonNumber = effectiveSeason,
                    contentId = contentId
                )
            )
            .orEmpty()
        val seasonSelection = resolveTrailer(
            seasonTrailerResolveRequest(
                metadataRequest = metadataRequest,
                title = title,
                year = year,
                tmdbId = tmdbId,
                type = type,
                seasonNumber = effectiveSeason,
                contentId = contentId,
                providerCandidates = seasonTrailerRefs,
                itemKeySuffix = "season"
            )
        )
        return seasonSelection.toFirstSeasonPlaybackSource(service, title, year)
    }

    /**
     * F2-TM-01: Season recap path routed through canonical facade.
     *
     * Recap refs are discovered through the resolver-owned season-ref adapter and selected by
     * [TrailerResolver]. [TrailerService] only translates the selected ref into playback.
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
        val service = trailerService
        val effectiveSeason = seasonNumber ?: metadataRequest.seasonNumber
        resolveRequest(
            metadataRequest.copy(
                depth = MetadataDepth.DETAIL_MEDIA,
                seasonNumber = effectiveSeason
            )
        )
        val seasonRecapRefs = seasonTrailerRefResolver
            ?.resolveSeasonRecapRefs(
                seasonRefRequest(
                    title = title,
                    year = year,
                    tmdbId = tmdbId,
                    type = type,
                    seasonNumber = effectiveSeason,
                    contentId = contentId
                )
            )
            .orEmpty()
        val recapSelection = resolveTrailer(
            seasonTrailerResolveRequest(
                metadataRequest = metadataRequest,
                title = title,
                year = year,
                tmdbId = tmdbId,
                type = type,
                seasonNumber = effectiveSeason,
                contentId = contentId,
                providerCandidates = seasonRecapRefs,
                itemKeySuffix = "recap"
            )
        )
        return recapSelection.toFirstSeasonPlaybackSource(service, title, year)
    }

    private fun seasonRefRequest(
        title: String,
        year: String?,
        tmdbId: String?,
        type: String?,
        seasonNumber: Int?,
        contentId: String?
    ): SeasonTrailerRefRequest =
        SeasonTrailerRefRequest(
            title = title,
            year = year,
            tmdbId = tmdbId,
            type = type,
            seasonNumber = seasonNumber,
            contentId = contentId
        )

    /**
     * Routes a review fetch through the canonical resolve pipeline at depth
     * [MetadataDepth.DETAIL_SECONDARY], aggregating REVIEWS candidates from provider-plan
     * adapters (TMDB, Trakt).
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
     */
    suspend fun fetchReviewsPage(
        metadataRequest: MetadataRequest,
        tmdbId: String,
        contentType: ContentType,
        page: Int = DEFAULT_REVIEWS_PAGE,
        limit: Int = DEFAULT_REVIEWS_LIMIT
    ): ReviewsPage {
        val paginatedRequest = metadataRequest.copy(
            depth = MetadataDepth.DETAIL_SECONDARY,
            pagination = PaginationCursor(page = page, limit = limit)
        )
        val resolution = resolveRequest(paginatedRequest)
        return resolution.providerRunResult.toLegacyReviewsPage(page = page, limit = limit)
    }

    /**
     * Routes a recommendations fetch through the canonical resolve pipeline at depth
     * [MetadataDepth.DETAIL_SECONDARY] and returns RECOMMENDATIONS candidates from
     * provider-plan output.
     */
    suspend fun fetchRecommendations(
        metadataRequest: MetadataRequest,
        tmdbId: String,
        contentType: ContentType
    ): List<MetaPreview> {
        val resolution = resolveRequest(metadataRequest.copy(depth = MetadataDepth.DETAIL_SECONDARY))
        return resolution.providerRunResult.toLegacyRecommendations()
    }

    /**
     * Routes a TMDB person-id-by-name lookup through the canonical resolve pipeline so
     * that `metadata.route_decision` and `metadata.field_selected` trace events fire.
     *
     * The secondary navigation-target hydration path
     * uses this to resolve TMDB person ids for actors that arrived via Kitsu metadata.
     * The [metadataRequest] is supplied by the caller for trace observability — there
     * is no canonical content-id for a "person by exact name" query.
     */
    suspend fun findPersonIdByExactName(
        metadataRequest: MetadataRequest,
        name: String
    ): Int? {
        val resolution = resolveRequest(
            metadataRequest.copy(
                contentId = "tmdb:person:$name",
                depth = MetadataDepth.DETAIL_SECONDARY
            )
        )
        return resolution.providerRunResult.toLegacyPersonIdOrNull()
    }

    /**
     * Routes a TMDB company-id-by-name lookup through the canonical resolve pipeline so
     * that `metadata.route_decision` and `metadata.field_selected` trace events fire.
     *
     * Used by the secondary navigation-target hydration path to resolve TMDB
     * company ids for organizations that arrived from non-TMDB metadata.
     */
    suspend fun findCompanyIdByExactName(
        metadataRequest: MetadataRequest,
        name: String
    ): Int? {
        val resolution = resolveRequest(
            metadataRequest.copy(
                contentId = "tmdb:company:$name",
                depth = MetadataDepth.DETAIL_SECONDARY
            )
        )
        return resolution.providerRunResult.toLegacyCompanyIdOrNull()
    }

    /**
     * Routes a TMDB person-detail fetch through the canonical resolve pipeline so that
     * `metadata.route_decision` and `metadata.field_selected` trace events fire.
     */
    suspend fun fetchPersonDetail(
        metadataRequest: MetadataRequest,
        personId: Int,
        preferCrewCredits: Boolean = false
    ): PersonDetail? {
        val request = if (metadataRequest.contentId.startsWith("tvdb:person:")) {
            metadataRequest.copy(depth = MetadataDepth.DETAIL_SECONDARY)
        } else {
            metadataRequest.copy(
                contentId = "tmdb:person:$personId",
                depth = MetadataDepth.DETAIL_SECONDARY,
                sourceContext = metadataRequest.sourceContext.copy(
                    itemType = if (preferCrewCredits) PERSON_CREW_ITEM_TYPE else metadataRequest.sourceContext.itemType
                )
            )
        }
        val resolution = resolveRequest(request)
        return resolution.providerRunResult.toLegacyPersonDetailOrNull(preferCrewCredits)
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
            // Pass through the caller's season hint without a numeric fallback — when both are
            // absent the unconstrained path in fetchEpisodeMetadataForRoute will fetch all
            // available seasons from the provider (e.g. all Kitsu episodes regardless of season).
            seasonNumber = tvRequest.seasonNumbers.firstOrNull() ?: metadataRequest.seasonNumber
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
        val effectiveSeasons = seasonNumbers.ifEmpty { listOfNotNull(metadataSeasonNumber) }
        return if (effectiveSeasons.isEmpty()) {
            if (route.provider == MetadataPrimaryProvider.KITSU) {
                // Kitsu returns all episodes when seasonNumber is null; use an unconstrained route
                // so the provider adapter passes an empty season filter through to the Kitsu backend,
                // which then returns every episode regardless of franchise-relative season number.
                // Last-write-wins on duplicate (season, episode) keys from multiple step results —
                // acceptable because providers are expected to return disjoint episode ranges.
                val unconstrainedRoute = route.copy(seasonNumber = null)
                val plan = providerPlanExecutor.buildPlan(unconstrainedRoute, MetadataDepth.SEASON)
                providerPlanRunner.run(plan).stepResults
                    .flatMap { stepResult -> stepResult.episodeMetadata.entries }
                    .associate { it.toPair() }
            } else {
                // TVDB and TMDB require a seasonNumber at SEASON depth (enforced per-provider in
                // ProviderPlanExecutor). Default to season 1 — the correct fallback for standard TV
                // shows where the caller did not specify which season to hydrate.
                // Last-write-wins on duplicate (season, episode) keys — acceptable here too.
                val season1Route = route.copy(seasonNumber = 1)
                val plan = providerPlanExecutor.buildPlan(season1Route, MetadataDepth.SEASON)
                providerPlanRunner.run(plan).stepResults
                    .flatMap { stepResult -> stepResult.episodeMetadata.entries }
                    .associate { it.toPair() }
            }
        } else {
            // Season hints provided — fetch each requested season individually.
            // Last-write-wins on duplicate (season, episode) keys — acceptable because providers
            // are expected to return disjoint episode ranges.
            effectiveSeasons.flatMap { seasonNumber ->
                val seasonRoute = route.copy(seasonNumber = seasonNumber)
                val plan = providerPlanExecutor.buildPlan(seasonRoute, MetadataDepth.SEASON)
                providerPlanRunner.run(plan).stepResults.flatMap { stepResult ->
                    stepResult.episodeMetadata.entries
                }
            }
            .associate { it.toPair() }
        }.withRoutedEpisodeThumbnailArtwork(route)
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

    private suspend fun Map<Pair<Int, Int>, TvEpisodeMetadata>.withRoutedEpisodeThumbnailArtwork(
        route: MetadataRoute
    ): Map<Pair<Int, Int>, TvEpisodeMetadata> {
        val resolver = posterRatingsUrlResolver ?: return this
        if (isEmpty()) return this

        val settings = resolver.currentSettings()
        val providerIds = route.targetIds.toProviderIds()
        val primaryArtworkProvider = route.provider.toArtworkProviderId()
        return mapValues { (key, episodeMetadata) ->
            val season = episodeMetadata.seasonNumber ?: key.first
            val episode = episodeMetadata.episodeNumber ?: key.second
            val episodeContext = EpisodeArtworkContext(season = season, episode = episode)
            val artworkRef = resolver.resolveEpisodeThumbnailArtworkRef(
                settings = settings,
                providerIds = providerIds,
                mediaKind = route.mediaKind,
                ownerKey = ArtworkOwnerKey.CanonicalContent(
                    "${route.parentId}:S${episodeContext.season}E${episodeContext.episode}"
                ),
                episodeContext = episodeContext,
                fallbackThumbnailUrl = episodeMetadata.thumbnail,
                primaryProvider = primaryArtworkProvider
            )
            if (artworkRef == null) episodeMetadata else episodeMetadata.copy(thumbnailArtwork = artworkRef)
        }
    }

    private fun MetadataPrimaryProvider.toArtworkProviderId(): ArtworkProviderId =
        when (this) {
            MetadataPrimaryProvider.TMDB -> ArtworkProviderId.RuntimeProvider(IntegrationProvider.TMDB)
            MetadataPrimaryProvider.TVDB -> ArtworkProviderId.RuntimeProvider(IntegrationProvider.TVDB)
            MetadataPrimaryProvider.KITSU -> ArtworkProviderId.RuntimeProvider(IntegrationProvider.KITSU)
            MetadataPrimaryProvider.TRAKT -> ArtworkProviderId.RuntimeProvider(IntegrationProvider.TRAKT)
            MetadataPrimaryProvider.SIMKL -> ArtworkProviderId.RuntimeProvider(IntegrationProvider.SIMKL)
            MetadataPrimaryProvider.RPDB -> ArtworkProviderId.RuntimeProvider(IntegrationProvider.RPDB)
            MetadataPrimaryProvider.TOP_POSTERS -> ArtworkProviderId.RuntimeProvider(IntegrationProvider.TOP_POSTERS)
            MetadataPrimaryProvider.IMDB -> ArtworkProviderId.RuntimeProvider(IntegrationProvider.CUSTOM_IMDB)
        }

    private fun Map<MetadataPrimaryProvider, String>.toProviderIds(): ProviderIds =
        ProviderIds(
            imdb = imdbTargetId(this[MetadataPrimaryProvider.IMDB]),
            tmdb = numericTargetId(this[MetadataPrimaryProvider.TMDB], "tmdb"),
            tvdb = numericTargetId(this[MetadataPrimaryProvider.TVDB], "tvdb"),
            trakt = numericTargetId(this[MetadataPrimaryProvider.TRAKT], "trakt"),
            kitsu = numericTargetId(this[MetadataPrimaryProvider.KITSU], "kitsu")
        )

    private fun imdbTargetId(raw: String?): String? {
        val value = raw?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val candidate = when {
            value.startsWith("imdb:", ignoreCase = true) -> value.substringAfter(':').trim()
            ':' in value -> return null
            else -> value
        }
        return candidate.takeIf { it.matches(IMDB_ID_REGEX) }
    }

    private fun numericTargetId(raw: String?, prefix: String): String? {
        val value = raw?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val candidate = when {
            value.startsWith("$prefix:", ignoreCase = true) -> value.substringAfter(':').trim()
            ':' in value -> return null
            else -> value
        }
        return candidate.takeIf { it.matches(NUMERIC_ID_REGEX) }
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
            .associateBy { episodeMetadata ->
                (episodeMetadata.seasonNumber ?: seasonNumber) to (episodeMetadata.episodeNumber ?: Int.MAX_VALUE)
            }
            .withRoutedEpisodeThumbnailArtwork(route)
            .values
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
            (artwork?.poster ?: poster)?.let { put(ResolvedField.POSTER, FieldValue(it, FieldOwner.PRIMARY)) }
            (artwork?.backdrop ?: backdrop)?.let { put(ResolvedField.BACKDROP, FieldValue(it, FieldOwner.PRIMARY)) }
            (artwork?.logo ?: logo)?.let { put(ResolvedField.LOGO, FieldValue(it, FieldOwner.PRIMARY)) }
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
            genres = genres.ifEmpty { fallback.genres },
            posterProviderTag = resolvedPosterProviderTag(fallback),
            artwork = mergeResolvedArtwork(fallback)
        )

    private fun ResolvedMetadataDocument.resolvedPosterProviderTag(fallback: HomeDisplayMetadata): String? {
        val selectedPoster = poster
        val selectedRole = sourceRoles[ResolvedField.POSTER]
        val selectedProvider = sourceProviders[ResolvedField.POSTER]
            ?.trim()
            ?.takeIf { it.isNotBlank() }

        return when {
            selectedPoster == null -> fallback.posterProviderTag
            selectedRole == SourceRole.RAIL_PREVIEW || selectedRole == SourceRole.ADDON_PREVIEW -> fallback.posterProviderTag
            selectedRole == SourceRole.ARTWORK && selectedProvider != null -> selectedProvider.lowercase()
            else -> null
        }
    }

    private fun ResolvedMetadataDocument.mergeResolvedArtwork(fallback: HomeDisplayMetadata): ArtworkBundle? {
        val selectedArtwork = artwork.takeUnless { it.isEmpty() }
        val fallbackArtwork = fallback.artwork ?: return selectedArtwork
        val merged = ArtworkBundle(
            poster = selectedArtwork?.poster ?: fallbackArtwork.poster.takeIf { poster == null },
            backdrop = selectedArtwork?.backdrop ?: fallbackArtwork.backdrop.takeIf { backdrop == null },
            logo = selectedArtwork?.logo ?: fallbackArtwork.logo.takeIf { logo == null },
            thumbnail = selectedArtwork?.thumbnail ?: fallbackArtwork.thumbnail
        )
        return merged.takeUnless { it.isEmpty() }
    }

    private fun ArtworkBundle.isEmpty(): Boolean =
        poster == null && backdrop == null && logo == null && thumbnail == null

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
            platformName = platformName,
            remoteIds = remoteIds
        )

    private fun ResolvedMetadataDocument.toLegacyTmdbEnrichmentOrNull(): TmdbEnrichment? {
        val hasData = listOf(
            title,
            overview,
            poster,
            backdrop,
            logo,
            releaseDate,
            ageRating,
            language,
            runtimeMinutes,
            rating
        ).any { it != null } ||
            genres.isNotEmpty() ||
            countries.isNotEmpty() ||
            castMembers.isNotEmpty() ||
            productionCompanies.isNotEmpty() ||
            networks.isNotEmpty()
        if (!hasData) return null

        return TmdbEnrichment(
            localizedTitle = title,
            description = overview,
            genres = genres,
            backdrop = backdrop,
            logo = logo,
            poster = poster,
            directorMembers = emptyList(),
            writerMembers = emptyList(),
            castMembers = castMembers,
            releaseInfo = releaseDate,
            rating = (rating as? Number)?.toDouble(),
            runtimeMinutes = runtimeMinutes,
            director = emptyList(),
            writer = emptyList(),
            productionCompanies = productionCompanies,
            networks = networks,
            ageRating = ageRating,
            countries = countries.takeIf { it.isNotEmpty() },
            language = language,
            collectionId = null,
            collectionName = null
        )
    }

    private fun ProviderPlanRunResult?.toLegacyReviewsPage(page: Int, limit: Int): ReviewsPage {
        val reviews = this.toFieldValues(ResolvedField.REVIEWS)
            .flatMap { value ->
                when (value) {
                    is MetaReview -> listOf(value)
                    is Collection<*> -> value.filterIsInstance<MetaReview>()
                    else -> emptyList()
                }
            }
        val hasMore = reviews.size >= limit
        return ReviewsPage(
            reviews = reviews,
            hasMore = hasMore,
            nextPage = if (hasMore) page + 1 else null
        )
    }

    private fun ProviderPlanRunResult?.toLegacyRecommendations(): List<MetaPreview> =
        this.toFieldValues(ResolvedField.RECOMMENDATIONS)
            .flatMap { value ->
                when (value) {
                    is MetaPreview -> listOf(value)
                    is Collection<*> -> value.filterIsInstance<MetaPreview>()
                    else -> emptyList()
                }
            }

    private fun ProviderPlanRunResult?.toTrailerPlaybackRefs(): List<TrailerPlaybackRef> =
        toFieldValues(ResolvedField.TRAILERS)
            .flatMap(::trailerPlaybackRefsFrom)

    private fun ProviderPlanRunResult?.toSeasonTrailerPlaybackRefs(): List<TrailerPlaybackRef> =
        this?.stepResults
            ?.filter { stepResult -> stepResult.step.apiShapeId == TmdbApiShapes.SEASON_VIDEOS }
            ?.mapNotNull { stepResult -> stepResult.candidate?.fields?.get(ResolvedField.TRAILERS)?.value }
            ?.flatMap(::trailerPlaybackRefsFrom)
            .orEmpty()

    private fun seasonTrailerResolveRequest(
        metadataRequest: MetadataRequest,
        title: String,
        year: String?,
        tmdbId: String?,
        type: String?,
        seasonNumber: Int?,
        contentId: String?,
        providerCandidates: List<TrailerPlaybackRef>,
        itemKeySuffix: String? = null
    ): TrailerResolveRequest {
        val itemKey = buildString {
            append(contentId ?: metadataRequest.contentId)
            seasonNumber?.let { append(":season:").append(it) }
            itemKeySuffix?.let { append(':').append(it) }
        }
        return TrailerResolveRequest(
            itemKey = itemKey,
            title = title,
            year = year,
            stableIds = ProviderIds(
                tmdb = tmdbId?.trim()?.takeIf { it.isNotBlank() }
            ),
            fallbackYtIds = emptyList(),
            surface = TrailerSurface.DETAIL,
            type = type,
            seasonNumber = seasonNumber,
            contentId = contentId ?: metadataRequest.contentId,
            providerCandidates = providerCandidates
        )
    }

    private suspend fun TrailerPlaybackRef.toSeasonPlaybackSource(
        service: TrailerService?,
        title: String,
        year: String?
    ): TrailerPlaybackSource? {
        if (this is TrailerPlaybackRef.InAppSource) {
            return toInlinePlaybackSource()
        }
        val transported = service?.resolvePlaybackSource(
            ref = this,
            title = title,
            year = year
        )
        return when (transported) {
            is TrailerResolutionResult.Playback -> transported.source
            is TrailerResolutionResult.External,
            null -> toInlinePlaybackSource()
        }
    }

    private suspend fun TrailerResolution.toFirstSeasonPlaybackSource(
        service: TrailerService?,
        title: String,
        year: String?
    ): TrailerPlaybackSource? {
        for (candidate in candidates) {
            candidate.toSeasonPlaybackSource(service, title, year)?.let { return it }
        }
        return null
    }

    private fun TrailerPlaybackRef.toInlinePlaybackSource(): TrailerPlaybackSource? =
        when (this) {
            is TrailerPlaybackRef.InAppSource -> TrailerPlaybackSource(
                videoUrl = videoUrl,
                audioUrl = audioUrl,
                userAgent = userAgent
            )
            is TrailerPlaybackRef.ExternalUrl,
            is TrailerPlaybackRef.ItemLookup,
            is TrailerPlaybackRef.YouTubeId -> null
        }

    private fun ProviderPlanRunResult?.toLegacyPersonIdOrNull(): Int? =
        (
            this.toFieldValues(ResolvedField.CANONICAL_ID) +
                this.toFieldValues(ResolvedField.CAST) +
                this.toFieldValues(ResolvedField.CREW)
            ).firstNotNullOfOrNull { value ->
            when (value) {
                is PersonDetail -> value.tmdbId
                is String -> value.tmdbNumericSuffix()
                is Collection<*> -> value.firstNotNullOfOrNull { item ->
                    when (item) {
                        is PersonDetail -> item.tmdbId
                        is String -> item.tmdbNumericSuffix()
                        else -> null
                    }
                }
                else -> null
            }
        }

    private fun ProviderPlanRunResult?.toLegacyCompanyIdOrNull(): Int? =
        (
            this.toFieldValues(ResolvedField.CANONICAL_ID) +
                this.toFieldValues(ResolvedField.ORGANIZATION_LIST)
            ).firstNotNullOfOrNull { value ->
            when (value) {
                is MetaCompany -> value.tmdbId
                is Int -> value
                is String -> value.tmdbNumericSuffix()
                is Collection<*> -> value.firstNotNullOfOrNull { item ->
                    when (item) {
                        is MetaCompany -> item.tmdbId
                        is Int -> item
                        is String -> item.tmdbNumericSuffix()
                        else -> null
                    }
                }
                else -> null
            }
        }

    private fun ProviderPlanRunResult?.toLegacyPersonDetailOrNull(preferCrewCredits: Boolean): PersonDetail? {
        val preferredFields = if (preferCrewCredits) {
            listOf(ResolvedField.CREW, ResolvedField.CAST)
        } else {
            listOf(ResolvedField.CAST, ResolvedField.CREW)
        }
        return preferredFields.firstNotNullOfOrNull { field ->
            this.toFieldValues(field).firstNotNullOfOrNull { value ->
                when (value) {
                    is PersonDetail -> value
                    is Collection<*> -> value.filterIsInstance<PersonDetail>().firstOrNull()
                    else -> null
                }
            }
        }
    }

    private fun ProviderPlanRunResult?.toFieldValues(field: ResolvedField): List<Any?> =
        this?.stepResults
            ?.mapNotNull { stepResult -> stepResult.candidate?.fields?.get(field)?.value }
            .orEmpty()

    private fun trailerPlaybackRefsFrom(value: Any?): List<TrailerPlaybackRef> =
        when (value) {
            is TrailerResolutionResult -> listOf(value.toTrailerPlaybackRef())
            is TrailerPlaybackSource -> listOf(value.toTrailerPlaybackRef())
            is TmdbVideoResult -> rankedTmdbTrailerPlaybackRefs(listOf(value))
            is String -> listOfNotNull(value.toTrailerPlaybackRef())
            is Collection<*> -> {
                val tmdbVideos = value.filterIsInstance<TmdbVideoResult>()
                if (tmdbVideos.size == value.size) {
                    rankedTmdbTrailerPlaybackRefs(tmdbVideos)
                } else {
                    value.flatMap(::trailerPlaybackRefsFrom)
                }
            }
            else -> emptyList()
        }

    private fun TrailerResolutionResult.toTrailerPlaybackRef(): TrailerPlaybackRef =
        when (this) {
            is TrailerResolutionResult.Playback -> source.toTrailerPlaybackRef()
            is TrailerResolutionResult.External -> TrailerPlaybackRef.ExternalUrl(url)
        }

    private fun TrailerPlaybackSource.toTrailerPlaybackRef(): TrailerPlaybackRef =
        TrailerPlaybackRef.InAppSource(
            videoUrl = videoUrl,
            audioUrl = audioUrl,
            userAgent = userAgent
        )

    private fun String.toTrailerPlaybackRef(): TrailerPlaybackRef? {
        val normalized = trim().takeIf { it.isNotBlank() } ?: return null
        return if ("://" in normalized) {
            TrailerPlaybackRef.ExternalUrl(normalized)
        } else {
            TrailerPlaybackRef.YouTubeId(normalized)
        }
    }

    private fun String.tmdbNumericSuffix(): Int? =
        substringAfterLast(':').trim().toIntOrNull()

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
        const val PERSON_CREW_ITEM_TYPE = "person_crew"
        const val DEFAULT_REVIEWS_PAGE = 1
        const val DEFAULT_REVIEWS_LIMIT = 20
        val IMDB_ID_REGEX = Regex("tt\\d+", RegexOption.IGNORE_CASE)
        val NUMERIC_ID_REGEX = Regex("\\d+")
    }
}
