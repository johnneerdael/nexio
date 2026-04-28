package com.nexio.tv.core.metadata.router

import com.nexio.tv.domain.model.HomeDisplayMetadata
import com.nexio.tv.core.tvdb.TvEpisodeMetadata

data class ProviderStepResult(
    val step: ProviderPlanStep,
    val candidate: MetadataCandidate? = null,
    val episodeMetadata: Map<Pair<Int, Int>, TvEpisodeMetadata> = emptyMap(),
    val episodeLocalization: Map<Pair<Int, Int>, Map<ResolvedField, MetadataLocalizationFieldTrace>> = emptyMap(),
    val localizationPayloads: List<MetadataLocalizationPayloadTrace> = emptyList(),
    val trace: List<MetadataRouteTrace> = emptyList()
)

data class ProviderPlanRunResult(
    val route: MetadataRoute,
    val depth: MetadataDepth,
    val primaryCandidate: MetadataCandidate,
    val secondaryCandidates: List<MetadataCandidate>,
    val stepResults: List<ProviderStepResult>,
    val trace: List<MetadataRouteTrace>
)

/**
 * Returns the PRIMARY_CORE step's candidate iff that candidate carries the requested [field].
 *
 * Used by the resolver dispatch loop in [MetadataRouterFacade.resolveRequest] to feed
 * `primary` into the per-field resolvers (TRAILERS / REVIEWS / RECOMMENDATIONS /
 * ORGANIZATION_PERSON). Returns null when the primary step did not contribute the field —
 * the resolver will then pick from secondaries only.
 */
fun ProviderPlanRunResult.primaryCandidateFor(field: ResolvedField): MetadataCandidate? =
    stepResults.firstOrNull { stepResult ->
        stepResult.step.role == ProviderPlanRole.PRIMARY_CORE &&
            stepResult.candidate?.fields?.containsKey(field) == true
    }?.candidate

/**
 * Returns every non-PRIMARY_CORE step candidate that carries the requested [field], in
 * plan order. Includes MEDIA / SECONDARY / SEASON / PLAYER step candidates.
 */
fun ProviderPlanRunResult.secondaryCandidatesFor(field: ResolvedField): List<MetadataCandidate> =
    stepResults.mapNotNull { stepResult ->
        val candidate = stepResult.candidate ?: return@mapNotNull null
        if (stepResult.step.role == ProviderPlanRole.PRIMARY_CORE) return@mapNotNull null
        if (!candidate.fields.containsKey(field)) return@mapNotNull null
        candidate
    }

data class MetadataResolutionResult(
    val route: MetadataRoute?,
    val plan: ProviderExecutionPlan?,
    val resolverSchedule: ResolverSchedule,
    val resolvedDocument: ResolvedMetadataDocument,
    val displayMetadata: HomeDisplayMetadata,
    val trace: List<MetadataRouteTrace>,
    val providerRunResult: ProviderPlanRunResult? = null
)

sealed class MetadataRouteFailure(message: String) : RuntimeException(message) {
    class IdentityResolutionFailed(parentId: String, provider: MetadataPrimaryProvider) :
        MetadataRouteFailure("Identity resolution failed for $parentId before $provider execution")

    class MissingPlanStepAdapter(apiShapeId: String) :
        MetadataRouteFailure("No metadata provider adapter mapped apiShapeId=$apiShapeId")
}
