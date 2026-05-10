package com.nexio.tv.data.repository

import com.nexio.tv.core.metadata.router.MetadataDecisionReason
import com.nexio.tv.core.metadata.router.MetadataPrimaryProvider
import com.nexio.tv.core.metadata.router.MetadataRoute
import com.nexio.tv.domain.model.DisplaySourceRank
import com.nexio.tv.domain.model.HomeDisplayMetadata
import com.nexio.tv.domain.model.ResolvedDisplayFieldSlots
import com.nexio.tv.ui.screens.home.HomeRailProjectionReducer
import com.nexio.tv.ui.screens.home.emptySlotsAt
import com.nexio.tv.ui.screens.home.toHomeDisplayMetadata
import com.nexio.tv.ui.screens.home.toResolvedFieldSlots

data class ContinueWatchingMetadataSnapshot(
    val routingVersion: Int,
    val parentId: String,
    val primaryProvider: MetadataPrimaryProvider,
    val decisionReason: MetadataDecisionReason,
    val clickTimeSlots: ResolvedDisplayFieldSlots
) {
    companion object {
        const val CURRENT_ROUTING_VERSION = 1

        fun fromRoute(
            route: MetadataRoute,
            clickTimeSlots: ResolvedDisplayFieldSlots
        ): ContinueWatchingMetadataSnapshot {
            return ContinueWatchingMetadataSnapshot(
                routingVersion = CURRENT_ROUTING_VERSION,
                parentId = route.parentId,
                primaryProvider = route.provider,
                decisionReason = route.reason,
                clickTimeSlots = clickTimeSlots
            )
        }

        fun shouldReroute(storedRoutingVersion: Int): Boolean {
            return storedRoutingVersion != CURRENT_ROUTING_VERSION
        }

        /**
         * Phase 3.8 full — rank-aware merge of CW metadata sources via the typed
         * slot bag. The clickTimeSlots input is already typed (post-3.8-full
         * field-type migration); canonical and persistedFallback still come in
         * as HomeDisplayMetadata until those producers also migrate.
         *
         * Precedence (matches the original coalesceWith chain):
         *   canonical          -> RESOLVED       (rank 4)
         *   clickTimeSlots     -> STALE_RESOLVED (rank 3) — already typed; re-ranked
         *   persistedFallback  -> FIRST_PAINT    (rank 2)
         *
         * EMPTY-on-null behavior in SlotConversions.kt helpers preserves
         * coalesceWith's `this.field ?: fallback.field` semantic field-by-field.
         */
        fun renderDisplayMetadata(
            canonical: HomeDisplayMetadata?,
            clickTimeSlots: ResolvedDisplayFieldSlots?,
            persistedFallback: HomeDisplayMetadata?
        ): HomeDisplayMetadata {
            if (canonical == null && clickTimeSlots == null && persistedFallback == null) {
                return HomeDisplayMetadata()
            }
            val nowMs = System.currentTimeMillis()
            val canonicalSlots = canonical?.toResolvedFieldSlots(
                nowMs = nowMs,
                rank = DisplaySourceRank.RESOLVED,
            )
            val clickTimeSlotsReranked = clickTimeSlots?.rerankedTo(DisplaySourceRank.STALE_RESOLVED)
            val persistedFallbackSlots = persistedFallback?.toResolvedFieldSlots(
                nowMs = nowMs,
                rank = DisplaySourceRank.FIRST_PAINT,
            )
            val firstPaint = persistedFallbackSlots
                ?: clickTimeSlotsReranked
                ?: canonicalSlots
                ?: emptySlotsAt(nowMs)
            val overlayInput = if (firstPaint !== canonicalSlots) canonicalSlots else null
            val existingInput = if (firstPaint !== clickTimeSlotsReranked) clickTimeSlotsReranked else null
            val merged = HomeRailProjectionReducer.reduce(
                firstPaint = firstPaint,
                overlay = overlayInput,
                existing = existingInput,
                profile = null,
            )
            return merged.toHomeDisplayMetadata()
        }
    }
}

/**
 * Re-ranks every non-EMPTY slot in this bag to the new [rank]. EMPTY slots
 * stay EMPTY (the null-value-coerces-to-EMPTY rule from SlotConversions.kt
 * must hold across rerankings — a slot was EMPTY because its value was
 * null, and a re-rank doesn't change that).
 */
private fun ResolvedDisplayFieldSlots.rerankedTo(rank: DisplaySourceRank): ResolvedDisplayFieldSlots {
    fun <T> com.nexio.tv.domain.model.ResolvedSlot<T>.reranked(): com.nexio.tv.domain.model.ResolvedSlot<T> =
        if (this.rank == DisplaySourceRank.EMPTY) this else copy(rank = rank)
    return ResolvedDisplayFieldSlots(
        title = title.reranked(),
        originalTitle = originalTitle.reranked(),
        overview = overview.reranked(),
        genres = genres.reranked(),
        releaseInfo = releaseInfo.reranked(),
        runtime = runtime.reranked(),
        rating = rating.reranked(),
        poster = poster.reranked(),
        backdrop = backdrop.reranked(),
        logo = logo.reranked(),
        thumbnail = thumbnail.reranked(),
        posterProviderTag = posterProviderTag.reranked()
    )
}
