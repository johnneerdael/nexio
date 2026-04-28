package com.nexio.tv.data.integration.metadata

import com.nexio.tv.core.integration.TraktApiShapes
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
import com.nexio.tv.data.repository.ReviewsRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Trakt review adapter. Wraps [ReviewsRepository.fetchTraktReviewPage] for the
 * `trakt.movie.comments` / `trakt.show.comments` API shapes and emits a
 * [ResolvedField.REVIEWS] candidate for the
 * [com.nexio.tv.core.metadata.router.resolver.ReviewResolver] to consume.
 *
 * Trakt accepts IMDB ids natively as a canonical lookup key, so the adapter pulls
 * the IMDB target id off [MetadataRoute.targetIds] when present and routes the
 * `isShow` flag from [com.nexio.tv.core.metadata.router.MetadataMediaKind].
 */
@Singleton
class TraktReviewMetadataAdapter @Inject constructor(
    private val reviewsRepository: ReviewsRepository
) : MetadataProviderAdapter {
    override val provider: MetadataPrimaryProvider = MetadataPrimaryProvider.TRAKT

    override fun supports(step: ProviderPlanStep): Boolean = step.apiShapeId in REVIEW_SHAPES

    override suspend fun execute(route: MetadataRoute, step: ProviderPlanStep): ProviderStepResult {
        val pathId = route.targetIds[MetadataPrimaryProvider.IMDB]?.stripImdbPrefix()
            ?: return ProviderStepResult(step = step, candidate = emptyCandidate(this.provider))
        val isShow: Boolean = when (step.apiShapeId) {
            TraktApiShapes.MOVIE_COMMENTS -> false
            TraktApiShapes.SHOW_COMMENTS -> true
            else -> return ProviderStepResult(step = step, candidate = emptyCandidate(this.provider))
        }
        // F-05-02 Task 6c: read page/limit from the route's pagination cursor when supplied
        // by MetadataRouterFacade.fetchReviewsPage(...); otherwise default to first page.
        val pageNumber = route.pagination?.page ?: DEFAULT_PAGE
        val limit = route.pagination?.limit ?: DEFAULT_LIMIT
        val page = reviewsRepository.fetchTraktReviewPage(
            pathId = pathId,
            isShow = isShow,
            page = pageNumber,
            limit = limit
        ) ?: return ProviderStepResult(step = step, candidate = emptyCandidate(this.provider))
        if (page.reviews.isEmpty()) {
            return ProviderStepResult(step = step, candidate = emptyCandidate(this.provider))
        }
        return ProviderStepResult(
            step = step,
            candidate = MetadataCandidate(
                provider = this.provider,
                resolverType = ResolverType.REVIEWS,
                fields = mapOf(
                    ResolvedField.REVIEWS to FieldValue(page.reviews, FieldOwner.REVIEWS)
                )
            )
        )
    }

    private fun String.stripImdbPrefix(): String? {
        val value = trim().takeIf { it.isNotBlank() } ?: return null
        val prefix = value.substringBefore(':', missingDelimiterValue = "")
        if (prefix.isEmpty()) return value
        if (!prefix.equals("imdb", ignoreCase = true)) return null
        return value.substringAfter(':').takeIf { it.isNotBlank() }
    }

    private companion object {
        val REVIEW_SHAPES = setOf(
            TraktApiShapes.MOVIE_COMMENTS,
            TraktApiShapes.SHOW_COMMENTS
        )
        const val DEFAULT_PAGE = 1
        const val DEFAULT_LIMIT = 20
    }
}
