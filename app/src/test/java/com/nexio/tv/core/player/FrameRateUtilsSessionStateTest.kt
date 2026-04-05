package com.nexio.tv.core.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameRateUtilsSessionStateTest {

    @Test
    fun `display mode changes are blocked when main player session is inactive`() {
        FrameRateUtils.resetDisplayModeSessionStateForTests()

        assertFalse(FrameRateUtils.isMainPlayerDisplayModeSessionActiveForTests())
        assertFalse(FrameRateUtils.canChangeDisplayModeForPlaybackForTests())
    }

    @Test
    fun `begin main player session enables display mode changes until ended`() {
        FrameRateUtils.resetDisplayModeSessionStateForTests()

        FrameRateUtils.beginMainPlayerDisplayModeSession()

        assertTrue(FrameRateUtils.isMainPlayerDisplayModeSessionActiveForTests())
        assertTrue(FrameRateUtils.canChangeDisplayModeForPlaybackForTests())

        FrameRateUtils.endMainPlayerDisplayModeSession()

        assertFalse(FrameRateUtils.isMainPlayerDisplayModeSessionActiveForTests())
        assertFalse(FrameRateUtils.canChangeDisplayModeForPlaybackForTests())
    }
}
