package com.nexio.tv.ui.screens.player

import android.os.LocaleList
import android.util.Log
import com.nexio.tv.core.mpv.NexioMpvSubtitleCueState
import com.nexio.tv.core.mpv.NexioMpvSurfaceView
import com.nexio.tv.data.local.PlayerPreference
import com.nexio.tv.data.local.PlayerSettings
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal fun PlayerRuntimeController.usesLibmpvBackend(): Boolean {
    return playerBackendPreference == PlayerPreference.LIBMPV
}

internal fun PlayerRuntimeController.attachMpvView(view: NexioMpvSurfaceView?) {
    if (mpvView === view) return
    mpvView = view

    if (view == null) return
    if (!usesLibmpvBackend()) return
    if (mpvInitializationInProgress) return
    val currentSettings = lastLibmpvPlayerSettings
    if (currentSettings == null) return
    view.configure(currentSettings)
    if (currentStreamUrl.isBlank()) return

    runCatching {
        val preferredAudioLanguages = resolveLibmpvPreferredAudioLanguages()
        view.setMedia(
            url = currentStreamUrl,
            headers = currentHeaders,
            streamName = _uiState.value.currentStreamName ?: title,
            filename = currentFilename
        )
        view.setPlaybackSpeed(_uiState.value.playbackSpeed)
        view.applyAudioLanguagePreferences(preferredAudioLanguages)
        view.applySubtitleLanguagePreferences(
            preferred = _uiState.value.subtitleStyle.preferredLanguage,
            secondary = _uiState.value.subtitleStyle.secondaryPreferredLanguage
        )
        view.applySubtitleStyle(_uiState.value.subtitleStyle)
        view.setSubtitleDelayMs(_uiState.value.subtitleDelayMs)
        view.setPaused(false)
        hasRenderedFirstFrame = false
        _uiState.update {
            it.copy(
                isBuffering = true,
                isPlaying = view.isPlayingNow(),
                showLoadingOverlay = it.loadingOverlayEnabled,
                error = null
            )
        }
        cancelPauseOverlay()
        startProgressUpdates()
        startWatchProgressSaving()
        updateMpvAvailableTracks()
        tryAutoSelectPreferredSubtitleFromAvailableTracks()
        scheduleHideControls()
        emitScrobbleStart()
    }.onFailure {
        _uiState.update { state ->
            state.copy(
                error = it.message ?: "Failed to initialize libmpv surface",
                showLoadingOverlay = false
            )
        }
    }
}

internal fun PlayerRuntimeController.observeMpvSubtitleCuesIfNeeded() {
    if (!usesLibmpvBackend()) return
    val view = mpvView ?: return
    if (observedMpvView === view && mpvSubtitleCueCollectionJob != null) {
        return
    }
    mpvSubtitleCueCollectionJob?.cancel()
    observedMpvView = view
    mpvSubtitleCueCollectionJob = scope.launch {
        view.subtitleCueState.collectLatest { state ->
            applyMpvSubtitleCueState(state)
        }
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
    Log.i(
        PlayerRuntimeController.TAG,
        "LIBMPV init: videoOutput=${playerSettings.libmpvVideoOutputMode} " +
            "dvReshape=${playerSettings.libmpvGpuNextDolbyVisionReshapingEnabled} " +
            "audioPassthrough=${playerSettings.libmpvAudioPassthroughEnabled}"
    )
    val view = mpvView
    if (view == null) {
        _uiState.update {
            it.copy(
                isBuffering = true,
                isPlaying = false,
                showLoadingOverlay = it.loadingOverlayEnabled,
                error = null
            )
        }
        return
    }

    lastLibmpvPlayerSettings = playerSettings
    view.configure(playerSettings)
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
            audioTracks = emptyList(),
            subtitleTracks = emptyList(),
            selectedAudioTrackIndex = -1,
            selectedSubtitleTrackIndex = -1,
            useBuiltInAiSubtitleOverlay = false,
            translatedBuiltInCues = emptyList()
        )
    }

    setLibmpvSubtitleVisibility(true)
    val preferredAudioLanguages = resolveLibmpvPreferredAudioLanguages()
    view.setMedia(
        url = url,
        headers = headers,
        streamName = _uiState.value.currentStreamName ?: title,
        filename = currentFilename
    )
    view.setPlaybackSpeed(_uiState.value.playbackSpeed)
    view.applyAudioLanguagePreferences(preferredAudioLanguages)
    view.applySubtitleLanguagePreferences(
        preferred = _uiState.value.subtitleStyle.preferredLanguage,
        secondary = _uiState.value.subtitleStyle.secondaryPreferredLanguage
    )
    view.applySubtitleStyle(_uiState.value.subtitleStyle)
    view.setSubtitleDelayMs(_uiState.value.subtitleDelayMs)
    view.setPaused(false)
    hasRenderedFirstFrame = false
    cancelPauseOverlay()
    startProgressUpdates()
    startWatchProgressSaving()
    updateMpvAvailableTracks()
    tryAutoSelectPreferredSubtitleFromAvailableTracks()
    scheduleHideControls()
    emitScrobbleStart()
    fetchAddonSubtitles()
}

