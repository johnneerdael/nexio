package com.nexio.tv.core.metadata.router

import com.nexio.tv.core.artwork.ArtworkDisplayRef
import com.nexio.tv.core.artwork.toLegacyArtworkString
import com.nexio.tv.domain.model.HomeDisplayMetadata
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

        // Indexed-for + mutableListOf instead of `plan.steps.mapNotNull { ... suspend ... }`.
        // mapNotNull allocates an Iterator whose this$0 pins `plan.steps` across every
        // adapter.execute() suspension; with concurrent ProviderPlanRunner.run calls this
        // accumulates thousands of pinned iterators in continuations (HARD RULE #4 in
        // CLAUDE.md). Indexed-for compiles to a primitive int counter — no iterator,
        // nothing pinned across suspensions.
        val stepResults = mutableListOf<ProviderStepResult>()
        var primaryCandidateForFallback: MetadataCandidate? = null
        for (i in plan.steps.indices) {
            val step = plan.steps[i]
            val adapter = adapters
                .filter { it.provider == step.provider && it.supports(step) }
                .sortedWith(
                    compareByDescending<MetadataProviderAdapter> { it.priorityFor(step) }
                        .thenBy { it::class.qualifiedName.orEmpty() }
                )
                .firstOrNull()
            val routeForStep = if (step.role == ProviderPlanRole.ARTWORK) {
                plan.route.withPrimaryPosterFallback(primaryCandidateForFallback)
            } else {
                plan.route
            }
            val result = when {
                adapter != null -> adapter.execute(route = routeForStep, step = step)
                step.required -> throw MetadataRouteFailure.MissingPlanStepAdapter(step.apiShapeId)
                else -> null  // optional step with no registered adapter — skip silently
            }
            if (result != null) stepResults += result
            val candidate = result?.candidate
            if (candidate != null && candidate.provider == plan.route.provider) {
                primaryCandidateForFallback = candidate
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

    private fun MetadataRoute.withPrimaryPosterFallback(primary: MetadataCandidate?): MetadataRoute {
        val poster = primary?.fields?.get(ResolvedField.POSTER)?.value.toPosterFallbackString()
            ?: return this
        val currentMetadata = sourceContext.addonMetadata
        val fallbackMetadata = (currentMetadata ?: HomeDisplayMetadata()).copy(poster = poster)
        return copy(sourceContext = sourceContext.copy(addonMetadata = fallbackMetadata))
    }

    private fun Any?.toPosterFallbackString(): String? =
        when (this) {
            is ArtworkDisplayRef -> toLegacyArtworkString()
            is String -> trim().takeIf { it.isNotEmpty() }
            else -> null
        }
}
