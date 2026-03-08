package com.nexio.tv.ui.screens.player

import android.util.Log
import com.nexio.tv.core.mpv.NexioMpvPlaybackState
import com.nexio.tv.core.mpv.NexioMpvSession
import com.nexio.tv.core.mpv.NexioMpvSubtitleCueState
import com.nexio.tv.data.local.PlayerPreference
import com.nexio.tv.data.local.PlayerSettings
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal fun PlayerRuntimeController.usesLibmpvBackend(): Boolean {
    return playerBackendPreference == PlayerPreference.LIBMPV
}

internal fun PlayerRuntimeController.observeMpvStateIfNeeded() {
    if (!usesLibmpvBackend()) return
    val session = mpvSession ?: return
    if (observedMpvSession === session && mpvStateCollectionJob != null && mpvSubtitleCueCollectionJob != null) {
        return
    }
    mpvStateCollectionJob?.cancel()
    mpvSubtitleCueCollectionJob?.cancel()
    observedMpvSession = session
    mpvStateCollectionJob = scope.launch {
        session.playbackState.collectLatest { state ->
            applyMpvPlaybackState(state)
        }
    }
    mpvSubtitleCueCollectionJob = scope.launch {
        session.subtitleCueState.collectLatest { state ->
            applyMpvSubtitleCueState(state)
        }
    }
}

private fun PlayerRuntimeController.applyMpvPlaybackState(state: NexioMpvPlaybackState) {
    lastKnownDuration = maxOf(lastKnownDuration, state.durationMs)
    if (state.isReady) {
        hasRenderedFirstFrame = true
    }
    if (state.isReady && pendingResumeProgress != null) {
        tryApplyPendingResumeProgressForCurrentBackend()
    }
    _uiState.update { current ->
        current.copy(
            isPlaying = state.isPlaying,
            isBuffering = state.isBuffering,
            playbackEnded = state.playbackEnded,
            currentPosition = pendingPreviewSeekPosition ?: state.currentPositionMs,
            duration = state.durationMs,
            playbackSpeed = state.playbackSpeed,
            subtitleDelayMs = state.subtitleDelayMs,
            audioTracks = state.audioTracks,
            subtitleTracks = state.subtitleTracks,
            selectedAudioTrackIndex = state.selectedAudioTrackIndex,
            selectedSubtitleTrackIndex = if (current.selectedAddonSubtitle == null) {
                state.selectedSubtitleTrackIndex
            } else {
                -1
            },
            error = state.errorMessage ?: current.error,
            showLoadingOverlay = if (state.isReady) false else current.showLoadingOverlay
        )
    }
    maybeApplyRememberedAudioSelection(state.audioTracks)
    maybeRestorePendingAudioSelectionAfterSubtitleRefresh(state.audioTracks)
    tryAutoSelectPreferredSubtitleFromAvailableTracks()
    if (state.errorMessage != null) {
        Log.e(PlayerRuntimeController.TAG, "LIBMPV error=${state.errorMessage}")
    }
}

private fun PlayerRuntimeController.applyMpvSubtitleCueState(state: NexioMpvSubtitleCueState) {
    currentCueGroup = state.cueGroup
    if (_uiState.value.aiSubtitlesEnabled) {
        handleBuiltInCueGroupUpdate()
    }
}

internal fun PlayerRuntimeController.initializeLibmpvPlayer(
    url: String,
    headers: Map<String, String>,
    playerSettings: PlayerSettings
) {
    val session = if (mpvSession?.isReusableForPlayback() == true) {
        mpvSession!!
    } else {
        NexioMpvSession(
            context = context.applicationContext,
            externalScope = scope
        ).also { mpvSession = it }
    }
    observeMpvStateIfNeeded()
    session.configure(playerSettings)
    session.ensureInitialized()
    lastMpvSubtitleVisibility = null
    _uiState.update {
        it.copy(
            frameRateMatchingMode = playerSettings.frameRateMatchingMode,
            playbackSpeed = 1f,
            subtitleDelayMs = 0,
            showLoadingOverlay = true,
            showControls = false,
            isBuffering = true,
            playbackEnded = false,
            error = null,
            useBuiltInAiSubtitleOverlay = false,
            translatedBuiltInCues = emptyList()
        )
    }
    setLibmpvSubtitleVisibility(true)
    session.load(
        url = url,
        headers = headers,
        title = title,
        startPositionMs = pendingResumeProgress?.takeUnless { navigationArgs.startFromBeginning }?.position ?: 0L
    )
    session.play()
    startProgressUpdates()
    startWatchProgressSaving()
    fetchAddonSubtitles()
}

