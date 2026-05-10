package com.nexio.tv.ui.screens.detail

import androidx.compose.runtime.Immutable
import com.nexio.tv.core.artwork.ArtworkDisplayRef
import com.nexio.tv.domain.model.ResolvedDisplayItem
import com.nexio.tv.domain.model.TitleRating

/**
 * Per-surface projection for the detail screen header / hero. Projects
 * [ResolvedDisplayItem] into the field shape the detail composables consume
 * (title, overview, genres, year, runtime, typed artwork refs, rating).
 *
 * Same pattern as [com.nexio.tv.ui.screens.home.ModernHomeRowItem],
 * [com.nexio.tv.ui.screens.home.HeroDisplayItem],
 * [com.nexio.tv.ui.screensaver.IdleScreensaverDisplayItem],
 * [com.nexio.tv.ui.components.ContinueWatchingResolvedDisplayItem].
 *
 * Per CLAUDE.md rule #1, surfaces consume [ResolvedDisplayItem] (or an
 * approved per-surface projection) rather than raw `MetaPreview`. Typed
 * `posterRef` / `backdropRef` / `logoRef` slots are kept strict — no
 * cross-type fallback (e.g. `poster ?: backdrop`).
 *
 * Plan B Surface 5 Task 21. Type only; consumers (Tasks 22-25) wire
 * [MetaDetailsViewModel] and [MetaDetailsScreen] on top.
 */
@Immutable
internal data class MetaDetailsResolvedFields(
    val title: String?,
    val overview: String?,
    val genres: List<String>,
    val year: Int?,
    val runtimeText: String?,
    val posterRef: ArtworkDisplayRef?,
    val backdropRef: ArtworkDisplayRef?,
    val logoRef: ArtworkDisplayRef?,
    val rating: TitleRating?
) {
    companion object {
        fun from(resolved: ResolvedDisplayItem): MetaDetailsResolvedFields =
            MetaDetailsResolvedFields(
                title = resolved.display.title,
                overview = resolved.display.overview,
                genres = resolved.display.genres,
                year = resolved.display.year,
                runtimeText = resolved.display.runtimeText,
                posterRef = resolved.artwork.poster,
                backdropRef = resolved.artwork.backdrop,
                logoRef = resolved.artwork.logo,
                rating = resolved.rating
            )
    }
}
