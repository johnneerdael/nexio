package com.nexio.tv.ui.screensaver

import androidx.compose.runtime.Immutable

@Immutable
data class IdleScreensaverSlide(
    val itemId: String,
    val itemType: String,
    val addonBaseUrl: String,
    val title: String,
    val backgroundUrl: String,
    val logoUrl: String?,
    val genres: List<String>,
    val description: String?,
    val releaseInfo: String?,
    val runtime: String?,
    val imdbRating: Float?,
    val tomatoesRating: Double? = null,
    val modeData: IdleScreensaverModeData = IdleScreensaverModeData(
        image = IdleScreensaverImageModeData(fallbackArtworkUrls = listOf(backgroundUrl))
    )
)

@Immutable
data class IdleScreensaverModeData(
    val image: IdleScreensaverImageModeData = IdleScreensaverImageModeData(),
    val trailer: IdleScreensaverTrailerModeData? = null
)

@Immutable
data class IdleScreensaverImageModeData(
    val fallbackArtworkUrls: List<String> = emptyList()
)

@Immutable
data class IdleScreensaverTrailerModeData(
    val trailerYtIds: List<String>
)

@Immutable
data class IdleTrailerScreensaverCandidate(
    val itemId: String,
    val itemType: String,
    val addonBaseUrl: String,
    val title: String,
    val logoUrl: String?,
    val backgroundUrl: String,
    val fallbackArtworkUrls: List<String>,
    val genres: List<String>,
    val description: String?,
    val releaseInfo: String?,
    val runtime: String?,
    val imdbRating: Float?,
    val tomatoesRating: Double? = null,
    val trailerYtIds: List<String>
) {
    constructor(
        slide: IdleScreensaverSlide,
        trailerYtIds: List<String>
    ) : this(
        itemId = slide.itemId,
        itemType = slide.itemType,
        addonBaseUrl = slide.addonBaseUrl,
        title = slide.title,
        logoUrl = slide.logoUrl,
        backgroundUrl = slide.backgroundUrl,
        fallbackArtworkUrls = slide.modeData.image.fallbackArtworkUrls.ifEmpty { listOf(slide.backgroundUrl) },
        genres = slide.genres,
        description = slide.description,
        releaseInfo = slide.releaseInfo,
        runtime = slide.runtime,
        imdbRating = slide.imdbRating,
        tomatoesRating = slide.tomatoesRating,
        trailerYtIds = trailerYtIds
    )

    val slide: IdleScreensaverSlide
        get() = IdleScreensaverSlide(
            itemId = itemId,
            itemType = itemType,
            addonBaseUrl = addonBaseUrl,
            title = title,
            backgroundUrl = backgroundUrl,
            logoUrl = logoUrl,
            genres = genres,
            description = description,
            releaseInfo = releaseInfo,
            runtime = runtime,
            imdbRating = imdbRating,
            tomatoesRating = tomatoesRating,
            modeData = IdleScreensaverModeData(
                image = IdleScreensaverImageModeData(fallbackArtworkUrls = fallbackArtworkUrls),
                trailer = IdleScreensaverTrailerModeData(trailerYtIds = trailerYtIds)
            )
        )
}

@Immutable
data class ScreensaverSlideCandidate(
    val itemKey: String,
    val contentId: String,
    val itemType: String,
    val title: String?,
    val subtitle: String?,
    val overview: String?,
    val rating: com.nexio.tv.domain.model.TitleRating?,
    val artwork: com.nexio.tv.core.artwork.ArtworkBundle,
    val preferredImage: com.nexio.tv.core.artwork.ArtworkDisplayRef?,
    val stableIds: com.nexio.tv.domain.model.ProviderIds,
    val trace: List<com.nexio.tv.domain.model.HydratedHomeFieldTrace>
)
