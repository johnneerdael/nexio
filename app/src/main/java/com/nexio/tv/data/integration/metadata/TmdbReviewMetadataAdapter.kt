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
import com.nexio.tv.domain.model.MetaReview
import javax.inject.Inject
import javax.inject.Singleton

/**
 * TMDB review adapter. Delegates to [MetadataSecondaryRepository.fetchReviews] which fronts
 * [com.nexio.tv.core.tmdb.TmdbMetadataService] (cache + en-US fallback semantics); emits a
 * [ResolvedField.REVIEWS] candidate for the [com.nexio.tv.core.metadata.router.resolver.ReviewResolver]
 * to consume.
 *
 * The base TMDB adapter still claims `MOVIE_REVIEWS`/`TV_REVIEWS` from the legacy plan path —
 * this adapter is reached only after the orchestrator schedules review-aware dispatch
 * (follow-up tasks). Both adapters report `provider = TMDB`; the dispatcher must filter by
 * `supports(step)` keyed on the review adapter's narrower shape ownership.
 */
@Singleton
class TmdbReviewMetadataAdapter @Inject constructor(
    private val secondaryRepository: MetadataSecondaryRepository
) : MetadataProviderAdapter {
    override val provider: MetadataPrimaryProvider = MetadataPrimaryProvider.TMDB

    override fun supports(step: ProviderPlanStep): Boolean = step.apiShapeId in REVIEW_SHAPES

    override fun priorityFor(step: ProviderPlanStep): Int =
        if (supports(step)) SECONDARY_ADAPTER_PRIORITY else 0

    override suspend fun execute(route: MetadataRoute, step: ProviderPlanStep): ProviderStepResult {
        val tmdbId = MetadataProviderTargetIds.tmdbInt(route.targetIds[MetadataPrimaryProvider.TMDB])
            ?: return ProviderStepResult(step = step, candidate = emptyCandidate(this.provider))
        val contentType: ContentType = when (step.apiShapeId) {
            TmdbApiShapes.MOVIE_REVIEWS -> ContentType.MOVIE
            TmdbApiShapes.TV_REVIEWS -> ContentType.SERIES
            else -> return ProviderStepResult(step = step, candidate = emptyCandidate(this.provider))
        }
        val reviews: List<MetaReview> = secondaryRepository.fetchReviews(
            tmdbId = tmdbId.toString(),
            contentType = contentType
        )
        if (reviews.isEmpty()) {
            return ProviderStepResult(step = step, candidate = emptyCandidate(this.provider))
        }
        return ProviderStepResult(
            step = step,
            candidate = MetadataCandidate(
                provider = this.provider,
                resolverType = ResolverType.REVIEWS,
                fields = mapOf(
                    ResolvedField.REVIEWS to FieldValue(reviews, FieldOwner.REVIEWS)
                )
            )
        )
    }

    private companion object {
        val REVIEW_SHAPES = setOf(
            TmdbApiShapes.MOVIE_REVIEWS,
            TmdbApiShapes.TV_REVIEWS
        )
        const val SECONDARY_ADAPTER_PRIORITY = 100
    }
}
