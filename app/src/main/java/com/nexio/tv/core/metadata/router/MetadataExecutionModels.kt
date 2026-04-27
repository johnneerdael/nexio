package com.nexio.tv.core.metadata.router

import com.nexio.tv.domain.model.HomeDisplayMetadata
import com.nexio.tv.core.tvdb.TvEpisodeMetadata

data class ProviderStepResult(
    val step: ProviderPlanStep,
    val candidate: MetadataCandidate? = null,
    val episodeMetadata: Map<Pair<Int, Int>, TvEpisodeMetadata> = emptyMap(),
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
