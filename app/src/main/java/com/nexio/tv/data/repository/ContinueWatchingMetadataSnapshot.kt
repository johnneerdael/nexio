package com.nexio.tv.data.repository

import com.nexio.tv.core.metadata.router.MetadataDecisionReason
import com.nexio.tv.core.metadata.router.MetadataPrimaryProvider
import com.nexio.tv.core.metadata.router.MetadataRoute
import com.nexio.tv.domain.model.DisplaySourceRank
import com.nexio.tv.domain.model.HomeDisplayMetadata
import com.nexio.tv.ui.screens.home.HomeRailProjectionReducer
import com.nexio.tv.ui.screens.home.emptySlotsAt
import com.nexio.tv.ui.screens.home.toHomeDisplayMetadata
import com.nexio.tv.ui.screens.home.toResolvedFieldSlots

data class ContinueWatchingMetadataSnapshot(
    val routingVersion: Int,
    val parentId: String,
    val primaryProvider: MetadataPrimaryProvider,
    val decisionReason: MetadataDecisionReason,
    val clickTimeDisplayMetadata: HomeDisplayMetadata
) {
    companion object {
        const val CURRENT_ROUTING_VERSION = 1

        fun fromRoute(
            route: MetadataRoute,
            clickTimeDisplayMetadata: HomeDisplayMetadata
        ): ContinueWatchingMetadataSnapshot {
            return ContinueWatchingMetadataSnapshot(
                routingVersion = CURRENT_ROUTING_VERSION,
                parentId = route.parentId,
                primaryProvider = route.provider,
                decisionReason = route.reason,
                clickTimeDisplayMetadata = clickTimeDisplayMetadata
            )
        }

        fun shouldReroute(storedRoutingVersion: Int): Boolean {
            return storedRoutingVersion != CURRENT_ROUTING_VERSION
        }

        /**
         * Phase 3.8 — rank-aware merge of CW metadata sources via the typed
         * slot bag, replacing the prior `coalesceWith` chain. Observable
         * semantics are preserved: the slot conversion helpers in
         * `SlotConversions.kt` coerce null/blank input fields to
         * `DisplaySourceRank.EMPTY` (rank 0), so a slot with a null value
         * loses to any lower-rank slot with a non-null value. Combined with
         * the rank assignment below, this yields exactly the same per-field
         * winner as `canonical.coalesceWith(clickTime).coalesceWith(persistedFallback)`:
         *
         * Precedence (matches the original `canonical.coalesceWith(clickTime)
         * .coalesceWith(persistedFallback)` chain — canonical first, then
         * clickTime fills canonical nulls, then persistedFallback fills the
         * remainder):
         *
         * - canonical          → RESOLVED       (rank 4) — wins per field when non-null
         * - clickTime          → STALE_RESOLVED (rank 3) — fills canonical nulls
         * - persistedFallback  → FIRST_PAINT    (rank 2) — fills remaining nulls
         *
         * Rank names don't perfectly match semantic intent (persistedFallback
         * isn't literally "first paint"); we use the ordering for precedence
         * because [DisplaySourceRank] doesn't expose finer-grained tiers.
         *
         * Closes Phase 3.8's `coalesceWith` migration without bumping the
         * snapshot schema (clickTimeDisplayMetadata stays HomeDisplayMetadata
         * for now; full type-shift to ResolvedDisplayFieldSlots is deferred).
         */
        fun renderDisplayMetadata(
            canonical: HomeDisplayMetadata?,
            clickTime: HomeDisplayMetadata?,
            persistedFallback: HomeDisplayMetadata?
        ): HomeDisplayMetadata {
            if (canonical == null && clickTime == null && persistedFallback == null) {
                return HomeDisplayMetadata()
            }
            val nowMs = System.currentTimeMillis()
            val canonicalSlots = canonical?.toResolvedFieldSlots(
                nowMs = nowMs,
                rank = DisplaySourceRank.RESOLVED,
            )
            val clickTimeSlots = clickTime?.toResolvedFieldSlots(
                nowMs = nowMs,
                rank = DisplaySourceRank.STALE_RESOLVED,
            )
            val persistedFallbackSlots = persistedFallback?.toResolvedFieldSlots(
                nowMs = nowMs,
                rank = DisplaySourceRank.FIRST_PAINT,
            )
            // HomeRailProjectionReducer.reduce signature: reduce(firstPaint, overlay, existing, profile).
            // For rank-aware merge per-slot, the parameter assignment is
            // positional — pickHigherRanked picks the highest rank with a
            // non-null value across all 4 slots, so any input can go in any
            // position. We pass the lowest-rank non-null input as firstPaint
            // (required non-null), then layer the others above.
            val firstPaint = persistedFallbackSlots
                ?: clickTimeSlots
                ?: canonicalSlots
                ?: emptySlotsAt(nowMs)
            val overlayInput = if (firstPaint !== canonicalSlots) canonicalSlots else null
            val existingInput = if (firstPaint !== clickTimeSlots) clickTimeSlots else null
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
