package com.nexio.tv.data.integration.metadata

import com.nexio.tv.core.integration.TmdbApiShapes
import com.nexio.tv.core.metadata.router.FieldOwner
import com.nexio.tv.core.metadata.router.FieldValue
import com.nexio.tv.core.metadata.router.MetadataCandidate
import com.nexio.tv.core.metadata.router.MetadataPrimaryProvider
import com.nexio.tv.core.metadata.router.MetadataProviderAdapter
import com.nexio.tv.core.metadata.router.MetadataRoute
import com.nexio.tv.core.metadata.router.ProviderPlanStep
import com.nexio.tv.core.metadata.router.ProviderStepResult
import com.nexio.tv.core.metadata.router.ResolvedField
import com.nexio.tv.core.metadata.router.ResolverType
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.MetaPreview
import javax.inject.Inject
import javax.inject.Singleton

/**
 * TMDB recommendation adapter. Delegates to [MetadataSecondaryRepository.fetchMoreLikeThis]
 * (TMDB "more like this" / recommendations endpoint) and emits a
 * [ResolvedField.RECOMMENDATIONS] candidate for the
 * [com.nexio.tv.core.metadata.router.resolver.RecommendationResolver] to consume.
 *
 * The base TMDB adapter still claims `MOVIE_RECOMMENDATIONS`/`TV_RECOMMENDATIONS` from the
 * legacy plan path — this adapter is reached only after the orchestrator schedules
 * recommendation-aware dispatch (follow-up tasks). Both adapters report `provider = TMDB`;
 * the dispatcher must filter by `supports(step)` keyed on this adapter's narrower shape
 * ownership.
 */
@Singleton
class TmdbRecommendationMetadataAdapter @Inject constructor(
    private val secondaryRepository: MetadataSecondaryRepository
) : MetadataProviderAdapter {
    override val provider: MetadataPrimaryProvider = MetadataPrimaryProvider.TMDB

    override fun supports(step: ProviderPlanStep): Boolean = step.apiShapeId in RECOMMENDATION_SHAPES

    override suspend fun execute(route: MetadataRoute, step: ProviderPlanStep): ProviderStepResult {
        val tmdbId = MetadataProviderTargetIds.tmdbInt(route.targetIds[MetadataPrimaryProvider.TMDB])
            ?: return ProviderStepResult(step = step, candidate = emptyCandidate(this.provider))
        val contentType: ContentType = when (step.apiShapeId) {
            TmdbApiShapes.MOVIE_RECOMMENDATIONS -> ContentType.MOVIE
            TmdbApiShapes.TV_RECOMMENDATIONS -> ContentType.SERIES
            else -> return ProviderStepResult(step = step, candidate = emptyCandidate(this.provider))
        }
        val recommendations: List<MetaPreview> = secondaryRepository.fetchMoreLikeThis(
            tmdbId = tmdbId.toString(),
            contentType = contentType
        )
        if (recommendations.isEmpty()) {
            return ProviderStepResult(step = step, candidate = emptyCandidate(this.provider))
        }
        return ProviderStepResult(
            step = step,
            candidate = MetadataCandidate(
                provider = this.provider,
                resolverType = ResolverType.RECOMMENDATIONS,
                fields = mapOf(
                    ResolvedField.RECOMMENDATIONS to FieldValue(recommendations, FieldOwner.RECOMMENDATIONS)
                )
            )
        )
    }

    private companion object {
        val RECOMMENDATION_SHAPES = setOf(
            TmdbApiShapes.MOVIE_RECOMMENDATIONS,
            TmdbApiShapes.TV_RECOMMENDATIONS
        )
    }
}