private fun PlayerRuntimeController.resolveLibmpvPreferredAudioLanguages(): List<String> {
    val settings = lastLibmpvPlayerSettings ?: return emptyList()
    val localeList = LocaleList.getDefault()
    val deviceLanguages = List(localeList.size()) { localeList[it].isO3Language }
    return resolvePreferredAudioLanguages(
        preferredAudioLanguage = settings.preferredAudioLanguage,
        secondaryPreferredAudioLanguage = settings.secondaryPreferredAudioLanguage,
        deviceLanguages = deviceLanguages
    )
}

internal fun PlayerRuntimeController.setLibmpvSubtitleVisibility(visible: Boolean) {
    if (!usesLibmpvBackend()) return
    if (lastMpvSubtitleVisibility == visible) return
    lastMpvSubtitleVisibility = visible
    mpvView?.setSubtitleVisibility(visible)
}

internal fun PlayerRuntimeController.backendCurrentPosition(): Long {
    return if (usesLibmpvBackend()) {
        mpvView?.currentPositionMs() ?: 0L
    } else {
        _exoPlayer?.currentPosition ?: 0L
    }
}

internal fun PlayerRuntimeController.backendDuration(): Long {
    return if (usesLibmpvBackend()) {
        mpvView?.durationMs() ?: 0L
    } else {
        _exoPlayer?.duration ?: 0L
    }
}

internal fun PlayerRuntimeController.backendIsReady(): Boolean {
    return if (usesLibmpvBackend()) {
        hasRenderedFirstFrame
    } else {
        _exoPlayer?.playbackState == androidx.media3.common.Player.STATE_READY
    }
}

internal fun PlayerRuntimeController.backendIsPlaying(): Boolean {
    return if (usesLibmpvBackend()) {
        mpvView?.isPlayingNow() == true
    } else {
        _exoPlayer?.isPlaying == true
    }
}

internal fun PlayerRuntimeController.backendPause() {
    if (usesLibmpvBackend()) {
        mpvView?.setPaused(true)
    } else {
        _exoPlayer?.pause()
    }
}

internal fun PlayerRuntimeController.backendPlay() {
    if (usesLibmpvBackend()) {
        mpvView?.setPaused(false)
    } else {
        _exoPlayer?.play()
    }
}

internal fun PlayerRuntimeController.backendStop() {
    if (usesLibmpvBackend()) {
        mpvView?.stopPlayback()
    } else {
        _exoPlayer?.stop()
    }
}

internal fun PlayerRuntimeController.backendSeekTo(positionMs: Long) {
    if (usesLibmpvBackend()) {
        mpvView?.seekToMs(positionMs)
    } else {
        _exoPlayer?.seekTo(positionMs)
    }
}

