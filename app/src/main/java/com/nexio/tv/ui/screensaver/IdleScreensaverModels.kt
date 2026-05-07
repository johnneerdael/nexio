package com.nexio.tv.ui.screensaver

import androidx.compose.runtime.Immutable
import com.nexio.tv.core.artwork.ArtworkBundle
import com.nexio.tv.core.artwork.ArtworkDisplayRef
import com.nexio.tv.core.metadata.router.resolver.TrailerPlaybackRef
import com.nexio.tv.domain.model.HydratedHomeFieldTrace
import com.nexio.tv.domain.model.ProviderIds
import com.nexio.tv.domain.model.TitleRating
import com.nexio.tv.domain.model.TrailerDisplayState

@Immutable
data class IdleScreensaverSlide(
    val itemId: String,
    val itemType: String,
    val addonBaseUrl: String,
    val title: String,
    val backgroundArtwork: ArtworkDisplayRef,
    val logoArtwork: ArtworkDisplayRef?,
    val genres: List<String>,
    val description: String?,
    val releaseInfo: String?,
    val runtime: String?,
    val imdbRating: Float?,
    val tomatoesRating: Double? = null,
    val modeData: IdleScreensaverModeData = IdleScreensaverModeData(
        image = IdleScreensaverImageModeData(fallbackArtwork = listOf(backgroundArtwork))
    )
)

@Immutable
data class IdleScreensaverModeData(
    val image: IdleScreensaverImageModeData = IdleScreensaverImageModeData(),
    val trailer: IdleScreensaverTrailerModeData? = null
)

@Immutable
data class IdleScreensaverImageModeData(
    val fallbackArtwork: List<ArtworkDisplayRef> = emptyList()
)

@Immutable
data class IdleScreensaverTrailerModeData(
    val trailerState: TrailerDisplayState
)

@Immutable
data class IdleTrailerScreensaverCandidate(
    val itemId: String,
    val itemType: String,
    val addonBaseUrl: String,
    val title: String,
    val logoArtwork: ArtworkDisplayRef?,
    val backgroundArtwork: ArtworkDisplayRef,
    val fallbackArtwork: List<ArtworkDisplayRef>,
    val genres: List<String>,
    val description: String?,
    val releaseInfo: String?,
    val runtime: String?,
    val imdbRating: Float?,
    val tomatoesRating: Double? = null,
    val trailerState: TrailerDisplayState,
    val stableIds: ProviderIds = ProviderIds()
) {
    constructor(
        slide: IdleScreensaverSlide,
        trailerState: TrailerDisplayState
    ) : this(
        itemId = slide.itemId,
        itemType = slide.itemType,
        addonBaseUrl = slide.addonBaseUrl,
        title = slide.title,
        logoArtwork = slide.logoArtwork,
        backgroundArtwork = slide.backgroundArtwork,
        fallbackArtwork = slide.modeData.image.fallbackArtwork.ifEmpty { listOf(slide.backgroundArtwork) },
        genres = slide.genres,
        description = slide.description,
        releaseInfo = slide.releaseInfo,
        runtime = slide.runtime,
        imdbRating = slide.imdbRating,
        tomatoesRating = slide.tomatoesRating,
        trailerState = trailerState,
        stableIds = ProviderIds()
    )

    val playbackRefs: List<TrailerPlaybackRef>
        get() = listOfNotNull(trailerState.selectedPlaybackRef).distinct()

    val slide: IdleScreensaverSlide
        get() = IdleScreensaverSlide(
            itemId = itemId,
            itemType = itemType,
            addonBaseUrl = addonBaseUrl,
            title = title,
            backgroundArtwork = backgroundArtwork,
            logoArtwork = logoArtwork,
            genres = genres,
            description = description,
            releaseInfo = releaseInfo,
            runtime = runtime,
            imdbRating = imdbRating,
            tomatoesRating = tomatoesRating,
            modeData = IdleScreensaverModeData(
                image = IdleScreensaverImageModeData(fallbackArtwork = fallbackArtwork),
                trailer = IdleScreensaverTrailerModeData(trailerState = trailerState)
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
    val genres: List<String> = emptyList(),
    val runtime: String? = null,
    val rating: TitleRating?,
    val artwork: ArtworkBundle,
    val preferredImage: ArtworkDisplayRef,
    val stableIds: ProviderIds,
    val trace: List<HydratedHomeFieldTrace>
)

@Immutable
data class ScreensaverTrailerCandidate(
    val itemKey: String,
    val contentId: String,
    val itemType: String,
    val title: String,
    val releaseInfo: String?,
    val overview: String?,
    val genres: List<String> = emptyList(),
    val runtime: String? = null,
    val rating: TitleRating?,
    val artwork: ArtworkBundle,
    val trailerState: TrailerDisplayState,
    val stableIds: ProviderIds
)
