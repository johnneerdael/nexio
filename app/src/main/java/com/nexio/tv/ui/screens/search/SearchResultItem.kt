package com.nexio.tv.ui.screens.search

import androidx.compose.runtime.Immutable
import com.nexio.tv.core.artwork.ArtworkDisplayRef
import com.nexio.tv.core.artwork.ArtworkTrace
import com.nexio.tv.core.artwork.ArtworkType
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.TitleRating
import com.nexio.tv.domain.model.TitleRatingSource
import com.nexio.tv.ui.components.RailCardData

/**
 * Per-surface projection for search/discover results. Mirrors
 * [com.nexio.tv.ui.screens.detail.DetailRailItem.fromMetaPreview] (commit
 * `d777c65b3`): wraps a search-result `MetaPreview`'s poster URL string as
 * [ArtworkDisplayRef.LegacyString] of POSTER type so the
 * [com.nexio.tv.ui.components.GridContentCard] surface boundary's rule #1
 * strict-typed-slot contract holds.
 *
 * The original [source] is retained so search-specific callbacks
 * (`onItemClick`, `onItemLongPress`, etc.) can pass the legacy `MetaPreview`
 * to the navigation / library actions without forcing those signatures to
 * change in the same migration.
 *
 * Plan B Phase 1A of the home-MetaPreview-elimination spec
 * (`docs/superpowers/specs/2026-05-10-home-metapreview-elimination-design.md`).
 */
@Immutable
internal data class SearchResultItem(
    val itemKey: String,
    val contentId: String,
    val title: String,
    val rating: TitleRating?,
    override val posterRef: ArtworkDisplayRef?,
    override val posterProviderTag: String?,
    val source: MetaPreview
) : RailCardData {
    override val id: String get() = contentId
    override val name: String get() = title

    companion object {
        fun fromMetaPreview(meta: MetaPreview): SearchResultItem = SearchResultItem(
            itemKey = "${meta.apiType}:${meta.id}",
            contentId = meta.id,
            title = meta.name,
            rating = meta.imdbRating?.let { value ->
                TitleRating(value = value.toDouble(), source = meta.ratingSource ?: TitleRatingSource.IMDB)
            },
            posterRef = meta.poster.toLegacySearchResultRefOrNull(ArtworkType.POSTER),
            posterProviderTag = meta.posterProviderTag,
            source = meta
        )
    }
}

private fun String?.toLegacySearchResultRefOrNull(type: ArtworkType): ArtworkDisplayRef? {
    val v = this?.takeIf { it.isNotBlank() } ?: return null
    return ArtworkDisplayRef.LegacyString(value = v, imageType = type, trace = ArtworkTrace.empty())
}
