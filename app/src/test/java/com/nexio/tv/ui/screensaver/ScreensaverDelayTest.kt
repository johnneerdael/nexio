package com.nexio.tv.ui.screensaver

import com.nexio.tv.data.local.coerceScreensaverDelaySeconds
import org.junit.Assert.assertEquals
import org.junit.Test

class ScreensaverDelayTest {
    @Test
    fun `value within range passes through`() {
        assertEquals(180, coerceScreensaverDelaySeconds(180))
    }

    @Test
    fun `value below 60 clamps to 60`() {
        assertEquals(60, coerceScreensaverDelaySeconds(0))
        assertEquals(60, coerceScreensaverDelaySeconds(45))
    }

    @Test
    fun `value above 600 clamps to 600`() {
        assertEquals(600, coerceScreensaverDelaySeconds(1200))
    }
}
