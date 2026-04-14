package com.nexio.tv.ui.screens.player

import com.nexio.tv.data.local.InternalPlayerEngine
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.abs

private const val MPV_RESUME_SEEK_TOLERANCE_MS = 1500L

internal fun PlayerRuntimeController.isUsingMpvEngine(): Boolean {
    return currentInternalPlayerEngine == InternalPlayerEngine.LIBMPV
}

internal fun PlayerRuntimeController.attachMpvView(view: NexioMpvSurfaceView?) {
    if (mpvView === view) return
    mpvView = view

    if (view == null) return
    if (!isUsingMpvEngine()) return
    if (currentStreamUrl.isBlank()) return
    if (mpvInitializationInProgress) return

    initializeMpvPlayer(currentStreamUrl, currentHeaders)
}

internal fun PlayerRuntimeController.initializeMpvPlayer(
    url: String,
    headers: Map<String, String>
) {
    _exoPlayer?.release()
    _exoPlayer = null
    trackSelector = null
    runCatching { currentMediaSession?.release() }
    currentMediaSession = null

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

    runCatching {
        view.applyHardwareDecodeMode(mpvHardwareDecodeModeSetting)
        view.setMedia(url, headers)
        view.setPlaybackSpeed(_uiState.value.playbackSpeed)
        view.applyAudioLanguagePreferences(mpvPreferredAudioLanguages)
        view.applySubtitleLanguagePreferences(
            preferred = _uiState.value.subtitleStyle.preferredLanguage,
            secondary = _uiState.value.subtitleStyle.secondaryPreferredLanguage
        )
        view.applySubtitleStyle(_uiState.value.subtitleStyle)
        view.applyResizeMode(_uiState.value.resizeMode)
        view.setSubtitleDelayMs(_uiState.value.subtitleDelayMs)
        view.setPaused(false)
        applyPendingMpvSeekIfNeeded(view)
        hasRenderedFirstFrame = false
        _uiState.update {
            it.copy(
                isBuffering = true,
                isPlaying = view.isPlayingNow(),
                showLoadingOverlay = it.loadingOverlayEnabled,
                error = null,
                audioTracks = emptyList(),
                subtitleTracks = emptyList(),
                selectedAudioTrackIndex = -1,
                selectedSubtitleTrackIndex = -1
            )
        }
        cancelPauseOverlay()
        startProgressUpdates()
        startWatchProgressSaving()
        updateMpvAvailableTracks()
        tryAutoSelectPreferredSubtitleFromAvailableTracks()
        scheduleHideControls()
        emitScrobbleStart()
    }.onFailure { error ->
        _uiState.update {
            it.copy(
                error = error.message ?: "Failed to initialize libmpv playback",
                showLoadingOverlay = false,
                isBuffering = false
            )
        }
    }
}

internal fun PlayerRuntimeController.updateMpvAvailableTracks() {
    if (!isUsingMpvEngine()) return
    if (mpvTrackRefreshInProgress) return
    mpvTrackRefreshInProgress = true
    try {
        val snapshot = mpvView?.readTrackSnapshot() ?: return
        val audioTracks = snapshot.audioTracks.mapIndexed { index, track ->
            val codecSuffix = buildList {
                track.codec?.takeIf { it.isNotBlank() }?.let { add(it) }
                track.channelCount?.takeIf { it > 0 }?.let { add("${it}ch") }
            }.joinToString(" ")
            TrackInfo(
                index = index,
                name = if (codecSuffix.isBlank()) track.name else "${track.name} ($codecSuffix)",
                language = track.language,
                trackId = track.id.toString(),
                codec = track.codec,
                channelCount = track.channelCount,
                isSelected = track.isSelected
            )
        }
        val subtitleTracks = snapshot.subtitleTracks
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
        val selectedSubtitleIndex = subtitleTracks.indexOfFirst { it.isSelected }
        val selectedExternalSubtitleTrack = snapshot.subtitleTracks.firstOrNull { it.isExternal && it.isSelected }

        hasScannedTextTracksOnce = true
        maybeApplyRememberedAudioSelection(audioTracks)
        _uiState.update { state ->
            val selectedAddonSubtitle = selectedExternalSubtitleTrack?.let { track ->
                state.addonSubtitles.firstOrNull { subtitle ->
                    buildAddonSubtitleTrackId(subtitle).equals(track.name, ignoreCase = true)
                }
            }
            state.copy(
                audioTracks = audioTracks,
                subtitleTracks = subtitleTracks,
                selectedAudioTrackIndex = selectedAudioIndex,
                selectedSubtitleTrackIndex = if (selectedExternalSubtitleTrack != null) -1 else selectedSubtitleIndex,
                selectedAddonSubtitle = selectedAddonSubtitle ?: state.selectedAddonSubtitle.takeIf {
                    selectedExternalSubtitleTrack == null && selectedSubtitleIndex < 0
                }
            )
        }
    } finally {
        mpvTrackRefreshInProgress = false
    }
}

internal fun PlayerRuntimeController.applyPendingMpvSeekIfNeeded(
    view: NexioMpvSurfaceView,
    currentPositionMs: Long = view.currentPositionMs().coerceAtLeast(0L),
    durationMs: Long = view.durationMs().coerceAtLeast(0L)
) {
    if (!isUsingMpvEngine()) return
    if (delayMpvResumeSeekUntilVideoTrack) {
        if (!view.hasVideoTrackSelectedNow()) return
        delayMpvResumeSeekUntilVideoTrack = false
    }

    val state = _uiState.value
    val savedResume = pendingResumeProgress
    val queuedPosition = state.pendingSeekPosition ?: savedResume?.position
    if (queuedPosition == null) return
    if (queuedPosition <= 0L && savedResume == null) {
        _uiState.update { it.copy(pendingSeekPosition = null) }
        pendingResumeProgress = null
        return
    }

    val target = when {
        savedResume != null && durationMs > 0L -> {
            savedResume.resolveResumePosition(durationMs).coerceAtLeast(0L)
        }
        savedResume != null -> {
            val requiresDurationForPercentResume = savedResume.progressPercent != null &&
                savedResume.duration <= 0L
            if (requiresDurationForPercentResume) return
            savedResume.position.coerceAtLeast(0L)
        }
        else -> queuedPosition.coerceAtLeast(0L)
    }

    if (target <= 0L) {
        _uiState.update { it.copy(pendingSeekPosition = null) }
        pendingResumeProgress = null
        return
    }

    val isAlreadyAtTarget = currentPositionMs >= target ||
        abs(currentPositionMs - target) <= MPV_RESUME_SEEK_TOLERANCE_MS
    if (isAlreadyAtTarget) {
        _uiState.update { it.copy(pendingSeekPosition = null) }
        pendingResumeProgress = null
        return
    }

    val canSeekNow = durationMs > 0L || currentPositionMs > 0L || hasRenderedFirstFrame
    if (!canSeekNow) return

    view.seekToMs(target)
    if (state.pendingSeekPosition != target) {
        _uiState.update { it.copy(pendingSeekPosition = target) }
    }
}

internal fun PlayerRuntimeController.keepMpvPlayingIfNeeded(wasPlaying: Boolean) {
    if (!wasPlaying || !isUsingMpvEngine()) return
    scope.launch {
        repeat(6) {
            if (!isUsingMpvEngine()) return@launch
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
            delay(120L)
        }
    }
}
