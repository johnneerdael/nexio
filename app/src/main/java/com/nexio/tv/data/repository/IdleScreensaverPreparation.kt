package com.nexio.tv.data.repository

import com.nexio.tv.core.artwork.toLegacyArtworkString
import com.nexio.tv.ui.screensaver.IdleScreensaverImageModeData
import com.nexio.tv.ui.screensaver.IdleScreensaverModeData
import com.nexio.tv.ui.screensaver.IdleScreensaverSlide
import com.nexio.tv.ui.screensaver.ScreensaverSlideCandidate

internal fun ScreensaverSlideCandidate.toIdleScreensaverSlide(): IdleScreensaverSlide? {
    val title = title?.takeIf { it.isNotBlank() } ?: return null
    val backgroundUrl = preferredImage.toLegacyArtworkString() ?: return null
    val fallbackArtworkUrls = listOfNotNull(
        preferredImage.toLegacyArtworkString(),
        artwork.backdrop.toLegacyArtworkString(),
        artwork.poster.toLegacyArtworkString()
    ).distinct()

    return IdleScreensaverSlide(
        itemId = contentId,
        itemType = itemType,
        addonBaseUrl = "",
        title = title,
        backgroundUrl = backgroundUrl,
        logoUrl = artwork.logo.toLegacyArtworkString(),
        genres = emptyList(),
        description = overview?.takeIf { it.isNotBlank() },
        releaseInfo = subtitle?.takeIf { it.isNotBlank() },
        runtime = null,
        imdbRating = rating?.value?.toFloat(),
        tomatoesRating = null,
        modeData = IdleScreensaverModeData(
            image = IdleScreensaverImageModeData(fallbackArtworkUrls = fallbackArtworkUrls)
        )
    )
}
