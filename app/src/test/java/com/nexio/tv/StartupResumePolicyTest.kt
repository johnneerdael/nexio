package com.nexio.tv

import com.nexio.tv.domain.model.AuthState
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

    @Test
    fun `auth qr onboarding only shows after auth confirms user is signed out`() {
        assertTrue(
            shouldShowAuthQrOnStartup(
                hasSeenAuthQrOnFirstLaunch = false,
                authState = AuthState.SignedOut,
                onboardingCompletedThisSession = false
            )
        )
        assertFalse(
            shouldShowAuthQrOnStartup(
                hasSeenAuthQrOnFirstLaunch = false,
                authState = AuthState.Loading,
                onboardingCompletedThisSession = false
            )
        )
        assertFalse(
            shouldShowAuthQrOnStartup(
                hasSeenAuthQrOnFirstLaunch = false,
                authState = AuthState.FullAccount(userId = "user", email = "user@example.com"),
                onboardingCompletedThisSession = false
            )
        )
    }

    @Test
    fun `auth qr onboarding stays hidden once onboarding is complete or state is unknown`() {
        assertFalse(
            shouldShowAuthQrOnStartup(
                hasSeenAuthQrOnFirstLaunch = true,
                authState = AuthState.SignedOut,
                onboardingCompletedThisSession = false
            )
        )
        assertFalse(
            shouldShowAuthQrOnStartup(
                hasSeenAuthQrOnFirstLaunch = false,
                authState = AuthState.SignedOut,
                onboardingCompletedThisSession = true
            )
        )
        assertFalse(
            shouldShowAuthQrOnStartup(
                hasSeenAuthQrOnFirstLaunch = null,
                authState = AuthState.SignedOut,
                onboardingCompletedThisSession = false
            )
        )
    }

    @Test
    fun `startup profile selection shows only before profile gate is passed`() {
        assertTrue(
            shouldShowStartupProfileSelection(
                hasPassedProfileSelectionGate = false,
                profileCount = 2
            )
        )
        assertFalse(
            shouldShowStartupProfileSelection(
                hasPassedProfileSelectionGate = true,
                profileCount = 2
            )
        )
    }

    @Test
    fun `startup profile selection stays hidden when there is one or no profiles`() {
        assertFalse(
            shouldShowStartupProfileSelection(
                hasPassedProfileSelectionGate = false,
                profileCount = 1
            )
        )
        assertFalse(
            shouldShowStartupProfileSelection(
                hasPassedProfileSelectionGate = false,
                profileCount = 0
            )
        )
    }
}
