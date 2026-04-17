package com.nexio.tv.core.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DolbyVisionDiagnosticsTest {

    @Test
    fun `dolby vision verbose logging flag is tracked in kotlin`() {
        DoviBridge.setVerboseLoggingEnabled(false)
        assertFalse(DoviBridge.isVerboseLoggingEnabled())

        DoviBridge.setVerboseLoggingEnabled(true)
        assertTrue(DoviBridge.isVerboseLoggingEnabled())

        DoviBridge.setVerboseLoggingEnabled(false)
    }
}
