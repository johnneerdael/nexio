package com.nexio.tv.core.metadata.router

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProviderPlanRunner @Inject constructor(
    private val adapters: Set<@JvmSuppressWildcards MetadataProviderAdapter>,
    private val traceEvents: com.nexio.tv.core.trace.TraceMetadataEvents = com.nexio.tv.core.trace.TraceMetadataEvents(
        sink = com.nexio.tv.core.trace.NoopRuntimeTraceSink,
        sessionId = { null }
    )
) {
    suspend fun run(plan: ProviderExecutionPlan): ProviderPlanRunResult {
        traceEvents.emitProviderPlan(
            contentId = plan.route.parentId,
            provider = plan.route.provider.name,
            mediaKind = plan.route.mediaKind.name,
            depth = plan.depth.name,
            steps = plan.steps.map { step ->
                mapOf(
                    "stepId" to step.apiShapeId,
                    "apiShapeId" to step.apiShapeId,
                    "workClass" to "USER_VISIBLE",
                    "cachePolicy" to "provider-metadata",
                    "requiresIdentityResolution" to plan.route.targetIdRequiresIdentityResolution,
                    "role" to step.role.name,
                    "required" to step.required
                )
            }
        )

        val stepResults = plan.steps.mapNotNull { step ->
            val adapter = adapters.firstOrNull { it.provider == step.provider && it.supports(step) }
            when {
                adapter != null -> adapter.execute(route = plan.route, step = step)
                step.required -> throw MetadataRouteFailure.MissingPlanStepAdapter(step.apiShapeId)
                else -> null  // optional step with no registered adapter — skip silently
            }
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
