package com.nexio.tv.core.metadata.router

import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.ProviderId
import com.nexio.tv.domain.model.ProviderIds

data class StableIdBundle(
    val itemKey: String,
    val itemType: ContentType,
    val canonical: CanonicalStableIds,
    val sidecars: SidecarStableIds,
    val source: SourceStableIds,
    val evidence: List<StableIdEvidence>,
    val resolvedAtMs: Long
) {
    val status: StableIdBundleStatus = resolveStableIdBundleStatus(canonical, sidecars)
}

data class CanonicalStableIds(
    val tmdbMovieId: String? = null,
    val tvdbSeriesId: String? = null,
    val kitsuAnimeId: String? = null
) {
    fun providerNativeIdFor(provider: MetadataPrimaryProvider): String? =
        when (provider) {
            MetadataPrimaryProvider.TMDB -> tmdbMovieId
            MetadataPrimaryProvider.TVDB -> tvdbSeriesId
            MetadataPrimaryProvider.KITSU -> kitsuAnimeId
            MetadataPrimaryProvider.IMDB,
            MetadataPrimaryProvider.TRAKT,
            MetadataPrimaryProvider.SIMKL,
            MetadataPrimaryProvider.RPDB,
            MetadataPrimaryProvider.TOP_POSTERS -> null
        }
}

data class SidecarStableIds(
    val imdbId: String? = null,
    val malId: String? = null,
    val anilistId: String? = null,
    val anidbId: String? = null
)

data class SourceStableIds(
    val sourceProvider: ProviderId?,
    val sourceItemId: String?,
    val railId: String?,
    val observedIds: ProviderIds
) {
    val hasTrackingSourceFacts: Boolean = !observedIds.trakt.isNullOrBlank() || !observedIds.simkl.isNullOrBlank()
    val promotesTrackingProviders: Boolean = false
}

data class StableIdEvidence(
    val source: String,
    val target: String,
    val networkExecuted: Boolean,
    val resultId: String? = null
)

enum class StableIdBundleStatus {
    PREVIEW_IDS_ONLY,
    CANONICAL_READY,
    CANONICAL_AND_RATING_READY,
    CANONICAL_READY_RATING_UNRESOLVED,
    UNRESOLVED,
    NEGATIVE_CACHED
}

enum class StableIdResolutionTrigger {
    VISIBLE_HOME_HYDRATION,
    FOCUSED_HOME_ITEM,
    DETAIL_OPEN,
    CONTINUE_WATCHING,
    PLAYER_START
}

fun ProviderIds.toSourceStableIds(
    sourceProvider: ProviderId?,
    sourceItemId: String?,
    railId: String?
): SourceStableIds =
    SourceStableIds(
        sourceProvider = sourceProvider,
        sourceItemId = sourceItemId,
        railId = railId,
        observedIds = this
    )

private fun resolveStableIdBundleStatus(
    canonical: CanonicalStableIds,
    sidecars: SidecarStableIds
): StableIdBundleStatus {
    val hasCanonical = canonical.tmdbMovieId != null || canonical.tvdbSeriesId != null || canonical.kitsuAnimeId != null
    if (!hasCanonical) return StableIdBundleStatus.UNRESOLVED
    return if (sidecars.imdbId != null) {
        StableIdBundleStatus.CANONICAL_AND_RATING_READY
    } else {
        StableIdBundleStatus.CANONICAL_READY_RATING_UNRESOLVED
    }
}
