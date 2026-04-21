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
                onboardingCompletedThisSession = false,
                hadAuthenticatedSession = false
            )
        )
        assertFalse(
            shouldShowAuthQrOnStartup(
                hasSeenAuthQrOnFirstLaunch = false,
                authState = AuthState.Loading,
                onboardingCompletedThisSession = false,
                hadAuthenticatedSession = false
            )
        )
        assertFalse(
            shouldShowAuthQrOnStartup(
                hasSeenAuthQrOnFirstLaunch = false,
                authState = AuthState.FullAccount(userId = "user", email = "user@example.com"),
                onboardingCompletedThisSession = false,
                hadAuthenticatedSession = false
            )
        )
    }

    @Test
    fun `auth qr onboarding stays hidden once onboarding is complete or state is unknown`() {
        assertFalse(
            shouldShowAuthQrOnStartup(
                hasSeenAuthQrOnFirstLaunch = true,
                authState = AuthState.SignedOut,
                onboardingCompletedThisSession = false,
                hadAuthenticatedSession = false
            )
        )
        assertFalse(
            shouldShowAuthQrOnStartup(
                hasSeenAuthQrOnFirstLaunch = false,
                authState = AuthState.SignedOut,
                onboardingCompletedThisSession = true,
                hadAuthenticatedSession = false
            )
        )
        assertFalse(
            shouldShowAuthQrOnStartup(
                hasSeenAuthQrOnFirstLaunch = null,
                authState = AuthState.SignedOut,
                onboardingCompletedThisSession = false,
                hadAuthenticatedSession = false
            )
        )
    }

    @Test
    fun `session-lost state does not trigger onboarding QR`() {
        // SessionLost means we already identified the user as returning but
        // couldn't restore their session. They should see the Account-panel
        // reconnect prompt, not the first-run onboarding QR.
        assertFalse(
            shouldShowAuthQrOnStartup(
                hasSeenAuthQrOnFirstLaunch = false,
                authState = AuthState.SessionLost,
                onboardingCompletedThisSession = false,
                hadAuthenticatedSession = false
            )
        )
    }

    @Test
    fun `returning user with prior auth marker never sees onboarding QR`() {
        // Simulates post-upgrade: the onboarding DataStore was wiped or
        // never migrated, so hasSeenAuthQrOnFirstLaunch looks like the
        // first-run value, and authState briefly flashes SignedOut while
        // Supabase hydrates storage. The presence marker keeps returning
        // users out of the QR onboarding regardless.
        assertFalse(
            shouldShowAuthQrOnStartup(
                hasSeenAuthQrOnFirstLaunch = false,
                authState = AuthState.SignedOut,
                onboardingCompletedThisSession = false,
                hadAuthenticatedSession = true
            )
        )
    }

    @Test
    fun `null presence marker falls through to classic onboarding gate`() {
        // Marker still loading from DataStore: fall back to the pre-marker
        // behaviour — show QR if this is a first-run SignedOut launch.
        assertTrue(
            shouldShowAuthQrOnStartup(
                hasSeenAuthQrOnFirstLaunch = false,
                authState = AuthState.SignedOut,
                onboardingCompletedThisSession = false,
                hadAuthenticatedSession = null
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