internal fun PlayerRuntimeController.setLibmpvSubtitleVisibility(visible: Boolean) {
    if (!usesLibmpvBackend()) return
    if (lastMpvSubtitleVisibility == visible) return
    lastMpvSubtitleVisibility = visible
    mpvSession?.setSubtitleVisibility(visible)
}

internal fun PlayerRuntimeController.backendCurrentPosition(): Long {
    return if (usesLibmpvBackend()) {
        mpvSession?.playbackState?.value?.currentPositionMs ?: 0L
    } else {
        _exoPlayer?.currentPosition ?: 0L
    }
}

internal fun PlayerRuntimeController.backendDuration(): Long {
    return if (usesLibmpvBackend()) {
        mpvSession?.playbackState?.value?.durationMs ?: 0L
    } else {
        _exoPlayer?.duration ?: 0L
    }
}

internal fun PlayerRuntimeController.backendIsReady(): Boolean {
    return if (usesLibmpvBackend()) {
        mpvSession?.playbackState?.value?.isReady == true
    } else {
        _exoPlayer?.playbackState == androidx.media3.common.Player.STATE_READY
    }
}

internal fun PlayerRuntimeController.backendIsPlaying(): Boolean {
    return if (usesLibmpvBackend()) {
        mpvSession?.playbackState?.value?.isPlaying == true
    } else {
        _exoPlayer?.isPlaying == true
    }
}

internal fun PlayerRuntimeController.backendPause() {
    if (usesLibmpvBackend()) {
        mpvSession?.pause()
    } else {
        _exoPlayer?.pause()
    }
}

internal fun PlayerRuntimeController.backendPlay() {
    if (usesLibmpvBackend()) {
        mpvSession?.play()
    } else {
        _exoPlayer?.play()
    }
}

internal fun PlayerRuntimeController.backendStop() {
    if (usesLibmpvBackend()) {
        mpvSession?.stop()
    } else {
        _exoPlayer?.stop()
    }
}

internal fun PlayerRuntimeController.backendSeekTo(positionMs: Long) {
    if (usesLibmpvBackend()) {
        mpvSession?.seekTo(positionMs)
    } else {
        _exoPlayer?.seekTo(positionMs)
    }
}

internal fun PlayerRuntimeController.backendSetPlaybackSpeed(speed: Float) {
    if (usesLibmpvBackend()) {
        mpvSession?.setPlaybackSpeed(speed)
    } else {
        _exoPlayer?.setPlaybackSpeed(speed)
    }
}

internal fun PlayerRuntimeController.backendSetSubtitleDelay(delayMs: Int) {
    if (usesLibmpvBackend()) {
        mpvSession?.setSubtitleDelay(delayMs)
    }
}

internal fun PlayerRuntimeController.tryApplyPendingResumeProgressForCurrentBackend() {
    val saved = pendingResumeProgress ?: return
    val duration = backendDuration()
    val target = when {
        duration > 0L -> saved.resolveResumePosition(duration)
        saved.position > 0L -> saved.position
        else -> 0L
    }

    if (target > 0L) {
        backendSeekTo(target)
        _uiState.update { it.copy(pendingSeekPosition = null) }
    }
    pendingResumeProgress = null
}

internal fun PlayerRuntimeController.pausePlaybackForLifecycle() {
    if (backendIsPlaying()) {
        backendPause()
    }
}
