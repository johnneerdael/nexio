package com.nexio.tv.ui.screens.player

import com.nexio.tv.core.metadata.router.resolver.SkipProviderRoute

internal fun PlayerRuntimeController.isAnimePrimarySkipPath(): Boolean {
    return skipSegmentResolver.resolveRoute(buildSkipSegmentRequest()) == SkipProviderRoute.ANIME_PRIMARY
}

internal fun PlayerRuntimeController.isTheIntroDbManagedSkipPath(): Boolean =
    theIntroDbEnabledSetting && !isAnimePrimarySkipPath()
