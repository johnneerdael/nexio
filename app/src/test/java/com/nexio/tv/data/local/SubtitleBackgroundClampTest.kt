package com.nexio.tv.data.local

import org.junit.Test
import org.junit.Assert.assertEquals

class SubtitleBackgroundClampTest {
    @Test fun `transparent stays transparent`() {
        assertEquals(0x00000000, clampSubtitleBackgroundAlpha(0x00000000))
    }

    @Test fun `value below cap is unchanged`() {
        // 50% alpha black = 0x80000000
        assertEquals(0x80000000.toInt(), clampSubtitleBackgroundAlpha(0x80000000.toInt()))
    }

    @Test fun `value at cap passes through`() {
        // 75% alpha = 0xBF (191)
        assertEquals(0xBF000000.toInt(), clampSubtitleBackgroundAlpha(0xBF000000.toInt()))
    }

    @Test fun `value above cap is clamped to 75 percent alpha`() {
        // Fully opaque white = 0xFFFFFFFF; alpha clamped to 0xBF, RGB preserved
        assertEquals(0xBFFFFFFF.toInt(), clampSubtitleBackgroundAlpha(0xFFFFFFFF.toInt()))
    }

    @Test fun `opaque black is clamped`() {
        assertEquals(0xBF000000.toInt(), clampSubtitleBackgroundAlpha(0xFF000000.toInt()))
    }
}
