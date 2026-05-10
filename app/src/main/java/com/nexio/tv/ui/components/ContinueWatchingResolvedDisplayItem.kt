package com.nexio.tv.ui.components

import androidx.compose.runtime.Immutable
import com.nexio.tv.core.artwork.ArtworkDisplayRef
import com.nexio.tv.data.repository.ContinueWatchingRecord
import com.nexio.tv.domain.model.ResolvedDisplayItem
import com.nexio.tv.domain.model.TitleRating

/**
 * Continue-Watching-row-specific projection of [ResolvedDisplayItem].
 *
 * Combines [ContinueWatchingRecord] (resume identity, progress, last-played
 * timing) with [ResolvedDisplayItem] (display fields, artwork) so CW cards
 * display authoritative artwork and metadata while preserving CW-specific
 * progress UI.
 *
 * Per project rule #1, surfaces consume [ResolvedDisplayItem] (or an approved
 * per-surface projection) rather than raw `MetaPreview`. CW is a per-surface
 * projection: typed `posterRef` / `backdropRef` / `logoRef` are kept strict
 * (no cross-type fallback) — see the sibling `ModernHomeRowItem` for the same
 * convention.
 */
@Immutable
data class ContinueWatchingResolvedDisplayItem(
    val itemKey: String,
    val contentId: String,
    val title: String?,
    val posterRef: ArtworkDisplayRef?,
    val backdropRef: ArtworkDisplayRef?,
    val logoRef: ArtworkDisplayRef?,
    val rating: TitleRating?,
    val record: ContinueWatchingRecord
) {
    /** Last-played playback position, in milliseconds. Reads from [record]. */
    val positionMs: Long
        get() = record.positionMs

    /** Total content duration, in milliseconds. Reads from [record]. */
    val durationMs: Long
        get() = record.durationMs

    /** Last-played timestamp (epoch ms), backed by [ContinueWatchingRecord.updatedAt]. */
    val lastPlayedAtMs: Long
        get() = record.updatedAt

    companion object {
        fun from(
            resolved: ResolvedDisplayItem,
            record: ContinueWatchingRecord
        ): ContinueWatchingResolvedDisplayItem =
            ContinueWatchingResolvedDisplayItem(
                itemKey = resolved.itemKey,
                contentId = resolved.contentId,
                title = resolved.display.title,
                posterRef = resolved.artwork.poster,
                backdropRef = resolved.artwork.backdrop,
                logoRef = resolved.artwork.logo,
                rating = resolved.rating,
                record = record
            )
    }
}
