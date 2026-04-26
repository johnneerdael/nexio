package com.nexio.tv.core.metadata.router

import com.nexio.tv.core.tvdb.ProviderMetadataRouter
import com.nexio.tv.core.tvdb.TvEpisodeMetadata
import com.nexio.tv.core.tvdb.TvMetadataDecision
import com.nexio.tv.core.tvdb.TvMetadataEnrichment
import com.nexio.tv.core.tvdb.TvMetadataRequest
import com.nexio.tv.core.tvdb.TvSeasonEpisode
import com.nexio.tv.domain.model.HomeDisplayMetadata
import javax.inject.Inject
import javax.inject.Singleton

data class MetadataFacadeResult(
    val route: MetadataRoute?,
    val plan: ProviderExecutionPlan?,
    val resolverSchedule: ResolverSchedule,
    val displayMetadata: HomeDisplayMetadata
)

@Singleton
class MetadataRouterFacade @Inject constructor(
    private val router: MetadataRouter,
    private val providerPlanExecutor: ProviderPlanExecutor,
    private val resolverOrchestrator: ResolverOrchestrator,
    private val providerMetadataRouter: ProviderMetadataRouter? = null
) {
    suspend fun resolveRequest(request: MetadataRequest): MetadataFacadeResult {
        val resolverSchedule = resolverOrchestrator.schedule(request.depth)
        val initialDisplay = request.sourceContext.addonMetadata ?: HomeDisplayMetadata()

        if (request.depth == MetadataDepth.PREVIEW) {
            return MetadataFacadeResult(
                route = null,
                plan = null,
                resolverSchedule = resolverSchedule,
                displayMetadata = initialDisplay
            )
        }

        val route = router.route(request)
        val plan = if (route.targetIdRequiresIdentityResolution) {
            null
        } else {
            providerPlanExecutor.buildPlan(route = route, depth = request.depth)
        }

        return MetadataFacadeResult(
            route = route,
            plan = plan,
            resolverSchedule = resolverSchedule,
            displayMetadata = initialDisplay
        )
    }

    suspend fun fetchTvEnrichment(
        metadataRequest: MetadataRequest,
        tvRequest: TvMetadataRequest
    ): TvMetadataDecision<TvMetadataEnrichment> {
        val routeResult = resolveRequest(metadataRequest)
        check(routeResult.route?.targetIdRequiresIdentityResolution != true) {
            "TV enrichment route requires identity resolution before provider execution for ${metadataRequest.contentId}"
        }
        return requireProviderMetadataRouter().fetchEnrichment(tvRequest)
    }

    suspend fun fetchTvEpisodeEnrichment(
        metadataRequest: MetadataRequest,
        tvRequest: TvMetadataRequest
    ): TvMetadataDecision<Map<Pair<Int, Int>, TvEpisodeMetadata>> {
        val routeResult = resolveRequest(metadataRequest)
        check(routeResult.route?.targetIdRequiresIdentityResolution != true) {
            "TV episode route requires identity resolution before provider execution for ${metadataRequest.contentId}"
        }
        return requireProviderMetadataRouter().fetchEpisodeEnrichment(tvRequest)
    }

    suspend fun fetchTvSeasonEpisodes(
        metadataRequest: MetadataRequest,
        contentId: String,
        fallbackContentId: String?,
        seasonNumber: Int,
        language: String? = null
    ): TvMetadataDecision<List<TvSeasonEpisode>> {
        val routeResult = resolveRequest(metadataRequest)
        check(routeResult.route?.targetIdRequiresIdentityResolution != true) {
            "TV season route requires identity resolution before provider execution for ${metadataRequest.contentId}"
        }
        return requireProviderMetadataRouter().fetchSeasonEpisodes(
            contentId = contentId,
            fallbackContentId = fallbackContentId,
            seasonNumber = seasonNumber,
            language = language
        )
    }

    private fun requireProviderMetadataRouter(): ProviderMetadataRouter =
        checkNotNull(providerMetadataRouter) {
            "ProviderMetadataRouter must be provided for TV metadata execution"
        }
}
