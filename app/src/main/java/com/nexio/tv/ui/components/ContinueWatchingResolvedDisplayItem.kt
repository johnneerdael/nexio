package com.nexio.tv.ui.components

import androidx.compose.runtime.Immutable
import com.nexio.tv.core.artwork.ArtworkDisplayRef
import com.nexio.tv.domain.model.ResolvedDisplayItem
import com.nexio.tv.domain.model.TitleRating
import com.nexio.tv.domain.model.WatchProgress
import com.nexio.tv.ui.screens.home.ContinueWatchingItem
import com.nexio.tv.ui.screens.home.NextUpInfo

/**
 * Per-surface projection for Continue Watching rows. Two variants mirror
 * [ContinueWatchingItem]:
 *
 *  - [InProgress]: a resume row backed by [WatchProgress] (positionMs,
 *    durationMs, episode info), display fields drawn from
 *    [ResolvedDisplayItem].
 *  - [NextUp]: a Next-Up row backed by [NextUpInfo] (season/episode
 *    metadata, last-watched timestamp), display fields drawn from
 *    [ResolvedDisplayItem].
 *
 * Per CLAUDE.md rule #1, surfaces consume [ResolvedDisplayItem] (or an
 * approved per-surface projection) rather than raw `MetaPreview`. Typed
 * `posterRef` / `backdropRef` / `logoRef` slots are kept strict (no
 * cross-type fallback).
 */
@Immutable
sealed class ContinueWatchingResolvedDisplayItem {
    abstract val itemKey: String
    abstract val contentId: String
    abstract val title: String?
    abstract val posterRef: ArtworkDisplayRef?
    abstract val backdropRef: ArtworkDisplayRef?
    abstract val logoRef: ArtworkDisplayRef?
    abstract val rating: TitleRating?

    /**
     * Resume row. [source] carries CW-specific fields not on [ResolvedDisplayItem]
     * (episode thumbnail/description, genres, releaseInfo, canonicalKey,
     * streamFetchVideoId).
     */
    @Immutable
    data class InProgress(
        override val itemKey: String,
        override val contentId: String,
        override val title: String?,
        override val posterRef: ArtworkDisplayRef?,
        override val backdropRef: ArtworkDisplayRef?,
        override val logoRef: ArtworkDisplayRef?,
        override val rating: TitleRating?,
        val source: ContinueWatchingItem.InProgress
    ) : ContinueWatchingResolvedDisplayItem() {
        val progress: WatchProgress get() = source.progress
    }

    /** Next-Up row. [source] carries the original [NextUpInfo]. */
    @Immutable
    data class NextUp(
        override val itemKey: String,
        override val contentId: String,
        override val title: String?,
        override val posterRef: ArtworkDisplayRef?,
        override val backdropRef: ArtworkDisplayRef?,
        override val logoRef: ArtworkDisplayRef?,
        override val rating: TitleRating?,
        val source: ContinueWatchingItem.NextUp
    ) : ContinueWatchingResolvedDisplayItem() {
        val info: NextUpInfo get() = source.info
    }

    /**
     * Adapter from the per-surface resolved projection back to the legacy domain
     * [ContinueWatchingItem]. Composables consume the resolved projection for
     * rendering, but ViewModel callbacks still take the legacy type — this returns
     * `source` directly so the boundary stays backward-compatible without leaking
     * the resolved type into VM signatures.
     *
     * TODO(Plan B Surface 4 cleanup): remove once VM callbacks accept
     * [ContinueWatchingResolvedDisplayItem] directly.
     */
    fun toContinueWatchingItem(): ContinueWatchingItem = when (this) {
        is InProgress -> source
        is NextUp -> source
    }

    companion object {
        fun fromInProgress(
            resolved: ResolvedDisplayItem,
            source: ContinueWatchingItem.InProgress
        ): InProgress = InProgress(
            itemKey = resolved.itemKey,
            contentId = resolved.contentId,
            title = resolved.display.title,
            posterRef = resolved.artwork.poster,
            backdropRef = resolved.artwork.backdrop,
            logoRef = resolved.artwork.logo,
            rating = resolved.rating,
            source = source
        )

        fun fromNextUp(
            resolved: ResolvedDisplayItem,
            source: ContinueWatchingItem.NextUp
        ): NextUp = NextUp(
            itemKey = resolved.itemKey,
            contentId = resolved.contentId,
            title = resolved.display.title,
            posterRef = resolved.artwork.poster,
            backdropRef = resolved.artwork.backdrop,
            logoRef = resolved.artwork.logo,
            rating = resolved.rating,
            source = source
        )
    }
}
