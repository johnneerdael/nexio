package com.nexio.tv.core.metadata.router.resolver

import com.nexio.tv.core.metadata.router.MetadataCandidate
import com.nexio.tv.core.metadata.router.ResolvedField
import com.nexio.tv.core.metadata.router.ResolverType
import com.nexio.tv.core.trace.TraceMetadataEvents
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves per-field winners for [ResolvedField.CAST], [ResolvedField.CREW], and
 * [ResolvedField.ORGANIZATION_LIST] candidates.
 *
 * Unlike single-field resolvers (e.g. [TrailerResolver], [RecommendationResolver]) this
 * resolver produces three independent picks (one per field) because cast / crew /
 * organizations may legitimately come from different providers in the same request — for
 * example TMDB person credits + TVDB cast list + a TMDB network detail. Each field is
 * picked in primary-then-secondary order, mirroring [RecommendationResolver]'s
 * first-match-by-priority semantics, and emits its own `metadata.field_selected` event.
 */
@Singleton
class OrganizationPersonResolver @Inject constructor(
    private val traceEvents: TraceMetadataEvents
) {
    val resolverType: ResolverType = ResolverType.ORGANIZATION_PERSON

    data class Resolution(
        val cast: MetadataCandidate?,
        val crew: MetadataCandidate?,
        val organizations: MetadataCandidate?
    )

    fun resolve(
        contentId: String,
        primary: MetadataCandidate?,
        secondary: List<MetadataCandidate>
    ): Resolution {
        val candidates = listOfNotNull(primary) + secondary
        return Resolution(
            cast = pickFirstFor(contentId, candidates, ResolvedField.CAST, "cast"),
            crew = pickFirstFor(contentId, candidates, ResolvedField.CREW, "crew"),
            organizations = pickFirstFor(
                contentId,
                candidates,
                ResolvedField.ORGANIZATION_LIST,
                "organizations"
            )
        )
    }

    private fun pickFirstFor(
        contentId: String,
        candidates: List<MetadataCandidate>,
        field: ResolvedField,
        label: String
    ): MetadataCandidate? {
        val pick = candidates.firstOrNull { candidate ->
            val value = candidate.fields[field]?.value
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
                    "reason" to if (rejection.fields.containsKey(field)) {
                        "lower_priority"
                    } else {
                        "missing_field"
                    }
                )
            }

        traceEvents.emitFieldSelected(
            contentId = contentId,
            field = field.name,
            selectedProvider = pick.provider.name,
            sourceRole = "ORGANIZATION_PERSON",
            valuePreview = label,
            ownershipRule = "organization-person-resolver: $label first-match-by-priority",
            rejectedCandidates = rejected
        )
        return pick
    }
}
