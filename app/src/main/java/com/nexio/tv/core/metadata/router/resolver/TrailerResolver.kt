package com.nexio.tv.core.metadata.router.resolver

import com.nexio.tv.core.metadata.router.MetadataCandidate
import com.nexio.tv.core.metadata.router.ResolvedField
import com.nexio.tv.core.metadata.router.ResolverType
import com.nexio.tv.core.trace.TraceMetadataEvents
import com.nexio.tv.domain.model.ProviderIds
import javax.inject.Inject
import javax.inject.Singleton

enum class TrailerSurface {
    HOME,
    DETAIL,
    SCREENSAVER
}

data class TrailerResolveRequest(
    val itemKey: String,
    val title: String,
    val year: String? = null,
    val stableIds: ProviderIds = ProviderIds(),
    val fallbackYtIds: List<String> = emptyList(),
    val surface: TrailerSurface,
    val type: String? = null,
    val seasonNumber: Int? = null,
    val contentId: String? = null,
    val providerCandidates: List<TrailerPlaybackRef> = emptyList()
)

data class TrailerAvailability(
    val available: Boolean,
    val reason: String
)

sealed interface TrailerPlaybackRef {
    data class YouTubeId(val videoId: String) : TrailerPlaybackRef
    data class ExternalUrl(val url: String) : TrailerPlaybackRef
    data class InAppSource(
        val videoUrl: String,
        val audioUrl: String? = null,
        val userAgent: String? = null
    ) : TrailerPlaybackRef
    data class ItemLookup(
        val title: String,
        val year: String? = null,
        val stableIds: ProviderIds = ProviderIds(),
        val type: String? = null,
        val seasonNumber: Int? = null,
        val contentId: String? = null,
        val fallbackYtIds: List<String> = emptyList()
    ) : TrailerPlaybackRef
}

data class TrailerResolution(
    val availability: TrailerAvailability,
    val candidates: List<TrailerPlaybackRef>,
    val selected: TrailerPlaybackRef?,
    val trace: List<String>
)

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

    fun resolveTrailer(request: TrailerResolveRequest): TrailerResolution {
        val providerCandidates = request.providerCandidates
            .mapNotNull(::normalizedPlaybackRef)
            .distinct()
        val fallbackCandidates = request.fallbackYtIds
            .mapNotNull { id ->
                id.trim()
                    .takeIf { it.isNotBlank() }
                    ?.let(TrailerPlaybackRef::YouTubeId)
            }
            .distinct()
        val itemLookupCandidates = if (providerCandidates.isEmpty()) {
            listOfNotNull(request.toItemLookupRef())
        } else {
            emptyList()
        }
        val candidates = (providerCandidates + itemLookupCandidates + fallbackCandidates).distinct()
        val selected = candidates.firstOrNull()
        val reason = when {
            selected == null -> "missing_candidates"
            selected in providerCandidates -> "provider_candidate"
            selected in fallbackCandidates -> "fallback_youtube_id"
            selected in itemLookupCandidates -> "item_lookup"
            else -> "selected"
        }

        return TrailerResolution(
            availability = TrailerAvailability(
                available = selected != null,
                reason = reason
            ),
            candidates = candidates,
            selected = selected,
            trace = listOf(
                "surface=${request.surface.name.lowercase()}",
                "reason=$reason",
                "candidate_count=${candidates.size}"
            )
        )
    }

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

    private fun normalizedPlaybackRef(ref: TrailerPlaybackRef): TrailerPlaybackRef? =
        when (ref) {
            is TrailerPlaybackRef.YouTubeId ->
                ref.videoId.trim()
                    .takeIf { it.isNotBlank() }
                    ?.let(TrailerPlaybackRef::YouTubeId)
            is TrailerPlaybackRef.ExternalUrl ->
                ref.url.trim()
                    .takeIf { it.isNotBlank() }
                    ?.let(TrailerPlaybackRef::ExternalUrl)
            is TrailerPlaybackRef.InAppSource -> {
                val videoUrl = ref.videoUrl.trim().takeIf { it.isNotBlank() } ?: return null
                TrailerPlaybackRef.InAppSource(
                    videoUrl = videoUrl,
                    audioUrl = ref.audioUrl?.trim()?.takeIf { it.isNotBlank() },
                    userAgent = ref.userAgent?.trim()?.takeIf { it.isNotBlank() }
                )
            }
            is TrailerPlaybackRef.ItemLookup -> {
                val title = ref.title.trim().takeIf { it.isNotBlank() } ?: return null
                TrailerPlaybackRef.ItemLookup(
                    title = title,
                    year = ref.year?.trim()?.takeIf { it.isNotBlank() },
                    stableIds = ref.stableIds,
                    type = ref.type?.trim()?.takeIf { it.isNotBlank() },
                    seasonNumber = ref.seasonNumber,
                    contentId = ref.contentId?.trim()?.takeIf { it.isNotBlank() },
                    fallbackYtIds = ref.fallbackYtIds.mapNotNull { id ->
                        id.trim().takeIf { it.isNotBlank() }
                    }
                )
            }
        }

    private fun TrailerResolveRequest.toItemLookupRef(): TrailerPlaybackRef.ItemLookup? {
        if (surface != TrailerSurface.SCREENSAVER) return null
        val hasStableId = listOf(
            stableIds.tvdb,
            stableIds.tmdb,
            stableIds.imdb,
            stableIds.kitsu
        ).any { !it.isNullOrBlank() }
        val normalizedContentId = contentId?.trim()?.takeIf { it.isNotBlank() }
        if (!hasStableId && normalizedContentId == null) return null
        return TrailerPlaybackRef.ItemLookup(
            title = title,
            year = year,
            stableIds = stableIds,
            type = type,
            seasonNumber = seasonNumber,
            contentId = normalizedContentId ?: itemKey,
            fallbackYtIds = fallbackYtIds
        )
    }
}
