package com.nexio.tv.data.repository

import android.util.Log
import com.nexio.tv.core.artwork.ArtworkDisplayRef
import com.nexio.tv.core.artwork.ArtworkTrace
import com.nexio.tv.core.artwork.ArtworkType
import com.nexio.tv.core.artwork.PlaceholderType
import com.nexio.tv.core.profile.ProfileManager
import com.nexio.tv.domain.model.ProviderIds
import com.nexio.tv.domain.model.RatingValueValidator
import com.nexio.tv.domain.model.ResolvedDisplayItem
import com.nexio.tv.ui.screensaver.IdleScreensaverDisplayItem
import com.nexio.tv.ui.screensaver.IdleScreensaverImageModeData
import com.nexio.tv.ui.screensaver.IdleScreensaverModeData
import com.nexio.tv.ui.screensaver.IdleScreensaverSlide
import com.nexio.tv.ui.screensaver.IdleTrailerScreensaverCandidate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val TAG = "IdleScreensaverRepo"
private const val PLACEHOLDER_ITEM_ID = "__placeholder__"
private val PLACEHOLDER_BACKDROP_ARTWORK = ArtworkDisplayRef.Placeholder(
    placeholderType = PlaceholderType.BACKDROP,
    imageType = ArtworkType.BACKDROP,
    trace = ArtworkTrace.empty()
)

private val EMPTY_SURFACE_PLACEHOLDER_SLIDE = IdleScreensaverSlide(
    itemId = PLACEHOLDER_ITEM_ID,
    itemType = "placeholder",
    addonBaseUrl = "",
    title = "",
    backgroundArtwork = PLACEHOLDER_BACKDROP_ARTWORK,
    logoArtwork = null,
    genres = emptyList(),
    description = null,
    releaseInfo = null,
    runtime = null,
    imdbRating = null,
    tomatoesRating = null,
    modeData = IdleScreensaverModeData(
        image = IdleScreensaverImageModeData(fallbackArtwork = listOf(PLACEHOLDER_BACKDROP_ARTWORK))
    )
)

@Singleton
class IdleScreensaverRepository(
    @Suppress("unused")
    private val screensaverCandidateRepository: ScreensaverCandidateRepository,
    private val surfaceRepository: ResolvedDisplaySurfaceRepository,
    private val activeProfileId: () -> Int
) {
    @Inject
    constructor(
        screensaverCandidateRepository: ScreensaverCandidateRepository,
        surfaceRepository: ResolvedDisplaySurfaceRepository,
        profileManager: ProfileManager
    ) : this(
        screensaverCandidateRepository = screensaverCandidateRepository,
        surfaceRepository = surfaceRepository,
        activeProfileId = { profileManager.activeProfileId.value }
    )

    private val refreshMutex = Mutex()
    private val _slides = MutableStateFlow<List<IdleScreensaverSlide>>(emptyList())
    val slides = _slides.asStateFlow()
    private val _trailerCandidates = MutableStateFlow<List<IdleTrailerScreensaverCandidate>>(emptyList())
    val trailerCandidates = _trailerCandidates.asStateFlow()

    /**
     * Per-item screensaver projection sourced directly from
     * [ResolvedDisplaySurfaceRepository.observeScreensaverSurface]. Each item carries
     * its own [TrailerDisplayState] so consumers can pick image vs trailer mode
     * without consulting a parallel `_trailerCandidates` flow. Plan B Task 14.
     *
     * Plan B Task 15 (adapter-bridge): this is now the SOLE upstream input. Both
     * legacy [slides] and [trailerCandidates] flows are derived from this list via
     * adapter functions ([toIdleScreensaverSlide] / [toIdleTrailerScreensaverCandidate]).
     * As a result the three flows are guaranteed coherent within an emission and
     * `ScreensaverCandidateRepository` is no longer consulted (param is retained
     * for the Hilt graph; Task 26 cleanup will remove it after surfaces accept the
     * new flow shape).
     */
    private val _screensaverDisplayItems = MutableStateFlow<List<IdleScreensaverDisplayItem>>(emptyList())
    val screensaverDisplayItems = _screensaverDisplayItems.asStateFlow()

    suspend fun warmFromCache() {
        refreshFromResolvedSurface("Warm cache prepared")
    }

    suspend fun refreshOnColdBoot() {
        refreshFromResolvedSurface("Prepared")
    }

    suspend fun observeResolvedSurface(profileId: Int = activeProfileId()) {
        // Single upstream collector. From each emission of resolved screensaver items
        // we publish all three flows (display items + adapter-derived legacy slides +
        // trailer candidates) under one mutex hold so consumers always see coherent
        // state within an emission window.
        surfaceRepository.observeScreensaverSurface(profileId).collect { items ->
            publishResolvedItems(items, "Observed")
        }
    }

    private suspend fun refreshFromResolvedSurface(logPrefix: String) {
        val profileId = activeProfileId()
        publishResolvedItems(
            items = surfaceRepository.getSnapshot(
                ResolvedDisplaySurfaceRepository.SCREENSAVER_SURFACE_KEY,
                profileId
            ),
            logPrefix = logPrefix
        )
    }

    private suspend fun publishResolvedItems(
        items: List<ResolvedDisplayItem>,
        logPrefix: String = "Observed"
    ) {
        refreshMutex.withLock {
            // Filter for items the screensaver can actually render: title + at least
            // one displayable artwork (matches ScreensaverCandidateRepository's gate).
            // Indexed-for to avoid suspending Iterable allocations (CLAUDE.md rule #4).
            val displayItems = ArrayList<IdleScreensaverDisplayItem>(items.size)
            for (i in items.indices) {
                val item = items[i]
                if (item.display.title.isNullOrBlank()) continue
                if (item.artwork.backdrop == null && item.artwork.poster == null) continue
                displayItems += IdleScreensaverDisplayItem.from(item)
            }

            val imageSlides = ArrayList<IdleScreensaverSlide>(displayItems.size)
            val trailerCandidates = ArrayList<IdleTrailerScreensaverCandidate>(displayItems.size)
            for (i in displayItems.indices) {
                val displayItem = displayItems[i]
                displayItem.toIdleScreensaverSlide()?.let(imageSlides::add)
                displayItem.toIdleTrailerScreensaverCandidate()?.let(trailerCandidates::add)
            }

            _screensaverDisplayItems.value = displayItems
            _slides.value = imageSlides.ifEmpty { listOf(EMPTY_SURFACE_PLACEHOLDER_SLIDE) }
            _trailerCandidates.value = trailerCandidates
            Log.d(
                TAG,
                "$logPrefix ${_slides.value.size} idle screensaver slides and " +
                    "${_trailerCandidates.value.size} trailer candidates from resolved display surface"
            )
        }
    }
}

