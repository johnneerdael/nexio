package com.nexio.tv

import com.nexio.tv.ui.navigation.Screen
import com.nexio.tv.ui.screensaver.PlaybackIdleGateSnapshot
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityIdleScreensaverTest {

    @Test
    fun `home route is not eligible while modern home trailer is active`() {
        assertFalse(
            isIdleScreensaverEligibleRoute(
                currentRoute = Screen.Home.route,
                playbackIdleSnapshot = PlaybackIdleGateSnapshot(),
                homeTrailerPlaybackActive = true
            )
        )
    }

    @Test
    fun `home route remains eligible without active home trailer`() {
        assertTrue(
            isIdleScreensaverEligibleRoute(
                currentRoute = Screen.Home.route,
                playbackIdleSnapshot = PlaybackIdleGateSnapshot(),
                homeTrailerPlaybackActive = false
            )
        )
    }
}
