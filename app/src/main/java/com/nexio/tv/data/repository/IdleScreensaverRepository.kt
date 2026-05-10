package com.nexio.tv.data.repository

import android.util.Log
import com.nexio.tv.core.artwork.ArtworkDisplayRef
import com.nexio.tv.core.artwork.ArtworkTrace
import com.nexio.tv.core.artwork.ArtworkType
import com.nexio.tv.core.artwork.PlaceholderType
import com.nexio.tv.core.profile.ProfileManager
import com.nexio.tv.domain.model.ResolvedDisplayItem
import com.nexio.tv.ui.screensaver.IdleScreensaverDisplayItem
import com.nexio.tv.ui.screensaver.IdleScreensaverImageModeData
import com.nexio.tv.ui.screensaver.IdleScreensaverModeData
import com.nexio.tv.ui.screensaver.IdleScreensaverSlide
import com.nexio.tv.ui.screensaver.IdleTrailerScreensaverCandidate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
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
     * **Coherence semantics:** [ResolvedDisplaySurfaceRepository.surfaces] is a
     * hot `MutableStateFlow`, so both this projection and the legacy
     * [slides]/[trailerCandidates] flows observe the same emission *value*. They
     * are still populated by two independent collectors below, so brief
     * intra-emission interleaving is possible (one StateFlow can update before
     * the other within a single dispatcher tick). Consumers that need lockstep
     * image+trailer state should treat them as eventually-coherent within an
     * emission window. Plan B Task 15 retires the legacy flows so this
     * coherence concern is structural-only and goes away with cleanup.
     */
    private val _screensaverDisplayItems = MutableStateFlow<List<IdleScreensaverDisplayItem>>(emptyList())
    val screensaverDisplayItems = _screensaverDisplayItems.asStateFlow()

    suspend fun warmFromCache() {
        refreshFromResolvedSurface("Warm cache prepared")
    }

    suspend fun refreshOnColdBoot() {
        refreshFromResolvedSurface("Prepared")
    }

    suspend fun observeResolvedSurface(profileId: Int = activeProfileId()) = coroutineScope {
        // Observe both the projected candidates snapshot (legacy slides/trailerCandidates
        // path) and the raw surface (new screensaverDisplayItems path) in parallel.
        // Both paths read from ResolvedDisplaySurfaceRepository so they tick together.
        launch {
            screensaverCandidateRepository.observeCandidates(profileId).collect { snapshot ->
                publishCandidatesSnapshot(snapshot, "Observed")
            }
        }
        launch {
            surfaceRepository.observeScreensaverSurface(profileId).collect { items ->
                publishResolvedItems(items)
            }
        }
    }

    private suspend fun refreshFromResolvedSurface(logPrefix: String) {
        val profileId = activeProfileId()
        publishCandidatesSnapshot(
            snapshot = screensaverCandidateRepository.getCandidatesSnapshot(profileId),
            logPrefix = logPrefix
        )
        publishResolvedItems(
            items = surfaceRepository.getSnapshot(
                ResolvedDisplaySurfaceRepository.SCREENSAVER_SURFACE_KEY,
                profileId
            )
        )
    }

    private suspend fun publishCandidatesSnapshot(
        snapshot: ScreensaverCandidatesSnapshot,
        logPrefix: String
    ) {
        refreshMutex.withLock {
            val imageSlides = snapshot.imageCandidates.mapNotNull { candidate -> candidate.toIdleScreensaverSlide() }
            val trailerCandidates = snapshot.trailerCandidates.mapNotNull { candidate ->
                candidate.toIdleTrailerScreensaverCandidate()
            }
            _slides.value = imageSlides.ifEmpty { listOf(EMPTY_SURFACE_PLACEHOLDER_SLIDE) }
            _trailerCandidates.value = trailerCandidates
            Log.d(
                TAG,
                "$logPrefix ${_slides.value.size} idle screensaver slides and " +
                    "${_trailerCandidates.value.size} trailer candidates from resolved display surface"
            )
        }
    }

    private suspend fun publishResolvedItems(items: List<ResolvedDisplayItem>) {
        refreshMutex.withLock {
            // Filter for items the screensaver can actually render: title + at least
            // one displayable artwork (matches ScreensaverCandidateRepository's gate).
            val out = ArrayList<IdleScreensaverDisplayItem>(items.size)
            for (i in items.indices) {
                val item = items[i]
                if (item.display.title.isNullOrBlank()) continue
                if (item.artwork.backdrop == null && item.artwork.poster == null) continue
                out += IdleScreensaverDisplayItem.from(item)
            }
            _screensaverDisplayItems.value = out
        }
    }
}
