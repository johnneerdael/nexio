package com.nexio.tv.ui.screens.detail

import androidx.compose.runtime.Immutable
import com.nexio.tv.core.artwork.ArtworkDisplayRef
import com.nexio.tv.core.artwork.ArtworkTrace
import com.nexio.tv.core.artwork.ArtworkType
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.TitleRating
import com.nexio.tv.domain.model.TitleRatingSource
import com.nexio.tv.domain.model.homeDisplayItemKey

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
 */
@Immutable
data class DetailRailItem(
    val itemKey: String,
    val contentId: String,
    val title: String,
    val year: Int?,
    val posterRef: ArtworkDisplayRef?,
    val backdropRef: ArtworkDisplayRef?,
    val logoRef: ArtworkDisplayRef?,
    val rating: TitleRating?,
    val source: MetaPreview
) {
    companion object {
        fun fromMetaPreview(meta: MetaPreview): DetailRailItem = DetailRailItem(
            itemKey = homeDisplayItemKey(meta.apiType, meta.id),
            contentId = meta.id,
            title = meta.name,
            year = meta.releaseInfo?.split("-")?.firstOrNull()?.toIntOrNull(),
            posterRef = meta.poster.toLegacyRailRefOrNull(ArtworkType.POSTER),
            backdropRef = meta.background.toLegacyRailRefOrNull(ArtworkType.BACKDROP),
            logoRef = meta.logo.toLegacyRailRefOrNull(ArtworkType.LOGO),
            rating = meta.imdbRating?.let { value ->
                TitleRating(value = value.toDouble(), source = meta.ratingSource ?: TitleRatingSource.IMDB)
            },
            source = meta
        )
    }
}

private fun String?.toLegacyRailRefOrNull(type: ArtworkType): ArtworkDisplayRef? {
    val v = this?.takeIf { it.isNotBlank() } ?: return null
    return ArtworkDisplayRef.LegacyString(value = v, imageType = type, trace = ArtworkTrace.empty())
}