/**
 * Adapt a [IdleScreensaverDisplayItem] to the legacy [IdleScreensaverSlide] shape
 * consumed by `IdleScreensaverOverlay`. Returns null when the item lacks a non-blank
 * title or any displayable background artwork.
 *
 * `backgroundRef` already enforces `backdrop ?: poster` (see [IdleScreensaverDisplayItem.from]),
 * so a single-element fallback list is content-equivalent to the previous
 * `listOfNotNull(preferredImage, artwork.backdrop, artwork.poster).distinct()` pipeline.
 */
internal fun IdleScreensaverDisplayItem.toIdleScreensaverSlide(): IdleScreensaverSlide? {
    val resolvedTitle = title?.takeIf { it.isNotBlank() } ?: return null
    val background = backgroundRef ?: return null
    val fallbackArtwork = listOf(background)
    return IdleScreensaverSlide(
        itemId = contentId,
        itemType = itemType,
        addonBaseUrl = "",
        title = resolvedTitle,
        backgroundArtwork = background,
        logoArtwork = logoRef,
        genres = genres,
        description = overview?.takeIf { it.isNotBlank() },
        releaseInfo = releaseInfo?.takeIf { it.isNotBlank() },
        runtime = runtime?.takeIf { it.isNotBlank() },
        imdbRating = rating?.value
            ?.takeIf { RatingValueValidator.validTitleRating(it) }
            ?.toFloat(),
        tomatoesRating = null,
        modeData = IdleScreensaverModeData(
            image = IdleScreensaverImageModeData(fallbackArtwork = fallbackArtwork)
        )
    )
}

/**
 * Adapt a [IdleScreensaverDisplayItem] to the legacy [IdleTrailerScreensaverCandidate]
 * shape consumed by `IdleTrailerScreensaverOverlay` /
 * `IdleTrailerScreensaverSession`. Returns null when the item lacks a non-blank title
 * or any displayable background artwork (same gate as the slide adapter).
 *
 * `stableIds` is intentionally [ProviderIds] empty — the prior
 * `ScreensaverCandidateRepository` pipeline left these blank as well.
 */
internal fun IdleScreensaverDisplayItem.toIdleTrailerScreensaverCandidate(): IdleTrailerScreensaverCandidate? {
    val resolvedTitle = title?.takeIf { it.isNotBlank() } ?: return null
    val background = backgroundRef ?: return null
    val fallbackArtwork = listOf(background)
    return IdleTrailerScreensaverCandidate(
        itemId = contentId,
        itemType = itemType,
        addonBaseUrl = "",
        title = resolvedTitle,
        logoArtwork = logoRef,
        backgroundArtwork = background,
        fallbackArtwork = fallbackArtwork,
        genres = genres,
        description = overview?.takeIf { it.isNotBlank() },
        releaseInfo = releaseInfo?.takeIf { it.isNotBlank() },
        runtime = runtime?.takeIf { it.isNotBlank() },
        imdbRating = rating?.value
            ?.takeIf { RatingValueValidator.validTitleRating(it) }
            ?.toFloat(),
        tomatoesRating = null,
        trailerState = trailer,
        stableIds = ProviderIds()
    )
}
