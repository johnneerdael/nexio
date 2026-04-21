package com.nexio.tv

import com.nexio.tv.domain.model.AuthState

internal enum class StartupLaunchDisposition {
    COLD_PROCESS_START,
    WARM_PROCESS_SKIP_SPLASH,
    WARM_PROCESS_SHOW_SPLASH_UNREADY
}

internal fun isWarmResumeCriticalStateReady(
    hasSeenAuthQrOnFirstLaunch: Boolean?,
    hasChosenLayout: Boolean?
): Boolean {
    return hasSeenAuthQrOnFirstLaunch != null && hasChosenLayout != null
}

internal fun resolveStartupLaunchDisposition(
    processUiBootstrapped: Boolean,
    hasSeenAuthQrOnFirstLaunch: Boolean?,
    hasChosenLayout: Boolean?
): StartupLaunchDisposition {
    if (!processUiBootstrapped) return StartupLaunchDisposition.COLD_PROCESS_START
    return if (isWarmResumeCriticalStateReady(hasSeenAuthQrOnFirstLaunch, hasChosenLayout)) {
        StartupLaunchDisposition.WARM_PROCESS_SKIP_SPLASH
    } else {
        StartupLaunchDisposition.WARM_PROCESS_SHOW_SPLASH_UNREADY
    }
}

internal fun shouldShowAuthQrOnStartup(
    hasSeenAuthQrOnFirstLaunch: Boolean?,
    authState: AuthState,
    onboardingCompletedThisSession: Boolean,
    hadAuthenticatedSession: Boolean?
): Boolean {
    // A returning user is anyone we've previously seen authenticated on this
    // install — never show the onboarding QR to them, regardless of what the
    // onboarding DataStore says (it can be wiped/corrupted on upgrade).
    if (hadAuthenticatedSession == true) return false
    return hasSeenAuthQrOnFirstLaunch == false &&
        authState is AuthState.SignedOut &&
        !onboardingCompletedThisSession
}

internal fun shouldShowStartupProfileSelection(
    hasPassedProfileSelectionGate: Boolean,
    profileCount: Int
): Boolean {
    return !hasPassedProfileSelectionGate && profileCount > 1
}
