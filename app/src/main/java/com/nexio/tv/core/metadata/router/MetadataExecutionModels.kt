package com.nexio.tv.core.metadata.router

import com.nexio.tv.domain.model.HomeDisplayMetadata

data class ProviderStepResult(
    val step: ProviderPlanStep,
    val candidate: MetadataCandidate? = null,
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

data class MetadataResolutionResult(
    val route: MetadataRoute?,
    val plan: ProviderExecutionPlan?,
    val resolverSchedule: ResolverSchedule,
    val resolvedDocument: ResolvedMetadataDocument,
    val displayMetadata: HomeDisplayMetadata,
    val trace: List<MetadataRouteTrace>
)

sealed class MetadataRouteFailure(message: String) : RuntimeException(message) {
    class IdentityResolutionFailed(parentId: String, provider: MetadataPrimaryProvider) :
        MetadataRouteFailure("Identity resolution failed for $parentId before $provider execution")

    class MissingPlanStepAdapter(apiShapeId: String) :
        MetadataRouteFailure("No metadata provider adapter mapped apiShapeId=$apiShapeId")
}
