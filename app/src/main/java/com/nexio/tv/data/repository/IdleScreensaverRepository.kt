package com.nexio.tv.data.repository

import android.util.Log
import com.nexio.tv.core.profile.ProfileManager
import com.nexio.tv.ui.screensaver.IdleScreensaverSlide
import com.nexio.tv.ui.screensaver.IdleTrailerScreensaverCandidate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val TAG = "IdleScreensaverRepo"

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

    private suspend fun refreshFromResolvedSurface(logPrefix: String) {
        refreshMutex.withLock {
            val profileId = activeProfileId()
            val snapshot = screensaverCandidateRepository.getCandidatesSnapshot(profileId)
            _slides.value = snapshot.imageCandidates.mapNotNull { candidate -> candidate.toIdleScreensaverSlide() }
            _trailerCandidates.value = snapshot.trailerCandidates.mapNotNull { candidate ->
                candidate.toIdleTrailerScreensaverCandidate()
            }
            Log.d(
                TAG,
                "$logPrefix ${_slides.value.size} idle screensaver slides and " +
                    "${_trailerCandidates.value.size} trailer candidates from resolved display surface"
            )
        }
    }
}
