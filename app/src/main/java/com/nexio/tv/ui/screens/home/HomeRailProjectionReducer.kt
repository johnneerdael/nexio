package com.nexio.tv.ui.screens.home

import com.nexio.tv.core.artwork.ArtworkDisplayRef
import com.nexio.tv.domain.model.ResolvedDisplayFieldSlots
import com.nexio.tv.domain.model.ResolvedSlot
import com.nexio.tv.domain.model.TitleRating

/**
 * The single non-downgrade reducer for Home rail projection. Implements the
 * spec's `choose()` rule: for every field, the highest-ranked slot among
 * (firstPaint, overlay, existing, profile) wins. A higher rank ALWAYS beats a
 * lower rank, even when the higher-rank slot's value is null — null at RESOLVED
 * means "the authoritative source explicitly produced no value", which must
 * not be papered over by FIRST_PAINT.
 *
 * This is the ONLY place that may merge multiple display inputs into final
 * row state. Every other site (apply seam, refresh coordinator, hydration
 * mapper) must funnel through here.
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
