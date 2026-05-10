package com.nexio.tv.ui.components

import androidx.compose.runtime.Immutable
import com.nexio.tv.core.artwork.ArtworkDisplayRef
import com.nexio.tv.core.artwork.ArtworkTrace
import com.nexio.tv.core.artwork.ArtworkType
import com.nexio.tv.domain.model.MetaPreview

/**
 * Minimum typed contract that [GridContentCard] consumes. Each surface that
 * renders rail cards provides a typed projection that implements this
 * interface — `ModernHomeRowItem` (home rails / SeeAll / GridHome),
 * `DetailRailItem` (MoreLikeThis / Collection), and per-surface projections
 * to be added by Phases 1A-G of the spec at
 * `docs/superpowers/specs/2026-05-10-home-metapreview-elimination-design.md`.
 *
 * The legacy adapter [toRailCardData] wraps a [MetaPreview] for surfaces that
 * have not migrated to a typed projection yet — it preserves the existing
 * "typed slot first, legacy String second" image-resolution chain inside the
 * single [posterRef] field, so [GridContentCard] only needs the one read path.
 *
 * Per CLAUDE.md hard rule #1, surfaces consume `ResolvedDisplayItem` (or an
 * approved per-surface projection like `ModernHomeRowItem`), never raw
 * `MetaPreview`. Typed `posterRef` is strict POSTER type — no cross-type
 * fallback to backdrop or logo.
 */
@Immutable
interface RailCardData {
    /** Stable content identifier — used for cache keys and focus tracking. */
    val id: String

    /** Display name. May be null when upstream metadata is missing; card
     *  renders an empty label in that case. */
    val name: String?

    /** Typed POSTER artwork slot. Strict POSTER type per rule #1 — never a
     *  backdrop or logo masquerading as a poster. May be null when no
     *  artwork is available; card renders the placeholder in that case. */
    val posterRef: ArtworkDisplayRef?

    /** Optional provider tag for disk-cache differentiation (e.g. distinguishes
     *  TVDB poster from TMDB poster for the same content id). May be null;
     *  card omits the provider component from the disk cache key. */
    val posterProviderTag: String?
}

/**
 * Legacy adapter for surfaces still passing `MetaPreview` directly. Wraps the
 * meta as a `RailCardData` view so [GridContentCard] keeps working without
 * forcing every caller to migrate to a typed projection in the same change.
 *
 * Composes the existing `artwork?.poster ?: legacy String` chain into a single
 * [RailCardData.posterRef] — when the typed `artwork.poster` is set, it wins;
 * otherwise the legacy String becomes a [ArtworkDisplayRef.LegacyString] of
 * POSTER type.
 *
 * This adapter is intended to be removed once every caller has its own typed
 * projection (Phases 1A-G complete).
 */
fun MetaPreview.toRailCardData(): RailCardData = MetaPreviewRailCardAdapter(this)

private class MetaPreviewRailCardAdapter(private val meta: MetaPreview) : RailCardData {
    override val id: String get() = meta.id
    override val name: String get() = meta.name
    override val posterRef: ArtworkDisplayRef? = computePosterRef(meta)
    override val posterProviderTag: String? get() = meta.posterProviderTag
}

private fun computePosterRef(meta: MetaPreview): ArtworkDisplayRef? {
    val typed = meta.artwork?.poster
    if (typed != null) return typed
    val legacy = meta.poster?.takeIf { it.isNotBlank() }
        ?: meta.displayPoster?.takeIf { it.isNotBlank() }
        ?: return null
    return ArtworkDisplayRef.LegacyString(
        value = legacy,
        imageType = ArtworkType.POSTER,
        trace = ArtworkTrace.empty()
    )
}