internal fun PlayerRuntimeController.backendSetPlaybackSpeed(speed: Float) {
    if (usesLibmpvBackend()) {
        mpvView?.setPlaybackSpeed(speed)
    } else {
        _exoPlayer?.setPlaybackSpeed(speed)
    }
}

internal fun PlayerRuntimeController.backendSetSubtitleDelay(delayMs: Int) {
    if (usesLibmpvBackend()) {
        mpvView?.setSubtitleDelayMs(delayMs)
    } else {
        subtitleDelayUs.set(delayMs * 1000L)
        _exoPlayer?.let { player ->
            player.setTrackSelectionParameters(
                player.trackSelectionParameters
                    .buildUpon()
                    .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, false)
                    .build()
            )
        }
    }
}

internal fun PlayerRuntimeController.releaseMpvPlayer() {
    runCatching { mpvView?.releasePlayer() }
}

internal fun PlayerRuntimeController.pausePlaybackForLifecycle() {
    if (usesLibmpvBackend()) {
        mpvView?.setPaused(true)
        stopWatchProgressSaving()
        stopProgressUpdates()
        _uiState.update { it.copy(isPlaying = false) }
        return
    }
    _exoPlayer?.pause()
}

internal fun PlayerRuntimeController.updateMpvAvailableTracks() {
    if (!usesLibmpvBackend()) return
    val snapshot = mpvView?.readTrackSnapshot() ?: return

    val audioTracks = snapshot.audioTracks
        .mapIndexed { index, track ->
            val codecSuffix = buildList {
                track.codec?.takeIf { it.isNotBlank() }?.let { add(it) }
                track.channelCount?.takeIf { it > 0 }?.let { add("${it}ch") }
            }.joinToString(" ")
            val displayName = if (codecSuffix.isBlank()) {
                track.name
            } else {
                "${track.name} ($codecSuffix)"
            }
            TrackInfo(
                index = index,
                name = displayName,
                language = track.language,
                trackId = track.id.toString(),
                codec = track.codec,
                channelCount = track.channelCount,
                isSelected = track.isSelected
            )
        }

    val internalSubtitleTracks = snapshot.subtitleTracks
        .filterNot { it.isExternal }
        .mapIndexed { index, track ->
            TrackInfo(
                index = index,
                name = track.name,
                language = track.language,
                trackId = track.id.toString(),
                codec = track.codec,
                isForced = track.isForced,
                isSelected = track.isSelected
            )
        }

    val selectedAudioIndex = audioTracks.indexOfFirst { it.isSelected }
    val selectedSubtitleIndex = internalSubtitleTracks.indexOfFirst { it.isSelected }
    val selectedExternalSubtitleTrack = snapshot.subtitleTracks.firstOrNull { it.isExternal && it.isSelected }
    val selectedExternalSubtitle = selectedExternalSubtitleTrack != null

    hasScannedTextTracksOnce = true
    maybeApplyRememberedAudioSelection(audioTracks)
    maybeRestorePendingAudioSelectionAfterSubtitleRefresh(audioTracks)

    _uiState.update { state ->
        val selectedAddonFromMpvTrack = selectedExternalSubtitleTrack?.let { track ->
            state.addonSubtitles.firstOrNull { subtitle ->
                buildAddonSubtitleTrackId(subtitle).equals(track.name, ignoreCase = true)
            }
        }

        val addonSelection = when {
            selectedAddonFromMpvTrack != null -> selectedAddonFromMpvTrack
            selectedExternalSubtitle -> null
            selectedSubtitleIndex >= 0 -> null
            else -> state.selectedAddonSubtitle
        }
        val normalizedSelectedSubtitleIndex = if (selectedExternalSubtitle) {
            -1
        } else {
            selectedSubtitleIndex
        }

        if (
            state.audioTracks == audioTracks &&
            state.subtitleTracks == internalSubtitleTracks &&
            state.selectedAudioTrackIndex == selectedAudioIndex &&
            state.selectedSubtitleTrackIndex == normalizedSelectedSubtitleIndex &&
            state.selectedAddonSubtitle == addonSelection
        ) {
            state
        } else {
            state.copy(
                audioTracks = audioTracks,
                subtitleTracks = internalSubtitleTracks,
                selectedAudioTrackIndex = selectedAudioIndex,
                selectedSubtitleTrackIndex = normalizedSelectedSubtitleIndex,
                selectedAddonSubtitle = addonSelection
            )
        }
    }
}

