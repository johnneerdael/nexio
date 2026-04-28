package com.nexio.tv.core.metadata.router.resolver

import com.nexio.tv.core.metadata.router.FieldOwner
import com.nexio.tv.core.metadata.router.FieldValue
import com.nexio.tv.core.metadata.router.MetadataCandidate
import com.nexio.tv.core.metadata.router.ResolvedField
import com.nexio.tv.core.metadata.router.ResolverType
import com.nexio.tv.core.trace.TraceMetadataEvents
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Aggregates review candidates from all participating providers (TMDB + Trakt + ...).
 *
 * Unlike [TrailerResolver] (first-match), reviews from multiple providers can coexist:
 * a TMDB reviews list and a Trakt comments list both have value to the user.
 * The merged candidate carries reviews concatenated from every contributor.
 */
@Singleton
class ReviewResolver @Inject constructor(
    private val traceEvents: TraceMetadataEvents
) {
    val resolverType: ResolverType = ResolverType.REVIEWS

    fun resolve(
        contentId: String,
        primary: MetadataCandidate?,
        secondary: List<MetadataCandidate>
    ): MetadataCandidate? {
        val candidates = listOfNotNull(primary) + secondary
        val withReviews = candidates.filter { candidate ->
            val value = candidate.fields[ResolvedField.REVIEWS]?.value
            when (value) {
                null -> false
                is Collection<*> -> value.isNotEmpty()
                else -> true
            }
        }
        if (withReviews.isEmpty()) return null

        val carrier = withReviews.first()
        val merged = mutableListOf<Any?>()
        for (candidate in withReviews) {
            when (val value = candidate.fields[ResolvedField.REVIEWS]?.value) {
                is Collection<*> -> merged.addAll(value)
                null -> Unit
                else -> merged.add(value)
            }
        }

        val rejected = candidates
            .filter { it !in withReviews }
            .map { rejection ->
                mapOf(
                    "provider" to rejection.provider.name,
                    "reason" to "missing_field"
                )
            }

        traceEvents.emitFieldSelected(
            contentId = contentId,
            field = ResolvedField.REVIEWS.name,
            selectedProvider = withReviews.joinToString(",") { it.provider.name },
            sourceRole = "aggregate",
            valuePreview = "reviews-aggregated:${merged.size}",
            ownershipRule = "review-resolver: aggregate-all",
            rejectedCandidates = rejected
        )

        return carrier.copy(
            fields = carrier.fields + (
                ResolvedField.REVIEWS to FieldValue(merged.toList(), FieldOwner.REVIEWS)
            )
        )
    }
}
