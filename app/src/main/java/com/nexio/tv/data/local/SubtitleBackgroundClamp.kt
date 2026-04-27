package com.nexio.tv.data.local

const val SUBTITLE_BACKGROUND_MAX_ALPHA: Int = 0xBF // 191 / 255 ≈ 75%

fun clampSubtitleBackgroundAlpha(argb: Int): Int {
    val storedAlpha = (argb ushr 24) and 0xFF
    val clampedAlpha = minOf(storedAlpha, SUBTITLE_BACKGROUND_MAX_ALPHA)
    val rgb = argb and 0x00FFFFFF
    return (clampedAlpha shl 24) or rgb
}
