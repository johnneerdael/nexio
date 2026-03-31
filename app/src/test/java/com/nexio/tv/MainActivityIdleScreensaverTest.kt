package com.nexio.tv

import com.nexio.tv.ui.navigation.Screen
import com.nexio.tv.ui.screensaver.PlaybackIdleGateSnapshot
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityIdleScreensaverTest {

    @Test
    fun `idle timer resets when in app trailer starts`() {
        assertTrue(
            shouldRegisterIdleInteractionForTrailerPlaybackTransition(
                previousActive = false,
                currentActive = true
            )
        )
    }

    @Test
    fun `idle timer resets when in app trailer ends`() {
        assertTrue(
            shouldRegisterIdleInteractionForTrailerPlaybackTransition(
                previousActive = true,
                currentActive = false
            )
        )
    }

    @Test
    fun `home route is not eligible while modern home trailer is active`() {
        assertFalse(
            isIdleScreensaverEligibleRoute(
                currentRoute = Screen.Home.route,
                playbackIdleSnapshot = PlaybackIdleGateSnapshot(),
                inAppTrailerPlaybackActive = true
            )
        )
    }

    @Test
    fun `detail route is not eligible while detail trailer is active`() {
        assertFalse(
            isIdleScreensaverEligibleRoute(
                currentRoute = Screen.Detail.route,
                playbackIdleSnapshot = PlaybackIdleGateSnapshot(),
                inAppTrailerPlaybackActive = true
            )
        )
    }

    @Test
    fun `home route remains eligible without active in app trailer`() {
        assertTrue(
            isIdleScreensaverEligibleRoute(
                currentRoute = Screen.Home.route,
                playbackIdleSnapshot = PlaybackIdleGateSnapshot(),
                inAppTrailerPlaybackActive = false
            )
        )
    }
}
