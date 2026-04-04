package com.nexio.tv

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
