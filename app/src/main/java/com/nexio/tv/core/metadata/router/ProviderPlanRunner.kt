package com.nexio.tv.core.metadata.router

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProviderPlanRunner @Inject constructor(
    private val adapters: Set<@JvmSuppressWildcards MetadataProviderAdapter>
) {
    suspend fun run(plan: ProviderExecutionPlan): ProviderPlanRunResult {
        val stepResults = plan.steps.map { step ->
            val adapter = adapters.firstOrNull { it.provider == step.provider && it.supports(step) }
                ?: throw MetadataRouteFailure.MissingPlanStepAdapter(step.apiShapeId)
            adapter.execute(route = plan.route, step = step)
        }

        val candidates = stepResults.mapNotNull { it.candidate }
        val primary = candidates.firstOrNull { candidate -> candidate.provider == plan.route.provider }
            ?: MetadataCandidate(provider = plan.route.provider, fields = emptyMap())
        val secondary = candidates.filterNot { candidate -> candidate === primary }

        return ProviderPlanRunResult(
            route = plan.route,
            depth = plan.depth,
            primaryCandidate = primary,
            secondaryCandidates = secondary,
            stepResults = stepResults,
            trace = plan.route.trace + stepResults.flatMap { it.trace }
        )
    }
}
