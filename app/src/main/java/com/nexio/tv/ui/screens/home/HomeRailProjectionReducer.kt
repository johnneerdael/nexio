package com.nexio.tv.ui.screens.home

import com.nexio.tv.core.artwork.ArtworkDisplayRef
import com.nexio.tv.domain.model.ResolvedDisplayFieldSlots
import com.nexio.tv.domain.model.ResolvedSlot
import com.nexio.tv.domain.model.TitleRating

/**
 * Pure rank-aware slot picker. For every field in [ResolvedDisplayFieldSlots],
 * picks the highest-ranked non-null candidate among (firstPaint, overlay,
 * existing, profile). A higher rank ALWAYS beats a lower rank, even when the
 * higher-rank slot's value is null — null at RESOLVED means "the authoritative
 * source explicitly produced no value", which must not be papered over by
 * FIRST_PAINT.
 *
 * **Where this is invoked from:**
 *
 * - [com.nexio.tv.data.repository.ResolvedDisplaySurfaceRepository] — the
 *   load-bearing **non-downgrade enforcement point**. Every per-itemKey
 *   transition published into the typed authority routes through here with
 *   `existing = previously-published slots`. This is what guarantees the
 *   "RESOLVED never downgrades to FIRST_PAINT" contract end-to-end (see
 *   `applyNonDowngradeMerge` / `applyNonDowngradeMergeForReplace`).
 *
 * - [HomeResolvedDisplayMapper.toResolvedDisplayItem] — combines a single
 *   CatalogRow item's `firstPaint` slots with its overlay slots. Passes
 *   `existing = null` because the mapper has no access to the repository's
 *   current state. The non-downgrade contract is enforced downstream at the
 *   boundary, not here.
 *
 * - Non-home surface merges in `HomeCatalogRefreshCoordinator`,
 *   `HomeViewModelContinueWatching`, and `ContinueWatchingMetadataSnapshot`.
 *
 * Previously documented as "the ONLY place that may merge display inputs" —
 * that was aspirational, never true. The boundary is the enforcement site;
 * this reducer is its rank-picker utility.
 */
internal object HomeRailProjectionReducer {
    fun reduce(
        firstPaint: ResolvedDisplayFieldSlots,
        overlay: ResolvedDisplayFieldSlots?,
        existing: ResolvedDisplayFieldSlots?,
        profile: ResolvedDisplayFieldSlots?
    ): ResolvedDisplayFieldSlots = ResolvedDisplayFieldSlots(
        title = pickHigherRanked(firstPaint.title, overlay?.title, existing?.title, profile?.title),
        originalTitle = pickHigherRanked(firstPaint.originalTitle, overlay?.originalTitle, existing?.originalTitle, profile?.originalTitle),
        overview = pickHigherRanked(firstPaint.overview, overlay?.overview, existing?.overview, profile?.overview),
        genres = pickHigherRanked(firstPaint.genres, overlay?.genres, existing?.genres, profile?.genres),
        releaseInfo = pickHigherRanked(firstPaint.releaseInfo, overlay?.releaseInfo, existing?.releaseInfo, profile?.releaseInfo),
        runtime = pickHigherRanked(firstPaint.runtime, overlay?.runtime, existing?.runtime, profile?.runtime),
        rating = pickHigherRanked(firstPaint.rating, overlay?.rating, existing?.rating, profile?.rating),
        poster = pickHigherRanked(firstPaint.poster, overlay?.poster, existing?.poster, profile?.poster),
        backdrop = pickHigherRanked(firstPaint.backdrop, overlay?.backdrop, existing?.backdrop, profile?.backdrop),
        logo = pickHigherRanked(firstPaint.logo, overlay?.logo, existing?.logo, profile?.logo),
        thumbnail = pickHigherRanked(firstPaint.thumbnail, overlay?.thumbnail, existing?.thumbnail, profile?.thumbnail),
        posterProviderTag = pickHigherRanked(firstPaint.posterProviderTag, overlay?.posterProviderTag, existing?.posterProviderTag, profile?.posterProviderTag)
    )

    /**
     * Allocation-free 4-argument higher-ranked-slot picker.
     *
     * Replaces the previous `vararg + filterNotNull + reduce { chooseHigherRank }`
     * pattern, which allocated per call: an Array<ResolvedSlot<T>?> for the vararg
     * backing, an ArrayList from filterNotNull, and an ArrayList iterator for
     * reduce (rule #4 — Iterable.forEach/reduce in a hot path; rule #5 — every
     * reducer call produced a fresh-but-content-equal output bag because the slot
     * lookups churned identity-stable inputs through reference-fresh wrappers).
     *
     * 2026-05-10 ANR investigation showed the reducer is invoked at ~2,860
     * chooseX calls per home pipeline emission (22 rows × ~10 items × 13
     * fields). Eliminating the vararg + ArrayList + iterator triplet drops
     * ~8,580 allocations per emission to zero. Tie semantics preserved
     * (first non-null with the highest rank wins on tie, matching
     * Iterable.reduce's left-fold over chooseHigherRank).
     */
    private fun <T> pickHigherRanked(
        s1: ResolvedSlot<T>?,
        s2: ResolvedSlot<T>?,
        s3: ResolvedSlot<T>?,
        s4: ResolvedSlot<T>?
    ): ResolvedSlot<T> {
        var best: ResolvedSlot<T>? = s1
        if (s2 != null && (best == null || s2.rank.ordinal > best.rank.ordinal)) best = s2
        if (s3 != null && (best == null || s3.rank.ordinal > best.rank.ordinal)) best = s3
        if (s4 != null && (best == null || s4.rank.ordinal > best.rank.ordinal)) best = s4
        return best ?: error("HomeRailProjectionReducer.pickHigherRanked called with all slots null — firstPaint must always be non-null")
    }
}
