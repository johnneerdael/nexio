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
        title = chooseString(firstPaint.title, overlay?.title, existing?.title, profile?.title),
        originalTitle = chooseString(firstPaint.originalTitle, overlay?.originalTitle, existing?.originalTitle, profile?.originalTitle),
        overview = chooseString(firstPaint.overview, overlay?.overview, existing?.overview, profile?.overview),
        genres = chooseList(firstPaint.genres, overlay?.genres, existing?.genres, profile?.genres),
        releaseInfo = chooseString(firstPaint.releaseInfo, overlay?.releaseInfo, existing?.releaseInfo, profile?.releaseInfo),
        runtime = chooseString(firstPaint.runtime, overlay?.runtime, existing?.runtime, profile?.runtime),
        rating = chooseRating(firstPaint.rating, overlay?.rating, existing?.rating, profile?.rating),
        poster = chooseArtwork(firstPaint.poster, overlay?.poster, existing?.poster, profile?.poster),
        backdrop = chooseArtwork(firstPaint.backdrop, overlay?.backdrop, existing?.backdrop, profile?.backdrop),
        logo = chooseArtwork(firstPaint.logo, overlay?.logo, existing?.logo, profile?.logo),
        thumbnail = chooseArtwork(firstPaint.thumbnail, overlay?.thumbnail, existing?.thumbnail, profile?.thumbnail),
        posterProviderTag = chooseString(firstPaint.posterProviderTag, overlay?.posterProviderTag, existing?.posterProviderTag, profile?.posterProviderTag)
    )

    private fun chooseString(vararg slots: ResolvedSlot<String>?): ResolvedSlot<String> =
        slots.filterNotNull().reduce { a, b -> ResolvedSlot.chooseHigherRank(a, b) }

    private fun chooseList(vararg slots: ResolvedSlot<List<String>>?): ResolvedSlot<List<String>> =
        slots.filterNotNull().reduce { a, b -> ResolvedSlot.chooseHigherRank(a, b) }

    private fun chooseRating(vararg slots: ResolvedSlot<TitleRating>?): ResolvedSlot<TitleRating> =
        slots.filterNotNull().reduce { a, b -> ResolvedSlot.chooseHigherRank(a, b) }

    private fun chooseArtwork(vararg slots: ResolvedSlot<ArtworkDisplayRef>?): ResolvedSlot<ArtworkDisplayRef> =
        slots.filterNotNull().reduce { a, b -> ResolvedSlot.chooseHigherRank(a, b) }
}
