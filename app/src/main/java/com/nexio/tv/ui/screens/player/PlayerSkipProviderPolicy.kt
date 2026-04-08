package com.nexio.tv.ui.screens.player

import com.nexio.tv.data.repository.SkipProviderArbiter
import com.nexio.tv.data.repository.SkipProviderRoute

internal fun PlayerRuntimeController.currentSkipProviderId(): String? =
    currentVideoId?.takeIf { it.isNotBlank() } ?: contentId

internal fun PlayerRuntimeController.isAnimePrimarySkipPath(): Boolean {
    return SkipProviderArbiter.resolve(
        contentType = contentType,
        effectiveId = currentSkipProviderId(),
        fallbackId = contentId
    ) == SkipProviderRoute.ANIME_PRIMARY
}

internal fun PlayerRuntimeController.isTheIntroDbManagedSkipPath(): Boolean =
    theIntroDbEnabledSetting && !isAnimePrimarySkipPath()
