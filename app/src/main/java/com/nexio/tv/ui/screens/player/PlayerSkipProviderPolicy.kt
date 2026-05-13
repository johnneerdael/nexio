package com.nexio.tv.ui.screens.player

import com.nexio.tv.core.metadata.router.resolver.SkipProviderRoute

internal fun PlayerRuntimeController.isAnimePrimarySkipPath(): Boolean {
    return skipSegmentResolver.resolveRoute(buildSkipSegmentRequest()) == SkipProviderRoute.ANIME_PRIMARY
}

internal fun PlayerRuntimeController.isKitsuBackedAnimeContent(): Boolean {
    return isKitsuContentId(contentId) || isKitsuContentId(currentVideoId)
}

internal fun isKitsuContentId(id: String?): Boolean {
    return id?.trim()?.startsWith("kitsu:", ignoreCase = true) == true
}

internal fun PlayerRuntimeController.isTheIntroDbManagedSkipPath(): Boolean =
    theIntroDbEnabledSetting && !isAnimePrimarySkipPath()
