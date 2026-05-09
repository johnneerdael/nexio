package com.nexio.tv.domain.model

/**
 * Per-field source rank used by [HomeRailProjectionReducer] to enforce the
 * non-downgrade rule: first-paint may only initialize a field that has no
 * higher-ranked source. Ordering matches the spec: a higher ordinal beats a
 * lower one.
 */
enum class DisplaySourceRank {
    EMPTY,
    PLACEHOLDER,
    FIRST_PAINT,
    STALE_RESOLVED,
    RESOLVED,
    USER_PROFILE_OVERLAY
}
