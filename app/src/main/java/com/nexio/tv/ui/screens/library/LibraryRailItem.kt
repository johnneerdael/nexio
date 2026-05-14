package com.nexio.tv.ui.screens.library

import androidx.compose.runtime.Immutable
import com.nexio.tv.core.artwork.ArtworkDisplayRef
import com.nexio.tv.core.artwork.ArtworkTrace
import com.nexio.tv.core.artwork.ArtworkType
import com.nexio.tv.core.artwork.toLegacyArtworkString
import com.nexio.tv.domain.model.LibraryEntry
import com.nexio.tv.domain.model.TitleRating
import com.nexio.tv.domain.model.TitleRatingSource
import com.nexio.tv.domain.model.UnifiedWatchlistRowItem
import com.nexio.tv.ui.components.RailCardData

/**
 * Per-surface projection for the library/saved-items grid. Mirrors the
 * `*RailItem.fromX(...)` pattern from
 * [com.nexio.tv.ui.screens.detail.DetailRailItem.fromMetaPreview] (commit
 * `d777c65b3`) and
 * [com.nexio.tv.ui.screens.search.SearchResultItem.fromMetaPreview]
 * (commit `d7ee2b87e`): wraps a [LibraryEntry]'s poster URL string as
 * [ArtworkDisplayRef.LegacyString] of POSTER type so the
 * [com.nexio.tv.ui.components.GridContentCard] surface boundary's rule #1
 * strict-typed-slot contract holds.
 *
 * The [source] entry is retained so library callbacks (`onOpenEntry`,
 * focus tracking, etc.) can keep using the legacy `LibraryEntry` type
 * without forcing those signatures to change in the same migration.
 *
 * Plan B Phase 1B of the home-MetaPreview-elimination spec
 * (`docs/superpowers/specs/2026-05-10-home-metapreview-elimination-design.md`).
 */
@Immutable
internal data class LibraryRailItem(
    val itemKey: String,
    val contentId: String,
    val title: String,
    val rating: TitleRating?,
    override val posterRef: ArtworkDisplayRef?,
    override val posterProviderTag: String?,
    val source: LibraryEntry
) : RailCardData {
    override val id: String get() = contentId
    override val name: String get() = title

    companion object {
        fun fromEntry(entry: LibraryEntry): LibraryRailItem = LibraryRailItem(
            itemKey = "${entry.type}:${entry.id}",
            contentId = entry.id,
            title = entry.name,
            rating = entry.imdbRating?.let { value ->
                TitleRating(value = value.toDouble(), source = TitleRatingSource.IMDB)
            },
            posterRef = entry.poster.toLegacyLibraryRefOrNull(ArtworkType.POSTER),
            posterProviderTag = null,
            source = entry
        )
    }
}

@Immutable
internal data class UnifiedWatchlistLibraryRailItem(
    val itemKey: String,
    val contentId: String,
    val title: String,
    val rating: TitleRating?,
    override val posterRef: ArtworkDisplayRef?,
    override val posterProviderTag: String?,
    val source: LibraryEntry
) : RailCardData {
    override val id: String get() = contentId
    override val name: String get() = title

    companion object {
        fun fromRow(row: UnifiedWatchlistRowItem): UnifiedWatchlistLibraryRailItem {
            val displayItem = row.displayItem
            val display = displayItem.display
            val membership = row.membership
            val title = display.title
                ?: membership.title
                ?: displayItem.contentId
            val entry = LibraryEntry(
                id = displayItem.contentId.ifBlank { membership.authorityKey },
                type = displayItem.itemType.toApiString(membership.contentType.toApiString()),
                name = title,
                poster = displayItem.artwork.poster.toLegacyArtworkString(),
                background = displayItem.artwork.backdrop.toLegacyArtworkString(),
                logo = displayItem.artwork.logo.toLegacyArtworkString(),
                description = display.overview,
                releaseInfo = display.year?.toString() ?: membership.year?.toString(),
                imdbRating = displayItem.rating?.value?.toFloat(),
                genres = display.genres,
                addonBaseUrl = null,
                imdbId = membership.imdbId ?: displayItem.imdbId ?: displayItem.stableIds.imdb,
                tmdbId = membership.tmdbId ?: displayItem.stableIds.tmdb?.toIntOrNull(),
                traktId = membership.traktId ?: displayItem.stableIds.trakt?.toIntOrNull()
            )
            return UnifiedWatchlistLibraryRailItem(
                itemKey = "unified:${membership.authorityKey}",
                contentId = displayItem.contentId,
                title = title,
                rating = displayItem.rating,
                posterRef = displayItem.artwork.poster,
                posterProviderTag = null,
                source = entry
            )
        }
    }
}

private fun String?.toLegacyLibraryRefOrNull(type: ArtworkType): ArtworkDisplayRef? {
    val v = this?.takeIf { it.isNotBlank() } ?: return null
    return ArtworkDisplayRef.LegacyString(value = v, imageType = type, trace = ArtworkTrace.empty())
}
