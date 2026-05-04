package com.nexio.tv.integrations.hyperhdr.capture

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DisplayColorCapabilityTest {

    @Test
    fun `containsWideColorMode returns false for default-only display`() {
        val modes = intArrayOf(0)   // COLOR_MODE_DEFAULT
        assertThat(DisplayColorCapability.containsWideColorMode(modes)).isFalse()
    }

    @Test
    fun `containsWideColorMode returns false for SDR wide-gamut modes`() {
        // DCI_P3, DISPLAY_P3, ADOBE_RGB are wide-gamut but SDR — not enough for HDR ambilight.
        val modes = intArrayOf(0, 8, 10, 11)
        assertThat(DisplayColorCapability.containsWideColorMode(modes)).isFalse()
    }

    @Test
    fun `containsWideColorMode returns true when BT2020 is present`() {
        val modes = intArrayOf(0, 12)
        assertThat(DisplayColorCapability.containsWideColorMode(modes)).isTrue()
    }

    @Test
    fun `containsWideColorMode returns true when BT2100_PQ is present`() {
        val modes = intArrayOf(0, 13)
        assertThat(DisplayColorCapability.containsWideColorMode(modes)).isTrue()
    }

    @Test
    fun `containsWideColorMode returns true when BT2100_HLG is present`() {
        val modes = intArrayOf(0, 14)
        assertThat(DisplayColorCapability.containsWideColorMode(modes)).isTrue()
    }

    @Test
    fun `containsWideColorMode returns false for empty array`() {
        assertThat(DisplayColorCapability.containsWideColorMode(intArrayOf())).isFalse()
    }
}
