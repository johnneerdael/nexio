package com.nexio.tv.core.metadata.router

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
    private val resolverOrchestrator: ResolverOrchestrator
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
        val plan = providerPlanExecutor.buildPlan(route = route, depth = request.depth)

        return MetadataFacadeResult(
            route = route,
            plan = plan,
            resolverSchedule = resolverSchedule,
            displayMetadata = initialDisplay
        )
    }
}
