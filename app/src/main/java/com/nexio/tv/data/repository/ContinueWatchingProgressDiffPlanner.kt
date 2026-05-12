package com.nexio.tv.data.repository

import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Picks a single ContinueWatching winner across multiple provider sources for the same item.
 *
 * Port of CrossWatch's `diff_progress` planner (`cw_platform/orchestrator/_planner.py:207-354`):
 *
 * - Records >= [NEAR_COMPLETE_PERCENT] are deferred to history sync (returns null) so the CW
 *   row clears rather than showing a stale near-complete progress bar.
 * - Trivial position deltas (< [TRIVIAL_DELTA_MS]) prefer the newer timestamp.
 * - Meaningful leads keep the leader even if a fresher timestamp exists for a record that
 *   would regress progress.
 */
@Singleton
class ContinueWatchingProgressDiffPlanner @Inject constructor() {

    fun pickWinner(candidates: List<ContinueWatchingRecord>): ContinueWatchingRecord? {
        if (candidates.isEmpty()) return null
        val active = candidates.filter { it.percentComplete() < NEAR_COMPLETE_PERCENT }
        if (active.isEmpty()) return null
        if (active.size == 1) return active.single()
        return active.reduce(::preferred)
    }

    private fun preferred(
        a: ContinueWatchingRecord,
        b: ContinueWatchingRecord,
    ): ContinueWatchingRecord {
        val delta = abs(a.positionMs - b.positionMs)
        if (delta < TRIVIAL_DELTA_MS) {
            return if (a.updatedAt >= b.updatedAt) a else b
        }
        // Meaningful delta — leader wins unless the trailing record is BOTH newer AND ahead.
        val leader = if (a.positionMs > b.positionMs) a else b
        val trailer = if (leader === a) b else a
        return if (trailer.updatedAt > leader.updatedAt && trailer.positionMs > leader.positionMs) {
            trailer
        } else {
            leader
        }
    }

    private fun ContinueWatchingRecord.percentComplete(): Float =
        if (durationMs <= 0L) 0f else (positionMs.toFloat() / durationMs.toFloat()) * 100f

    private companion object {
        // 30 seconds — matches CrossWatch _planner.py:218 delta_seconds default.
        const val TRIVIAL_DELTA_MS = 30_000L
        // 95% — matches CrossWatch _planner.py:218 max_percent default.
        const val NEAR_COMPLETE_PERCENT = 95f
    }
}
