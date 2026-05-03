package com.nexio.tv.integrations.hyperhdr.capture

import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.util.UnstableApi
import com.nexio.tv.integrations.hyperhdr.data.HdrMode

/**
 * Pure-logic mapping from ExoPlayer's video [ColorInfo] + a user [HdrMode] preference to a
 * [CaptureMode]. JVM-testable; the Player.Listener wiring in PlayerRuntimeControllerInitialization
 * calls this on every onTracksChanged to decide whether to install the SDR or HDR effect.
 */
object FormatDetector {

    @UnstableApi
    fun detect(
        colorInfo: ColorInfo?,
        hdrMode: HdrMode,
        deviceComposesWideColor: Boolean,
    ): CaptureMode {
        // Strongest gate first: if the device's compositor doesn't operate in wide-color,
        // any HDR pipeline we attempt would receive SDR-tone-mapped data anyway. Force SDR.
        if (!deviceComposesWideColor) return CaptureMode.SDR_NV12

        if (hdrMode == HdrMode.ForceSdr) return CaptureMode.SDR_NV12

        return when (colorInfo?.colorTransfer) {
            C.COLOR_TRANSFER_ST2084, C.COLOR_TRANSFER_HLG -> CaptureMode.HDR_P010
            else -> CaptureMode.SDR_NV12
        }
    }
}
