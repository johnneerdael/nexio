package com.nexio.tv.domain.model

import androidx.compose.runtime.Immutable
import com.nexio.tv.core.artwork.ArtworkBundle
import com.nexio.tv.core.metadata.router.MetadataMediaKind
import com.nexio.tv.core.metadata.router.resolver.TrailerPlaybackRef

@Immutable
data class ResolvedDisplayItem(
    val itemKey: String,
    val contentId: String,
    val parentId: String,
    val itemType: ContentType,
    val mediaKind: MetadataMediaKind,
    val canonicalProvider: String?,
    val canonicalId: String?,
    val imdbId: String?,
    val stableIds: ProviderIds,
    val display: ResolvedDisplayFields,
    val artwork: ArtworkBundle,
    val rating: TitleRating?,
    val trailer: TrailerDisplayState,
    val hydrationState: HydrationState,
    val sourceTrace: List<HydratedHomeFieldTrace>,
    val updatedAtMs: Long,
    /**
     * Per-field rank-aware slots. Populated by [HomeRailProjectionReducer]; null
     * when the item was constructed by legacy paths that haven't migrated yet.
     * Consumers must tolerate null and fall back to the flat fields above.
     */
    val slots: ResolvedDisplayFieldSlots? = null
)

@Immutable
data class ResolvedDisplayFields(
    val title: String?,
    val originalTitle: String?,
    val year: Int?,
    val releaseDate: String?,
    val overview: String?,
    val genres: List<String>,
    val runtimeText: String?,
    val tomatoesRating: Double? = null
)

@Immutable
data class TrailerDisplayState(
    val fallbackTrailerYtIds: List<String> = emptyList(),
    val selectedPlaybackRef: TrailerPlaybackRef? = null,
    val availabilityReason: String? = null,
    val surface: String? = null,
    val resolverSource: String? = null,
    val lastResolvedAtMs: Long? = null
)

enum class HydrationState {
    PREVIEW_ONLY,
    IDENTITY_READY,
    HYDRATING,
    CANONICAL_READY,
    FAILED_USING_PREVIEW,
    STALE_READY
}
