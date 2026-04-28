package com.nexio.tv.core.metadata.router.resolver

import com.nexio.tv.core.metadata.router.MetadataCandidate
import com.nexio.tv.core.metadata.router.ResolvedField
import com.nexio.tv.core.metadata.router.ResolverType
import com.nexio.tv.core.trace.TraceMetadataEvents
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Selects the canonical trailer field winner across TVDB / TMDB / addon candidates.
 *
 * Priority: first candidate (in primary-then-secondary order) that carries a non-empty
 * TRAILERS field. Pure function — no IO. Network candidates must be supplied by caller
 * (facade dispatch lands in a follow-up task).
 */
@Singleton
class TrailerResolver @Inject constructor(
    private val traceEvents: TraceMetadataEvents
) {
    val resolverType: ResolverType = ResolverType.TRAILERS

    fun resolve(
        contentId: String,
        primary: MetadataCandidate?,
        secondary: List<MetadataCandidate>
    ): MetadataCandidate? {
        val candidates = listOfNotNull(primary) + secondary
        val pick = candidates.firstOrNull { candidate ->
            val value = candidate.fields[ResolvedField.TRAILERS]?.value
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
                    "reason" to if (rejection.fields.containsKey(ResolvedField.TRAILERS)) {
                        "lower_priority"
                    } else {
                        "missing_field"
                    }
                )
            }

        traceEvents.emitFieldSelected(
            contentId = contentId,
            field = ResolvedField.TRAILERS.name,
            selectedProvider = pick.provider.name,
            sourceRole = "TRAILERS",
            valuePreview = "trailers",
            ownershipRule = "trailer-resolver: first-match-by-priority",
            rejectedCandidates = rejected
        )
        return pick
    }
}
