package com.nexio.tv.core.player

import org.junit.Assert.assertEquals
import org.junit.Test

class DolbyVisionBaseLayerPolicyTest {

    @Test
    fun `disabled setting never enables dv7 hevc mapping`() {
        assertEquals(
            DolbyVisionBaseLayerDecision.DisabledBySetting,
            resolveDolbyVisionBaseLayerDecision(
                enabled = false,
                displayCapabilities = DisplayHdrCapabilities(
                    hdrCapsKnown = true,
                    supportsDolbyVision = false,
                    supportsHdr10OrHdr10Plus = true
                )
            )
        )
    }

    @Test
    fun `dolby vision display preserves normal dv playback`() {
        assertEquals(
            DolbyVisionBaseLayerDecision.DisabledForDolbyVisionDisplay,
            resolveDolbyVisionBaseLayerDecision(
                enabled = true,
                displayCapabilities = DisplayHdrCapabilities(
                    hdrCapsKnown = true,
                    supportsDolbyVision = true,
                    supportsHdr10OrHdr10Plus = true
                )
            )
        )
    }

    @Test
    fun `hdr10 only display enables intended hdr10 base layer playback`() {
        assertEquals(
            DolbyVisionBaseLayerDecision.EnabledHdr10Display,
            resolveDolbyVisionBaseLayerDecision(
                enabled = true,
                displayCapabilities = DisplayHdrCapabilities(
                    hdrCapsKnown = true,
                    supportsDolbyVision = false,
                    supportsHdr10OrHdr10Plus = true
                )
            )
        )
    }

    @Test
    fun `sdr only display still enables best effort platform tone mapping`() {
        assertEquals(
            DolbyVisionBaseLayerDecision.EnabledBestEffortSdrToneMap,
            resolveDolbyVisionBaseLayerDecision(
                enabled = true,
                displayCapabilities = DisplayHdrCapabilities(
                    hdrCapsKnown = true,
                    supportsDolbyVision = false,
                    supportsHdr10OrHdr10Plus = false
                )
            )
        )
    }

    @Test
    fun `unknown hdr caps still enable explicit user selected mapping`() {
        assertEquals(
            DolbyVisionBaseLayerDecision.EnabledUnknownDisplayCaps,
            resolveDolbyVisionBaseLayerDecision(
                enabled = true,
                displayCapabilities = DisplayHdrCapabilities(
                    hdrCapsKnown = false,
                    supportsDolbyVision = false,
                    supportsHdr10OrHdr10Plus = false
                )
            )
        )
    }
}
