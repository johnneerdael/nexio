package com.nexio.tv.core.sync

/**
 * The set of Supabase configuration surfaces protected by Contract v10's
 * timestamp-based optimistic concurrency. Each surface has an independent
 * `updated_at_ms` watermark; a stale-base rejection on one surface does
 * not affect any other.
 *
 * Profile-scoped surfaces (PROFILE_SETTINGS, PROFILE_AUTH_TOKENS) are stored
 * under a composite key (`<surface>:<profileId>`) so secondary-profile
 * watermarks don't collide across profiles.
 */
enum class SyncWatermarkSurface {
    ACCOUNT_SETTINGS,
    ACCOUNT_SETTINGS_SECTION,
    ACCOUNT_ADDONS,
    ACCOUNT_SECRETS,
    PROFILE_SETTINGS,
    PROFILE_AUTH_TOKENS,
}
