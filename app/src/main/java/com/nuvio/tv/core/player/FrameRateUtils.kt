package com.nuvio.tv.core.player

import android.app.Activity
import android.hardware.display.DisplayManager
import android.os.Build
import android.util.Log
import android.view.Display
import android.view.WindowManager

/**
 * Auto frame rate matching utility.
 * Switches the display refresh rate to match the video frame rate for judder-free playback.
 * Inspired by Just (Video) Player's implementation.
 */
object FrameRateUtils {

    private const val TAG = "FrameRateUtils"

    /** Saved original display mode ID so we can restore it when playback ends. */
    private var originalModeId: Int = -1

    /**
     * Normalize a refresh rate to an integer × 100 for safe floating-point comparison.
     */
    private fun normRate(rate: Float): Int = (rate * 100f).toInt()

    /**
     * Attempt to match the display refresh rate to the video [frameRate].
     *
     * @param activity The current Activity (needed for window attributes).
     * @param frameRate The detected video frame rate (e.g. 23.976, 24, 25, 29.97, 30, 50, 59.94).
     * @return `true` if a mode switch was requested, `false` if not needed or unavailable.
     */
    fun matchFrameRate(activity: Activity, frameRate: Float): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
        if (frameRate <= 0f) return false

        try {
            val window = activity.window ?: return false
            val display = window.decorView.display ?: return false
            val supportedModes = display.supportedModes
            val activeMode = display.mode

            if (supportedModes.size <= 1) return false

            // Save original mode so we can restore later
            if (originalModeId == -1) {
                originalModeId = activeMode.modeId
            }

            // Collect modes that match current resolution
            val sameSizeModes = supportedModes.filter {
                it.physicalWidth == activeMode.physicalWidth &&
                        it.physicalHeight == activeMode.physicalHeight
            }

            if (sameSizeModes.size <= 1) return false

            // Among same-size modes, find ones with refresh rate >= video FPS
            val modesHigh = sameSizeModes.filter {
                normRate(it.refreshRate) >= normRate(frameRate)
            }

            // Track the highest refresh rate mode at same resolution
            val modeTop = sameSizeModes.maxByOrNull { normRate(it.refreshRate) } ?: activeMode

            // Find the best mode — one whose refresh rate is an exact integer multiple of the video FPS
            var modeBest: Display.Mode? = null
            for (mode in modesHigh) {
                if (normRate(mode.refreshRate) % normRate(frameRate) <= 1) {
                    if (modeBest == null || normRate(mode.refreshRate) > normRate(modeBest.refreshRate)) {
                        modeBest = mode
                    }
                }
            }

            // Fallback to highest available if no exact multiple found
            if (modeBest == null) {
                modeBest = modeTop
            }

            val switchNeeded = modeBest.modeId != activeMode.modeId
            if (switchNeeded) {
                Log.d(TAG, "Switching display mode: ${activeMode.refreshRate}Hz → ${modeBest.refreshRate}Hz " +
                        "(video ${frameRate}fps)")
                val layoutParams = window.attributes
                layoutParams.preferredDisplayModeId = modeBest.modeId
                window.attributes = layoutParams
            } else {
                Log.d(TAG, "Display already at optimal rate ${activeMode.refreshRate}Hz for ${frameRate}fps")
            }

            return switchNeeded
        } catch (e: Exception) {
            Log.e(TAG, "Failed to match frame rate", e)
            return false
        }
    }

    /**
     * Restore the original display mode that was active before frame rate matching.
     *
     * @param activity The current Activity.
     */
    fun restoreOriginalMode(activity: Activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        if (originalModeId == -1) return

        try {
            val window = activity.window ?: return
            val layoutParams = window.attributes
            layoutParams.preferredDisplayModeId = originalModeId
            window.attributes = layoutParams
            Log.d(TAG, "Restored original display mode id=$originalModeId")
            originalModeId = -1
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restore display mode", e)
        }
    }

    /**
     * Detect the video frame rate from an ExoPlayer Format and snap to standard rates.
     *
     * @param formatFrameRate The frame rate reported by ExoPlayer's video track Format.
     * @return The snapped standard frame rate, or the original if no standard match.
     */
    fun snapToStandardRate(formatFrameRate: Float): Float {
        if (formatFrameRate <= 0f) return formatFrameRate
        return when {
            formatFrameRate in 23.90f..23.988f -> 24000f / 1001f  // 23.976 (NTSC film)
            formatFrameRate in 23.988f..24.1f -> 24f
            formatFrameRate in 24.9f..25.1f -> 25f                 // PAL
            formatFrameRate in 29.90f..29.985f -> 30000f / 1001f  // 29.97 NTSC
            formatFrameRate in 29.985f..30.1f -> 30f
            formatFrameRate in 49.9f..50.1f -> 50f                 // PAL interlaced
            formatFrameRate in 59.9f..59.97f -> 60000f / 1001f    // 59.94 NTSC
            formatFrameRate in 59.97f..60.1f -> 60f
            else -> formatFrameRate
        }
    }
}
