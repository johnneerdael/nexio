package com.nexio.tv.integrations.hyperhdr.capture

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads `Display.getSupportedColorModes()` for the default display once at construction
 * and exposes whether the device's compositor can operate in any wide-color / HDR mode
 * (`BT2020`, `BT2100_PQ`, or `BT2100_HLG`).
 *
 * On devices whose compositor only reports `COLOR_MODE_DEFAULT` (sRGB) — e.g. UGOOS-AM6
 * on Android 9 — HDR content arrives at the GL effect pipeline already SDR-tone-mapped,
 * so [FormatDetector] should force SDR_NV12 regardless of source colorimetry.
 *
 * Wide-gamut SDR modes (`DCI_P3`, `DISPLAY_P3`, `ADOBE_RGB`) are deliberately NOT counted
 * as wide-color here — they're SDR with extended primaries, not HDR-composable.
 *
 * `Display.getSupportedColorModes()` is a `@hide` framework API (not in public SDK stubs).
 * It is accessed via reflection; on devices / API levels where it is absent the call
 * gracefully returns an empty array and `composesWideColor` is false.
 */
@Singleton
class DisplayColorCapability @Inject constructor(
    @ApplicationContext context: Context,
) {
    val composesWideColor: Boolean = run {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val display = dm.getDisplay(Display.DEFAULT_DISPLAY)
        val modes = display?.supportedColorModesCompat() ?: intArrayOf()
        containsWideColorMode(modes)
    }

    companion object {
        /**
         * Raw int values for the three HDR-capable compositor color modes.
         * `Display.COLOR_MODE_BT2020 = 12`, `BT2100_PQ = 13`, `BT2100_HLG = 14`.
         * Using literals because `Display.COLOR_MODE_*` are `@hide` and not in the public SDK.
         */
        private val WIDE_COLOR_MODES = intArrayOf(12, 13, 14)

        /** Pure-logic helper, JVM-testable without Android stubs. */
        fun containsWideColorMode(modes: IntArray): Boolean =
            modes.any { it in WIDE_COLOR_MODES }

        /**
         * Calls `Display.getSupportedColorModes()` via reflection.
         * Returns an empty array if the method is unavailable (pre-API or obfuscated ROM).
         */
        private fun Display.supportedColorModesCompat(): IntArray =
            runCatching {
                Display::class.java
                    .getMethod("getSupportedColorModes")
                    .invoke(this) as? IntArray
                    ?: intArrayOf()
            }.getOrDefault(intArrayOf())
    }
}
