package com.nexio.tv.data.repository

import com.nexio.tv.ui.screensaver.IdleScreensaverImageModeData
import com.nexio.tv.ui.screensaver.IdleScreensaverModeData
import com.nexio.tv.ui.screensaver.IdleScreensaverSlide
import com.nexio.tv.ui.screensaver.IdleTrailerScreensaverCandidate
import com.nexio.tv.ui.screensaver.ScreensaverSlideCandidate
import com.nexio.tv.ui.screensaver.ScreensaverTrailerCandidate

internal fun ScreensaverSlideCandidate.toIdleScreensaverSlide(): IdleScreensaverSlide? {
    val title = title?.takeIf { it.isNotBlank() } ?: return null
    val fallbackArtwork = listOfNotNull(
        preferredImage,
        artwork.backdrop,
        artwork.poster
    ).distinct()

    return IdleScreensaverSlide(
        itemId = contentId,
        itemType = itemType,
        addonBaseUrl = "",
        title = title,
        backgroundArtwork = preferredImage,
        logoArtwork = artwork.logo,
        genres = genres,
        description = overview?.takeIf { it.isNotBlank() },
        releaseInfo = subtitle?.takeIf { it.isNotBlank() },
        runtime = runtime?.takeIf { it.isNotBlank() },
        imdbRating = rating?.value?.toFloat(),
        tomatoesRating = null,
        modeData = IdleScreensaverModeData(
            image = IdleScreensaverImageModeData(fallbackArtwork = fallbackArtwork)
        )
    )
}

internal fun ScreensaverTrailerCandidate.toIdleTrailerScreensaverCandidate(): IdleTrailerScreensaverCandidate? {
    val backgroundArtwork = artwork.backdrop
        ?: artwork.poster
        ?: return null
    val fallbackArtwork = listOfNotNull(
        artwork.backdrop,
        artwork.poster
    ).distinct()
    return IdleTrailerScreensaverCandidate(
        itemId = contentId,
        itemType = itemType,
        addonBaseUrl = "",
        title = title,
        logoArtwork = artwork.logo,
        backgroundArtwork = backgroundArtwork,
        fallbackArtwork = fallbackArtwork,
        genres = genres,
        description = overview?.takeIf { it.isNotBlank() },
        releaseInfo = releaseInfo?.takeIf { it.isNotBlank() },
        runtime = runtime?.takeIf { it.isNotBlank() },
        imdbRating = rating?.value?.toFloat(),
        tomatoesRating = null,
        trailerState = trailerState,
        stableIds = stableIds
    )
}