internal fun PlayerRuntimeController.isPlaybackCurrentlyPlaying(): Boolean {
    return if (usesLibmpvBackend()) {
        mpvView?.isPlayingNow() == true
    } else {
        _exoPlayer?.isPlaying == true
    }
}

internal fun PlayerRuntimeController.seekPlaybackTo(positionMs: Long) {
    if (usesLibmpvBackend()) {
        mpvView?.let { view ->
            view.seekToMs(positionMs)
            view.setSubtitleDelayMs(_uiState.value.subtitleDelayMs)
        }
    } else {
        _exoPlayer?.seekTo(positionMs)
    }
}

internal fun PlayerRuntimeController.setPlaybackSpeedInternal(speed: Float) {
    if (usesLibmpvBackend()) {
        mpvView?.setPlaybackSpeed(speed)
    } else {
        _exoPlayer?.setPlaybackSpeed(speed)
    }
}

internal fun PlayerRuntimeController.setPlaybackPaused(paused: Boolean) {
    if (usesLibmpvBackend()) {
        mpvView?.setPaused(paused)
        _uiState.update { it.copy(isPlaying = !paused) }
    } else {
        _exoPlayer?.let { player ->
            if (paused) player.pause() else player.play()
        }
    }
}

internal fun PlayerRuntimeController.keepMpvPlayingIfNeeded(wasPlaying: Boolean) {
    if (!wasPlaying || !usesLibmpvBackend()) return
    scope.launch {
        repeat(6) {
            if (!usesLibmpvBackend()) return@launch
            val view = mpvView ?: return@launch
            val pausedByCache = view.isPausedForCacheNow()
            val coreIdle = view.isCoreIdleNow()
            if (view.isPlayingNow() && !pausedByCache && !coreIdle) {
                _uiState.update { state ->
                    if (state.isPlaying) state else state.copy(isPlaying = true, isBuffering = false)
                }
                return@launch
            }
            view.setPaused(false)
            _uiState.update { it.copy(isPlaying = true, isBuffering = false) }
            kotlinx.coroutines.delay(120L)
        }
    }
}

internal fun PlayerRuntimeController.tryApplyPendingResumeProgressForCurrentBackend() {
    val saved = pendingResumeProgress ?: return
    val view = mpvView ?: return
    val duration = view.durationMs()
    val target = when {
        duration > 0L -> saved.resolveResumePosition(duration)
        saved.position > 0L -> saved.position
        else -> 0L
    }
    if (target > 0L) {
        view.seekToMs(target)
        _uiState.update { it.copy(pendingSeekPosition = null) }
        pendingResumeProgress = null
    }
}

internal fun PlayerRuntimeController.tryApplyPendingLibmpvStartupSeek(
    playerDuration: Long,
    playingNow: Boolean,
    cacheBuffering: Boolean
) {
    if (!usesLibmpvBackend()) return
    val view = mpvView ?: return
    val requestedSeek = _uiState.value.pendingSeekPosition ?: pendingResumeProgress?.position ?: return
    if (requestedSeek <= 0L) return

    val coreReadyForSeek = playerDuration > 0L || (playingNow && !cacheBuffering && !view.isCoreIdleNow())
    if (!coreReadyForSeek) return

    val target = pendingResumeProgress?.let { saved ->
        if (playerDuration > 0L) saved.resolveResumePosition(playerDuration) else saved.position
    } ?: requestedSeek
    if (target <= 0L) return

    view.seekToMs(target)
    _uiState.update { it.copy(pendingSeekPosition = null) }
    pendingResumeProgress = null
}
