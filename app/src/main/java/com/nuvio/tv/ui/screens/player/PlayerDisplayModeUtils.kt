package com.nuvio.tv.ui.screens.player

import androidx.media3.ui.AspectRatioFrameLayout

internal object PlayerDisplayModeUtils {
    fun nextResizeMode(currentMode: Int): Int {
        return when (currentMode) {
            AspectRatioFrameLayout.RESIZE_MODE_FIT -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            AspectRatioFrameLayout.RESIZE_MODE_FILL -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH
            AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH -> AspectRatioFrameLayout.RESIZE_MODE_FIXED_HEIGHT
            AspectRatioFrameLayout.RESIZE_MODE_FIXED_HEIGHT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
    }

    fun resizeModeLabel(mode: Int): String {
        return when (mode) {
            AspectRatioFrameLayout.RESIZE_MODE_FIT -> "Fit (Original)"
            AspectRatioFrameLayout.RESIZE_MODE_FILL -> "Stretch"
            AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH -> "Fit Width"
            AspectRatioFrameLayout.RESIZE_MODE_FIXED_HEIGHT -> "Fit Height"
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> "Crop"
            else -> "Fit (Original)"
        }
    }
}
