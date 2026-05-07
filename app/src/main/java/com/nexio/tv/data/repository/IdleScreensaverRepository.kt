package com.nexio.tv.data.repository

import android.util.Log
import com.nexio.tv.core.artwork.ArtworkDisplayRef
import com.nexio.tv.core.artwork.ArtworkTrace
import com.nexio.tv.core.artwork.ArtworkType
import com.nexio.tv.core.artwork.PlaceholderType
import com.nexio.tv.core.profile.ProfileManager
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
    private val screensaverCandidateRepository: ScreensaverCandidateRepository,
    private val activeProfileId: () -> Int
) {
    @Inject
    constructor(
        screensaverCandidateRepository: ScreensaverCandidateRepository,
        profileManager: ProfileManager
    ) : this(
        screensaverCandidateRepository = screensaverCandidateRepository,
        activeProfileId = { profileManager.activeProfileId.value }
    )

    private val refreshMutex = Mutex()
    private val _slides = MutableStateFlow<List<IdleScreensaverSlide>>(emptyList())
    val slides = _slides.asStateFlow()
    private val _trailerCandidates = MutableStateFlow<List<IdleTrailerScreensaverCandidate>>(emptyList())
    val trailerCandidates = _trailerCandidates.asStateFlow()

    suspend fun warmFromCache() {
        refreshFromResolvedSurface("Warm cache prepared")
    }

    suspend fun refreshOnColdBoot() {
        refreshFromResolvedSurface("Prepared")
    }

    suspend fun observeResolvedSurface(profileId: Int = activeProfileId()) {
        screensaverCandidateRepository.observeCandidates(profileId).collect { snapshot ->
            publishSnapshot(snapshot, "Observed")
        }
    }

    private suspend fun refreshFromResolvedSurface(logPrefix: String) {
        val profileId = activeProfileId()
        publishSnapshot(
            snapshot = screensaverCandidateRepository.getCandidatesSnapshot(profileId),
            logPrefix = logPrefix
        )
    }

    private suspend fun publishSnapshot(
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
}
