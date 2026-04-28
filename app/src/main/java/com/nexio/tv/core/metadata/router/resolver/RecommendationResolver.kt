package com.nexio.tv.core.metadata.router.resolver

import com.nexio.tv.core.metadata.router.MetadataCandidate
import com.nexio.tv.core.metadata.router.ResolvedField
import com.nexio.tv.core.metadata.router.ResolverType
import com.nexio.tv.core.trace.TraceMetadataEvents
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Picks the first candidate (in primary-then-secondary order) that carries a non-empty
 * RECOMMENDATIONS field.
 *
 * Recommendations are NOT aggregated across providers (unlike [ReviewResolver]) — TMDB's
 * "more like this" is the canonical source; secondary providers only contribute if TMDB is
 * missing. Pure function — no IO.
 */
@Singleton
class RecommendationResolver @Inject constructor(
    private val traceEvents: TraceMetadataEvents
) {
    val resolverType: ResolverType = ResolverType.RECOMMENDATIONS

    fun resolve(
        contentId: String,
        primary: MetadataCandidate?,
        secondary: List<MetadataCandidate>
    ): MetadataCandidate? {
        val candidates = listOfNotNull(primary) + secondary
        val pick = candidates.firstOrNull { candidate ->
            val value = candidate.fields[ResolvedField.RECOMMENDATIONS]?.value
            when (value) {
                null -> false
                is Collection<*> -> value.isNotEmpty()
                else -> true
            }
        } ?: return null

        val rejected = candidates
            .filter { it !== pick }
            .map { rejection ->
                mapOf(
                    "provider" to rejection.provider.name,
                    "reason" to if (rejection.fields.containsKey(ResolvedField.RECOMMENDATIONS)) {
                        "lower_priority"
                    } else {
                        "missing_field"
                    }
                )
            }

        traceEvents.emitFieldSelected(
            contentId = contentId,
            field = ResolvedField.RECOMMENDATIONS.name,
            selectedProvider = pick.provider.name,
            sourceRole = "RECOMMENDATIONS",
            valuePreview = "recommendations",
            ownershipRule = "recommendation-resolver: first-match-by-priority",
            rejectedCandidates = rejected
        )
        return pick
    }
}
