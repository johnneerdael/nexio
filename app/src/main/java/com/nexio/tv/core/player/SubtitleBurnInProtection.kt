package com.nexio.tv.core.player

internal const val SUBTITLE_OFF_WHITE_ARGB: Int = 0xFFF0F0F0.toInt()
internal const val SUBTITLE_MAX_ALPHA: Float = 0.90f
internal const val SUBTITLE_BURN_IN_ZONE_COUNT: Int = 5
internal const val SUBTITLE_BURN_IN_ZONE_SPREAD_PERCENT: Float = 6f
internal const val SUBTITLE_BURN_IN_HORIZONTAL_JITTER_PX: Float = 6f
internal const val SUBTITLE_BURN_IN_HORIZONTAL_SLOT_COUNT: Int = 5
private const val DAY_MS: Long = 24L * 60L * 60L * 1000L

/**
 * Compute per-stream burn-in deltas from a deterministic seed.
 *
 * @param enabled whether burn-in protection is on; when false, returns DISABLED.
 * @param mediaSeedKey stable identity for the media ("contentId:s{n}e{m}" or stream URL).
 * @param userSalt persisted, per-install random string used to decorrelate users.
 * @param nowMs current epoch ms; bucketed to a day to drive cross-day rotation.
 */
internal fun computeBurnInProtectionState(
    enabled: Boolean,
    mediaSeedKey: String,
    userSalt: String,
    nowMs: Long,
): BurnInProtectionState {
    if (!enabled) return BurnInProtectionState.DISABLED

    val dayBucket = nowMs / DAY_MS
    val seedKey = "$mediaSeedKey:$userSalt:$dayBucket"
    val hash = seedKey.hashCode()

    val zoneIndex = Math.floorMod(hash, SUBTITLE_BURN_IN_ZONE_COUNT)
    val centerOffset = (SUBTITLE_BURN_IN_ZONE_COUNT - 1) / 2f
    val stepPercent = SUBTITLE_BURN_IN_ZONE_SPREAD_PERCENT / (SUBTITLE_BURN_IN_ZONE_COUNT - 1)
    val verticalDeltaPercent = (zoneIndex - centerOffset) * stepPercent

    val horizontalIndex = Math.floorMod(hash / 7, SUBTITLE_BURN_IN_HORIZONTAL_SLOT_COUNT)
    val horizontalCenter = (SUBTITLE_BURN_IN_HORIZONTAL_SLOT_COUNT - 1) / 2f
    val horizontalStep = SUBTITLE_BURN_IN_HORIZONTAL_JITTER_PX / horizontalCenter
    val horizontalOffsetPx = (horizontalIndex - horizontalCenter) * horizontalStep

    return BurnInProtectionState(
        enabled = true,
        verticalDeltaPercent = verticalDeltaPercent,
        horizontalOffsetPx = horizontalOffsetPx,
    )
}

/**
 * Build a stable media-identity seed string. Prefers contentId+season+episode for
 * tracked content; falls back to the stream URL for arbitrary playback (e.g., trailers
 * launched into the main player or untracked debrid streams).
 */
internal fun buildMediaSeedKey(
    contentId: String?,
    season: Int?,
    episode: Int?,
    streamUrl: String,
): String {
    val trimmedContentId = contentId?.takeIf { it.isNotBlank() }
    return when {
        trimmedContentId != null && season != null && episode != null ->
            "$trimmedContentId:s${season}e${episode}"
        // Partial metadata (one of season/episode null): fall back to content-level
        // granularity. Acceptable: hash collisions across episodes are harmless for
        // zone rotation; only repeats happen, not visible bugs.
        trimmedContentId != null -> trimmedContentId
        else -> "url:${streamUrl.hashCode()}"
    }
}
