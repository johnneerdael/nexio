package com.nexio.tv.domain.model

import androidx.compose.runtime.Immutable

/**
 * Per-field display value with provenance. Used by [HomeRailProjectionReducer] to
 * enforce non-downgrade selection (a higher [rank] always beats a lower rank,
 * even when the higher-rank slot's [value] is null — null at RESOLVED means the
 * authoritative source explicitly produced no value, which must not be papered
 * over by a lower-rank fallback).
 */
@Immutable
data class ResolvedSlot<T>(
    val value: T?,
    val rank: DisplaySourceRank,
    val provider: String?,
    val role: String?,
    val updatedAtMs: Long,
    val expiresAtMs: Long?,
    val trace: List<String>
) {
    companion object {
        fun <T> chooseHigherRank(a: ResolvedSlot<T>, b: ResolvedSlot<T>): ResolvedSlot<T> =
            if (a.rank.ordinal >= b.rank.ordinal) a else b

        fun <T> empty(nowMs: Long): ResolvedSlot<T> =
            ResolvedSlot(
                value = null,
                rank = DisplaySourceRank.EMPTY,
                provider = null,
                role = null,
                updatedAtMs = nowMs,
                expiresAtMs = null,
                trace = emptyList()
            )
    }
}
