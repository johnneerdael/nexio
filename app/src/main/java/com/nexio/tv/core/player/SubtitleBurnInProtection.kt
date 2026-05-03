package com.nexio.tv.core.player

internal const val SUBTITLE_OFF_WHITE_ARGB: Int = 0xFFF0F0F0.toInt()
internal const val SUBTITLE_MAX_ALPHA: Float = 0.90f
internal const val SUBTITLE_BURN_IN_ZONE_COUNT: Int = 5
internal const val SUBTITLE_BURN_IN_ZONE_SPREAD_PERCENT: Float = 6f
internal const val SUBTITLE_BURN_IN_HORIZONTAL_JITTER_PX: Float = 6f
internal const val SUBTITLE_BURN_IN_HORIZONTAL_SLOT_COUNT: Int = 5

private const val DAY_MS: Long = 24L * 60L * 60L * 1000L

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

    val horizontalIndex = Math.floorMod("$seedKey:h".hashCode(), SUBTITLE_BURN_IN_HORIZONTAL_SLOT_COUNT)
    val horizontalCenter = (SUBTITLE_BURN_IN_HORIZONTAL_SLOT_COUNT - 1) / 2f
    val horizontalStep = SUBTITLE_BURN_IN_HORIZONTAL_JITTER_PX / horizontalCenter
    val horizontalOffsetPx = (horizontalIndex - horizontalCenter) * horizontalStep

    return BurnInProtectionState(
        enabled = true,
        verticalDeltaPercent = verticalDeltaPercent,
        horizontalOffsetPx = horizontalOffsetPx,
    )
}

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
        trimmedContentId != null -> trimmedContentId
        else -> "url:${streamUrl.hashCode()}"
    }
}
