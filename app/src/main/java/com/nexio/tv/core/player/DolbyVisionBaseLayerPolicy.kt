package com.nexio.tv.core.player

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display

data class DisplayHdrCapabilities(
    val hdrCapsKnown: Boolean,
    val supportsDolbyVision: Boolean,
    val supportsHdr10OrHdr10Plus: Boolean
)

enum class DolbyVisionBaseLayerDecision(val enableHevcMapping: Boolean) {
    DisabledBySetting(false),
    DisabledForDolbyVisionDisplay(false),
    EnabledHdr10Display(true),
    EnabledBestEffortSdrToneMap(true),
    EnabledUnknownDisplayCaps(true)
}

fun resolveDolbyVisionBaseLayerDecision(
    enabled: Boolean,
    displayCapabilities: DisplayHdrCapabilities
): DolbyVisionBaseLayerDecision {
    if (!enabled) return DolbyVisionBaseLayerDecision.DisabledBySetting
    if (displayCapabilities.supportsDolbyVision) {
        return DolbyVisionBaseLayerDecision.DisabledForDolbyVisionDisplay
    }
    if (!displayCapabilities.hdrCapsKnown) {
        return DolbyVisionBaseLayerDecision.EnabledUnknownDisplayCaps
    }
    if (displayCapabilities.supportsHdr10OrHdr10Plus) {
        return DolbyVisionBaseLayerDecision.EnabledHdr10Display
    }
    return DolbyVisionBaseLayerDecision.EnabledBestEffortSdrToneMap
}

@Suppress("DEPRECATION")
fun Context.queryDisplayHdrCapabilities(): DisplayHdrCapabilities {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
        return DisplayHdrCapabilities(
            hdrCapsKnown = false,
            supportsDolbyVision = false,
            supportsHdr10OrHdr10Plus = false
        )
    }
    val displayManager = getSystemService(DisplayManager::class.java)
    val display = displayManager?.getDisplay(Display.DEFAULT_DISPLAY)
    val hdrTypes = display?.hdrCapabilities?.supportedHdrTypes
        ?: return DisplayHdrCapabilities(
            hdrCapsKnown = false,
            supportsDolbyVision = false,
            supportsHdr10OrHdr10Plus = false
        )
    return DisplayHdrCapabilities(
        hdrCapsKnown = true,
        supportsDolbyVision = hdrTypes.contains(Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION),
        supportsHdr10OrHdr10Plus =
            hdrTypes.contains(Display.HdrCapabilities.HDR_TYPE_HDR10) ||
                hdrTypes.contains(Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS)
    )
}
