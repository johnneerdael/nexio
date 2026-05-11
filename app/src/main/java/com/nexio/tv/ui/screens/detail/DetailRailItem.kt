package com.nexio.tv.ui.screens.detail

import androidx.compose.runtime.Immutable
import com.nexio.tv.core.artwork.ArtworkDisplayRef
import com.nexio.tv.core.artwork.ArtworkTrace
import com.nexio.tv.core.artwork.ArtworkType
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.ResolvedDisplayItem
import com.nexio.tv.domain.model.TitleRating
import com.nexio.tv.domain.model.TitleRatingSource
import com.nexio.tv.domain.model.homeDisplayItemKey
import com.nexio.tv.ui.components.RailCardData

/**
 * Per-surface projection for detail-screen rails (MoreLikeThis, Collection).
 *
 * The data flowing into these rails comes from
 * [com.nexio.tv.data.repository.MetadataDisplayRepository.resolveDetailDisplay] —
 * already through the resolved-display authority — but the doc emits
 * `recommendations: List<MetaPreview>` / `collection: List<MetaPreview>` rather
 * than `List<ResolvedDisplayItem>`. This projection wraps each MetaPreview as a
 * typed surface contract: `posterRef`/`backdropRef`/`logoRef` are
 * [ArtworkDisplayRef.LegacyString] (typed strict-no-fallback per CLAUDE.md
 * rule #1), and the original [source] is retained for downstream consumers
 * (callbacks, navigation, the GridContentCard render bridge).
 *
 * Plan B Surface 5 Task 24. Mirrors
 * [com.nexio.tv.ui.components.ContinueWatchingResolvedDisplayItem.fromInProgressLegacy].
 *
 * Implements [RailCardData] (Phase 0 Task 4 of the home-MetaPreview-elimination
 * spec) so rail composables can consume this projection through the typed
 * [RailCardData] contract instead of reading the legacy [source] MetaPreview.
 */
@Immutable
data class DetailRailItem(
    val itemKey: String,
    val contentId: String,
    val apiType: String,
    val title: String,
    val year: Int?,
    override val posterRef: ArtworkDisplayRef?,
    val backdropRef: ArtworkDisplayRef?,
    val logoRef: ArtworkDisplayRef?,
    val rating: TitleRating?,
    /**
     * Legacy MetaPreview source — non-null when constructed via
     * [fromMetaPreview], null when constructed via [fromResolved]. Consumers
     * should prefer [contentId] + [apiType] for navigation and the typed
     * artwork refs for rendering. Phase 4 retires the remaining MetaPreview-
     * in-callback patterns; this field can then drop entirely.
     */
    val source: MetaPreview?,
    override val posterProviderTag: String?
) : RailCardData {
    override val id: String get() = contentId
    override val name: String? get() = title

    companion object {
        fun fromMetaPreview(meta: MetaPreview): DetailRailItem = DetailRailItem(
            itemKey = homeDisplayItemKey(meta.apiType, meta.id),
            contentId = meta.id,
            apiType = meta.apiType,
            title = meta.name,
            year = meta.releaseInfo?.split("-")?.firstOrNull()?.toIntOrNull(),
            posterRef = meta.poster.toLegacyRailRefOrNull(ArtworkType.POSTER),
            backdropRef = meta.background.toLegacyRailRefOrNull(ArtworkType.BACKDROP),
            logoRef = meta.logo.toLegacyRailRefOrNull(ArtworkType.LOGO),
            rating = meta.imdbRating?.let { value ->
                TitleRating(value = value.toDouble(), source = meta.ratingSource ?: TitleRatingSource.IMDB)
            },
            source = meta,
            posterProviderTag = meta.posterProviderTag
        )

        /**
         * Plan B Surface 5 Task 6 — typed-authority constructor. Builds a
         * DetailRailItem directly from a [ResolvedDisplayItem] without going
         * through MetaPreview. `source` is null because the typed authority
         * doesn't carry the legacy shape; consumers must use [contentId] +
         * [apiType] for navigation routing.
         *
         * Currently unused by MetaDetailsViewModel — the
         * MetadataDisplayRepository.resolveDetailDisplay path still emits
         * `List<MetaPreview>` for recommendations/collection. Once that
         * repository upgrades to emit typed items, switch callers here.
         */
        fun fromResolved(item: ResolvedDisplayItem): DetailRailItem = DetailRailItem(
            itemKey = item.itemKey,
            contentId = item.contentId,
            apiType = item.mediaKind.name.lowercase(),
            title = item.display.title.orEmpty(),
            year = item.display.year,
            posterRef = item.artwork.poster,
            backdropRef = item.artwork.backdrop,
            logoRef = item.artwork.logo,
            rating = item.rating,
            source = null,
            posterProviderTag = null
        )
    }
}

private fun String?.toLegacyRailRefOrNull(type: ArtworkType): ArtworkDisplayRef? {
    val v = this?.takeIf { it.isNotBlank() } ?: return null
    return ArtworkDisplayRef.LegacyString(value = v, imageType = type, trace = ArtworkTrace.empty())
}
