package com.nexio.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupResumePolicyTest {

    @Test
    fun `critical warm resume state requires onboarding and layout readiness`() {
        assertFalse(isWarmResumeCriticalStateReady(null, true))
        assertFalse(isWarmResumeCriticalStateReady(false, null))
        assertTrue(isWarmResumeCriticalStateReady(false, true))
    }

    @Test
    fun `cold process start always shows splash path`() {
        assertEquals(
            StartupLaunchDisposition.COLD_PROCESS_START,
            resolveStartupLaunchDisposition(
                processUiBootstrapped = false,
                hasSeenAuthQrOnFirstLaunch = true,
                hasChosenLayout = true
            )
        )
    }

    @Test
    fun `warm bootstrapped process skips splash when critical state is ready`() {
        assertEquals(
            StartupLaunchDisposition.WARM_PROCESS_SKIP_SPLASH,
            resolveStartupLaunchDisposition(
                processUiBootstrapped = true,
                hasSeenAuthQrOnFirstLaunch = false,
                hasChosenLayout = true
            )
        )
    }

    @Test
    fun `warm bootstrapped process keeps splash when critical state is not ready`() {
        assertEquals(
            StartupLaunchDisposition.WARM_PROCESS_SHOW_SPLASH_UNREADY,
            resolveStartupLaunchDisposition(
                processUiBootstrapped = true,
                hasSeenAuthQrOnFirstLaunch = null,
                hasChosenLayout = true
            )
        )
    }
}
