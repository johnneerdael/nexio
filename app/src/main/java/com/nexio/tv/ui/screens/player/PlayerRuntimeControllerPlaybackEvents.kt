package com.nexio.tv.ui.screens.player

import android.os.SystemClock
import android.util.Log
import com.nexio.tv.core.playback.PlaybackOwnerContext
import com.nexio.tv.core.player.DoviBridge
import com.nexio.tv.core.player.Dv5HardwareToneMapRpuTap
import com.nexio.tv.core.player.MatroskaDolbyVisionHookInstaller
import com.nexio.tv.data.local.SubtitleStyleSettings
import com.nexio.tv.data.repository.extractYear
import com.nexio.tv.data.repository.TrackingScrobbleItem
import com.nexio.tv.domain.model.WatchProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal fun PlayerRuntimeController.startProgressUpdates() {
    progressJob?.cancel()
    progressJob = scope.launch {
        while (isActive) {
            val nowUptime = SystemClock.uptimeMillis()
            val pos = backendCurrentPosition().coerceAtLeast(0L)
            val playerDuration = backendDuration().coerceAtLeast(0L)
            if (playerDuration > lastKnownDuration) {
                lastKnownDuration = playerDuration
            }
            val progressState = progressUiState.value
            val controlsVisible = _uiState.value.showControls
            val progressUpdateIntervalMs =
                if (controlsVisible || progressState.pendingPreviewSeekPosition != null) 500L else 1_000L
            if (
                nowUptime - lastProgressUiUpdateUptimeMs >= progressUpdateIntervalMs ||
                progressState.currentPosition != pos ||
                progressState.duration != playerDuration ||
                progressState.pendingPreviewSeekPosition != pendingPreviewSeekPosition
            ) {
                lastProgressUiUpdateUptimeMs = nowUptime
                _progressUiState.update {
                    it.copy(
                        currentPosition = pos,
                        pendingPreviewSeekPosition = pendingPreviewSeekPosition,
                        duration = playerDuration
                    )
                }
            }
            if (nowUptime - lastSkipIntervalEvaluationUptimeMs >= 1_000L) {
                lastSkipIntervalEvaluationUptimeMs = nowUptime
                updateActiveSkipInterval(pos)
            }
            if (nowUptime - lastNextEpisodeEvaluationUptimeMs >= 1_000L) {
                lastNextEpisodeEvaluationUptimeMs = nowUptime
                evaluateNextEpisodeCardVisibility(
                    positionMs = pos,
                    durationMs = playerDuration
                )
            }

            if (backendIsPlaying() && bufferLogsEnabled) {
                    val now = System.currentTimeMillis()
                    maybeRefreshVodTelemetry(now)
                    if (now - lastBufferLogTimeMs >= 30_000 && bufferLogJob?.isActive != true) {
                        lastBufferLogTimeMs = now
                        val player = _exoPlayer
                        val bufAhead = if (player != null) {
                            (player.bufferedPosition - player.currentPosition) / 1000
                        } else {
                            0L
                        }
                        val loading = player?.isLoading ?: _uiState.value.isBuffering
                        val runtime = Runtime.getRuntime()
                        val usedMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
                        val maxMb = runtime.maxMemory() / (1024 * 1024)
                        val vodCache = cachedVodCacheLogState
                        val signalingRewrites =
                            MatroskaDolbyVisionHookInstaller.getCodecStringRewriteCount()
                        val sourceProfile =
                            MatroskaDolbyVisionHookInstaller.getLastDetectedSourceProfile()
                        val conversionMode =
                            MatroskaDolbyVisionHookInstaller.getLastSelectedConversionMode()
                        val conversionCalls = DoviBridge.getConversionCallCount()
                        val conversionSuccess = DoviBridge.getConversionSuccessCount()
                        val conversionAttempted = conversionCalls > 0 || signalingRewrites > 0
                        val experimentalDvEnabled = isExperimentalDv7ToDv81ActiveForCurrentPlayback
                        val dv7ProbeReason = dv7ToDv81LastProbeReasonForCurrentPlayback ?: "n/a"
                        bufferLogJob = scope.launch(Dispatchers.Default) {
                            val dv7doviState = buildString {
                                append("dv7dovi=")
                                append(if (experimentalDvEnabled) "on" else "off")
                                append(",attempted=")
                                append(if (conversionAttempted) "yes" else "no")
                                append(",sourceProfile=")
                                append(sourceProfile ?: "n/a")
                                append(",mode=")
                                append(conversionMode ?: "n/a")
                                append(",signalRewrites=")
                                append(signalingRewrites)
                                append(",calls=")
                                append(conversionCalls)
                                append(",success=")
                                append(conversionSuccess)
                                append(",hook=")
                                append(if (DoviBridge.isExtractorHookReadyInBuild) "ready" else "missing")
                                append(",reason=")
                                append(dv7ProbeReason)
                                if (dolbyVisionDiagnosticsEnabled) {
                                    val bridgeDiagnostics = DoviBridge.runtimeDiagnosticsSnapshot()
                                    val hookDiagnostics = MatroskaDolbyVisionHookInstaller.runtimeAllocationSnapshot()
                                    val tapDiagnostics = Dv5HardwareToneMapRpuTap.runtimeSnapshot()
                                    append(",dvDiag=on")
                                    append(",dvInMb=")
                                    append(bridgeDiagnostics.inputBytes / (1024L * 1024L))
                                    append(",dvOutMb=")
                                    append(bridgeDiagnostics.outputBytes / (1024L * 1024L))
                                    append(",dvFail=")
                                    append(bridgeDiagnostics.failedConversions)
                                    append(",rewriteCalls=")
                                    append(hookDiagnostics.rewriteSampleCalls)
                                    append(",rewriteInMb=")
                                    append(hookDiagnostics.rewriteInputBytes / (1024L * 1024L))
                                    append(",rewriteOutMb=")
                                    append(hookDiagnostics.rewriteOutputBytes / (1024L * 1024L))
                                    append(",rewriteCopyMb=")
                                    append(hookDiagnostics.rewriteCopyBytes / (1024L * 1024L))
                                    append(",sourceCopyMb=")
                                    append(hookDiagnostics.sourceCopyBytes / (1024L * 1024L))
                                    append(",rpuInMb=")
                                    append(hookDiagnostics.rpuInputBytes / (1024L * 1024L))
                                    append(",rpuOutMb=")
                                    append(hookDiagnostics.rpuOutputBytes / (1024L * 1024L))
                                    append(",lengthFieldMb=")
                                    append(hookDiagnostics.lengthFieldBytes / (1024L * 1024L))
                                    append(",dropMb=")
                                    append(hookDiagnostics.droppedBytes / (1024L * 1024L))
                                    append(",streamSamples=")
                                    append(hookDiagnostics.streamingSamples)
                                    append(",streamRpuKb=")
                                    append(hookDiagnostics.streamingRpuBytes / 1024L)
                                    append(",streamRpuOutKb=")
                                    append(hookDiagnostics.streamingConvertedRpuBytes / 1024L)
                                    append(",appendMb=")
                                    append(hookDiagnostics.appendedSampleBytes / (1024L * 1024L))
                                    append(",rpuCalls=")
                                    append(hookDiagnostics.rpuNalTransformCalls)
                                    append(",rpuTapEntries=")
                                    append(tapDiagnostics.queuedEntries)
                                    append(",rpuTapQueuedKb=")
                                    append(tapDiagnostics.queuedBytes / 1024L)
                                    append(",rpuTapCopiedKb=")
                                    append(tapDiagnostics.copiedBytes / 1024L)
                                }
                            }
                            Log.d(
                                PlayerRuntimeController.TAG,
                                "BUFFER: ahead=${bufAhead}s, loading=$loading, heap=${usedMb}/${maxMb}MB, pos=${pos / 1000}s, $vodCache, $dv7doviState"
                            )
                        }
                    }
                }
            delay(500)
        }
    }
}

internal fun PlayerRuntimeController.stopProgressUpdates() {
    progressJob?.cancel()
    progressJob = null
    vodTelemetryJob?.cancel()
    vodTelemetryJob = null
    bufferLogJob?.cancel()
    bufferLogJob = null
}

internal fun PlayerRuntimeController.startWatchProgressSaving() {
    watchProgressSaveJob?.cancel()
    watchProgressSaveJob = null
    if (!shouldPersistWatchProgressOnPlaybackInterval()) return

    watchProgressSaveJob = scope.launch {
        while (isActive) {
            delay(10000)
            saveWatchProgressIfNeeded()
        }
    }
}

internal fun PlayerRuntimeController.stopWatchProgressSaving() {
    watchProgressSaveJob?.cancel()
    watchProgressSaveJob = null
}

internal fun shouldPersistWatchProgressOnPlaybackInterval(): Boolean = false

internal fun shouldPersistWatchProgressOnPlaybackStopEvent(): Boolean = true

internal fun PlayerRuntimeController.saveWatchProgressIfNeeded() {
    if (!hasRenderedFirstFrame) return
    val currentPosition = backendCurrentPosition().takeIf { it > 0L } ?: return
    val duration = getEffectiveDuration(currentPosition)
    
    
    if (kotlin.math.abs(currentPosition - lastSavedPosition) >= saveThresholdMs) {
        lastSavedPosition = currentPosition
        saveWatchProgressInternal(currentPosition, duration, syncRemote = false)
    }
}

internal fun PlayerRuntimeController.saveWatchProgress() {
    if (!hasRenderedFirstFrame) return
    val currentPosition = backendCurrentPosition().takeIf { it > 0L } ?: return
    val duration = getEffectiveDuration(currentPosition)
    saveWatchProgressInternal(currentPosition, duration)
}

internal fun PlayerRuntimeController.getEffectiveDuration(position: Long): Long {
    val playerDuration = backendDuration()
    val effectiveDuration = maxOf(playerDuration, lastKnownDuration)
    if (effectiveDuration <= 0L) return 0L

    val isEnded = _uiState.value.playbackEnded
    if (!isEnded && effectiveDuration < position) return 0L

    return effectiveDuration
}

private fun PlayerRuntimeController.maybeRefreshVodTelemetry(now: Long) {
    if (vodTelemetryJob?.isActive == true) return
    if (now - lastVodTelemetryRefreshTimeMs < 30_000) return
    lastVodTelemetryRefreshTimeMs = now
    val streamUrl = currentStreamUrl
    vodTelemetryJob = scope.launch(Dispatchers.IO) {
        val nextState = runCatching {
            mediaSourceFactory.getVodCacheLogState(streamUrl)
        }.getOrElse {
            cachedVodCacheLogState
        }
        cachedVodCacheLogState = nextState
    }
}

internal fun PlayerRuntimeController.saveWatchProgressInternal(position: Long, duration: Long, syncRemote: Boolean = true) {
    
    if (contentId.isNullOrEmpty() || contentType.isNullOrEmpty()) return
    
    if (position < 1000) return

    val fallbackPercent = if (duration <= 0L) 5f else null

    val progress = WatchProgress(
        contentId = contentId,
        contentType = contentType,
        name = contentName ?: title,
        poster = poster,
        backdrop = backdrop,
        logo = logo,
        videoId = currentVideoId ?: contentId,
        season = currentSeason,
        episode = currentEpisode,
        episodeTitle = currentEpisodeTitle,
        position = position,
        duration = duration,
        lastWatched = System.currentTimeMillis(),
        addonBaseUrl = addonBaseUrl,
        progressPercent = fallbackPercent
    )

    scope.launch {
        watchProgressRepository.saveProgress(progress, syncRemote = syncRemote)
    }
}

internal fun PlayerRuntimeController.currentPlaybackProgressPercent(): Float {
    if (!hasRenderedFirstFrame) return 0f
    val duration = backendDuration().takeIf { it > 0 } ?: lastKnownDuration
    if (duration <= 0L) return 0f
    return ((backendCurrentPosition().toFloat() / duration.toFloat()) * 100f).coerceIn(0f, 100f)
}

internal fun PlayerRuntimeController.refreshScrobbleItem() {
    stopScrobbleHeartbeat()
    currentScrobbleItem = buildScrobbleItem()
    hasSentScrobbleStartForCurrentItem = false
    hasRequestedScrobbleStartForCurrentItem = false
    scrobbleStartRequestGeneration++
    hasSentCompletionScrobbleForCurrentItem = false
}

internal fun PlayerRuntimeController.buildScrobbleItem(): TrackingScrobbleItem? {
    val rawContentId = contentId ?: return null
    val parsedYear = extractYear(year)
    val normalizedType = contentType?.lowercase()

    val isEpisode = normalizedType in listOf("series", "tv") &&
        currentSeason != null && currentEpisode != null

    val item = if (isEpisode) {
        TrackingScrobbleItem.Episode(
            contentId = rawContentId,
            showTitle = contentName ?: title,
            showYear = parsedYear,
            season = currentSeason ?: return null,
            number = currentEpisode ?: return null,
            episodeTitle = currentEpisodeTitle
        )
    } else {
        TrackingScrobbleItem.Movie(
            contentId = rawContentId,
            title = contentName ?: title,
            year = parsedYear
        )
    }
    return item
}

internal fun PlayerRuntimeController.emitScrobbleStart() {
    val item = currentScrobbleItem ?: buildScrobbleItem().also { currentScrobbleItem = it }
    if (item == null) return
    if (hasRequestedScrobbleStartForCurrentItem) return

    hasRequestedScrobbleStartForCurrentItem = true
    startScrobbleHeartbeat()
    val requestGeneration = ++scrobbleStartRequestGeneration
    scope.launch {
        val progressPercent = currentPlaybackProgressPercent()
        trackingScrobbleService.scrobbleStart(
            item = item,
            progressPercent = progressPercent,
            owner = playbackOwnerContext
        )
        if (requestGeneration != scrobbleStartRequestGeneration || !hasRequestedScrobbleStartForCurrentItem) return@launch
        hasSentScrobbleStartForCurrentItem = true
    }
}

internal fun PlayerRuntimeController.emitScrobbleStop(
    progressPercent: Float? = null,
    allowWithoutStart: Boolean = false
) {
    val item = currentScrobbleItem
    if (item == null) return

    val provided = progressPercent
    if (!allowWithoutStart && !hasRequestedScrobbleStartForCurrentItem && (provided ?: 0f) < 80f) return

    val percent = provided ?: currentPlaybackProgressPercent()
    scope.launch {
        trackingScrobbleService.scrobbleStop(
            item = item,
            progressPercent = percent,
            owner = playbackOwnerContext
        )
    }
    stopScrobbleHeartbeat()
    scrobbleStartRequestGeneration++
    hasRequestedScrobbleStartForCurrentItem = false
    hasSentScrobbleStartForCurrentItem = false
}

internal fun PlayerRuntimeController.emitPauseScrobble(progressPercent: Float) {
    if (progressPercent < 1f) return
    emitScrobbleStop(progressPercent = progressPercent)
}

internal fun PlayerRuntimeController.emitCompletionScrobbleStop(progressPercent: Float) {
    if (progressPercent < 80f || hasSentCompletionScrobbleForCurrentItem) return
    hasSentCompletionScrobbleForCurrentItem = true
    emitScrobbleStop(progressPercent = progressPercent)
}

internal fun PlayerRuntimeController.emitStopScrobbleForCurrentProgress() {
    val progressPercent = currentPlaybackProgressPercent()
    emitScrobbleStop(progressPercent = progressPercent)
}

internal fun PlayerRuntimeController.flushPlaybackSnapshotForSwitchOrExit() {
    val progressPercent = currentPlaybackProgressPercent()
    // Teardown path: persist watch state even if start state tracking got out of sync.
    emitScrobbleStop(
        progressPercent = progressPercent,
        allowWithoutStart = progressPercent >= 1f
    )
    saveWatchProgress()
}

internal fun PlayerRuntimeController.scheduleProgressSyncAfterSeek() {
    seekProgressSyncJob?.cancel()
    seekProgressSyncJob = scope.launch {
        delay(seekProgressSyncDebounceMs)
        saveWatchProgress()

        val progressPercent = currentPlaybackProgressPercent()
        emitPauseScrobble(progressPercent = progressPercent)

        if (backendIsPlaying() && progressPercent >= 1f && progressPercent < 80f) {
            emitScrobbleStart()
        }
    }
}

private fun PlayerRuntimeController.startScrobbleHeartbeat() {
    if (scrobbleHeartbeatJob?.isActive == true) return
    val item = currentScrobbleItem ?: return
    if (!hasRequestedScrobbleStartForCurrentItem) return

    scrobbleHeartbeatJob = scope.launch {
        while (isActive) {
            delay(scrobbleHeartbeatIntervalMs)
            if (!isActive || !backendIsPlaying() || !hasRequestedScrobbleStartForCurrentItem) continue
            val progressPercent = currentPlaybackProgressPercent()
            if (progressPercent < 1f || progressPercent >= 95f) continue
            trackingScrobbleService.scrobbleStart(
                item = item,
                progressPercent = progressPercent,
                owner = playbackOwnerContext
            )
        }
    }
}

private fun PlayerRuntimeController.stopScrobbleHeartbeat() {
    scrobbleHeartbeatJob?.cancel()
    scrobbleHeartbeatJob = null
}

fun PlayerRuntimeController.scheduleHideControls() {
    hideControlsJob?.cancel()
    hideControlsJob = scope.launch {
        delay(3000)
        if (_uiState.value.isPlaying && !_uiState.value.showAudioDialog &&
            !_uiState.value.showSubtitleDialog &&
            !_uiState.value.showSpeedDialog && !_uiState.value.showMoreDialog &&
            !_uiState.value.showSubtitleDelayOverlay &&
            !_uiState.value.showEpisodesPanel && !_uiState.value.showSourcesPanel) {
            _uiState.update { it.copy(showControls = false) }
        }
    }
}

internal fun PlayerRuntimeController.showSubtitleDelayOverlay() {
    hideControlsJob?.cancel()
    _uiState.update {
        it.copy(
            showControls = false,
            showSubtitleDelayOverlay = true,
            showAudioDialog = false,
            showSubtitleDialog = false,
            showSpeedDialog = false
        )
    }
    scheduleHideSubtitleDelayOverlay()
}

internal fun PlayerRuntimeController.hideSubtitleDelayOverlay() {
    hideSubtitleDelayOverlayJob?.cancel()
    hideSubtitleDelayOverlayJob = null
    _uiState.update { it.copy(showSubtitleDelayOverlay = false) }
}

internal fun PlayerRuntimeController.adjustSubtitleDelay(deltaMs: Int) {
    val currentDelayMs = _uiState.value.subtitleDelayMs
    val newDelayMs = (currentDelayMs + deltaMs).coerceIn(
        minimumValue = SUBTITLE_DELAY_MIN_MS,
        maximumValue = SUBTITLE_DELAY_MAX_MS
    )

    subtitleDelayUs.set(newDelayMs.toLong() * 1000L)
    backendSetSubtitleDelay(newDelayMs)
    _uiState.update {
        it.copy(
            subtitleDelayMs = newDelayMs,
            showControls = false,
            showSubtitleDelayOverlay = true
        )
    }
    scheduleHideSubtitleDelayOverlay()
}

internal fun PlayerRuntimeController.scheduleHideSubtitleDelayOverlay() {
    hideSubtitleDelayOverlayJob?.cancel()
    hideSubtitleDelayOverlayJob = scope.launch {
        delay(SUBTITLE_DELAY_OVERLAY_TIMEOUT_MS)
        _uiState.update { it.copy(showSubtitleDelayOverlay = false) }
    }
}

internal fun PlayerRuntimeController.schedulePauseOverlay() {
    pauseOverlayJob?.cancel()

    if (!_uiState.value.pauseOverlayEnabled || !hasRenderedFirstFrame || !userPausedManually) {
        _uiState.update { it.copy(showPauseOverlay = false) }
        return
    }

    _uiState.update { it.copy(showPauseOverlay = false) }
    pauseOverlayJob = scope.launch {
        delay(pauseOverlayDelayMs)
        val state = _uiState.value
        if (shouldShowPauseOverlayAfterDelay(state)) {
            _uiState.update { it.copy(showPauseOverlay = true, showControls = false) }
        }
    }
}

internal fun PlayerRuntimeController.cancelPauseOverlay() {
    pauseOverlayJob?.cancel()
    pauseOverlayJob = null
    _uiState.update { it.copy(showPauseOverlay = false) }
}

fun PlayerRuntimeController.onUserInteraction() {
    if (_uiState.value.showPauseOverlay) {
        cancelPauseOverlay()
        showControlsTemporarily()
    } else if (pauseOverlayJob != null && !_uiState.value.isPlaying && userPausedManually) {
        schedulePauseOverlay()
    }
}

fun PlayerRuntimeController.hideControls() {
    hideControlsJob?.cancel()
    _uiState.update { it.copy(showControls = false, showSeekOverlay = false) }
}

fun PlayerRuntimeController.onEvent(event: PlayerEvent) {
    onUserInteraction()
    when (event) {
        PlayerEvent.OnPlayPause -> {
            if (backendIsPlaying()) {
                userPausedManually = true
                playbackIdleGateState.onUserPauseStateChanged(isPausedByUser = true)
                backendPause()
                schedulePauseOverlay()
            } else {
                userPausedManually = false
                playbackIdleGateState.onUserPauseStateChanged(isPausedByUser = false)
                cancelPauseOverlay()
                backendPlay()
            }
            showControlsTemporarily()
        }
        PlayerEvent.OnSeekForward -> {
            onEvent(PlayerEvent.OnSeekBy(deltaMs = 10_000L))
        }
        PlayerEvent.OnSeekBackward -> {
            onEvent(PlayerEvent.OnSeekBy(deltaMs = -10_000L))
        }
        is PlayerEvent.OnSeekBy -> {
            pendingPreviewSeekPosition = null
            val maxDuration = backendDuration().takeIf { it >= 0 } ?: Long.MAX_VALUE
            val target = (backendCurrentPosition() + event.deltaMs)
                .coerceAtLeast(0L)
                .coerceAtMost(maxDuration)
            beginSeekTelemetry(target)
            backendSeekTo(target)
            _progressUiState.update {
                it.copy(
                    currentPosition = target,
                    pendingPreviewSeekPosition = null
                )
            }
            scheduleProgressSyncAfterSeek()
            if (_uiState.value.showControls) {
                showControlsTemporarily()
            } else {
                showSeekOverlayTemporarily()
            }
        }
        is PlayerEvent.OnPreviewSeekBy -> {
            val maxDuration = backendDuration().takeIf { it >= 0 } ?: Long.MAX_VALUE
            val basePosition = pendingPreviewSeekPosition ?: backendCurrentPosition().coerceAtLeast(0L)
            val target = (basePosition + event.deltaMs)
                .coerceAtLeast(0L)
                .coerceAtMost(maxDuration)
            pendingPreviewSeekPosition = target
            _progressUiState.update { it.copy(pendingPreviewSeekPosition = target) }
            if (_uiState.value.showControls) {
                showControlsTemporarily()
            } else {
                showSeekOverlayTemporarily()
            }
        }
        PlayerEvent.OnCommitPreviewSeek -> {
            val target = pendingPreviewSeekPosition
            if (target != null) {
                beginSeekTelemetry(target)
                backendSeekTo(target)
                pendingPreviewSeekPosition = null
                _progressUiState.update {
                    it.copy(
                        currentPosition = target,
                        pendingPreviewSeekPosition = null
                    )
                }
                scheduleProgressSyncAfterSeek()
                if (_uiState.value.showControls) {
                    showControlsTemporarily()
                } else {
                    showSeekOverlayTemporarily()
                }
            }
        }
        is PlayerEvent.OnSeekTo -> {
            pendingPreviewSeekPosition = null
            beginSeekTelemetry(event.position)
            backendSeekTo(event.position)
            _progressUiState.update {
                it.copy(
                    currentPosition = event.position,
                    pendingPreviewSeekPosition = null
                )
            }
            scheduleProgressSyncAfterSeek()
            if (_uiState.value.showControls) {
                showControlsTemporarily()
            } else {
                showSeekOverlayTemporarily()
            }
        }
        is PlayerEvent.OnSelectAudioTrack -> {
            selectAudioTrack(event.index)
            _uiState.update { it.copy(showAudioDialog = false, showSubtitleDelayOverlay = false) }
        }
        is PlayerEvent.OnSelectSubtitleTrack -> {
            autoSubtitleSelected = true
            pendingAddonSubtitleLanguage = null
            pendingAddonSubtitleTrackId = null
            pendingAudioSelectionAfterSubtitleRefresh = null
            // If the user manually picked an internal track that is already
            // in their primary subtitle language, AI translation would be a
            // no-op and would leave the picker showing two simultaneously
            // "selected" entries. Clear the AI flag silently first.
            val pickedTrack = _uiState.value.subtitleTracks.getOrNull(event.index)
            val pickedLang = pickedTrack?.language?.let(PlayerSubtitleUtils::normalizeLanguageCode)
            val primaryLang = PlayerSubtitleUtils.normalizeLanguageCode(
                _uiState.value.subtitleStyle.preferredLanguage
            )
            val pickedIsPrimaryLang = !pickedLang.isNullOrBlank() &&
                primaryLang.isNotBlank() &&
                pickedLang == primaryLang
            if (pickedIsPrimaryLang &&
                (_uiState.value.aiSubtitlesEnabled || _uiState.value.isAiSubtitleTranslating)
            ) {
                clearAiTranslationStateSilently()
            }
            deactivateAddonSubtitleOverlay()
            selectSubtitleTrack(event.index)
            _uiState.update {
                it.copy(
                    showSubtitleDialog = false,
                    showSubtitleDelayOverlay = false,
                    selectedAddonSubtitle = null
                )
            }
            refreshBuiltInAiOverlayState()
            if (_uiState.value.aiSubtitlesEnabled) {
                handleBuiltInCueGroupUpdate()
            }
        }
        PlayerEvent.OnDisableSubtitles -> {
            autoSubtitleSelected = true
            pendingAddonSubtitleLanguage = null
            pendingAddonSubtitleTrackId = null
            pendingAudioSelectionAfterSubtitleRefresh = null
            deactivateAddonSubtitleOverlay()
            disableSubtitles()
            _uiState.update { 
                it.copy(
                    showSubtitleDialog = false,
                    showSubtitleDelayOverlay = false,
                    selectedAddonSubtitle = null,
                    selectedSubtitleTrackIndex = -1
                ) 
            }
            refreshBuiltInAiOverlayState()
        }
        is PlayerEvent.OnSelectAddonSubtitle -> {
            autoSubtitleSelected = true
            selectAddonSubtitleRespectingAi(event.subtitle)
            _uiState.update {
                it.copy(
                    showSubtitleDialog = false,
                    showSubtitleDelayOverlay = false
                )
            }
            refreshBuiltInAiOverlayState()
        }
        PlayerEvent.OnToggleAiSubtitles -> {
            toggleAiSubtitles()
        }
        is PlayerEvent.OnSetPlaybackSpeed -> {
            backendSetPlaybackSpeed(event.speed)
            _uiState.update { 
                it.copy(
                    playbackSpeed = event.speed,
                    showSpeedDialog = false,
                    showSubtitleDelayOverlay = false
                ) 
            }
        }
        PlayerEvent.OnToggleControls -> {
            if (_uiState.value.showSubtitleDelayOverlay) {
                hideSubtitleDelayOverlay()
            }
            _uiState.update { it.copy(showControls = !it.showControls) }
            if (_uiState.value.showControls) {
                scheduleHideControls()
            }
        }
        PlayerEvent.OnShowAudioDialog -> {
            _uiState.update {
                it.copy(
                    showAudioDialog = true,
                    showMoreDialog = false,
                    showSubtitleDelayOverlay = false,
                    showControls = true
                )
            }
        }
        PlayerEvent.OnShowSubtitleDialog -> {
            _uiState.update {
                it.copy(
                    showSubtitleDialog = true,
                    showMoreDialog = false,
                    showSubtitleDelayOverlay = false,
                    showControls = true
                )
            }
        }
        PlayerEvent.OnShowSubtitleDelayOverlay -> {
            showSubtitleDelayOverlay()
        }
        PlayerEvent.OnHideSubtitleDelayOverlay -> {
            hideSubtitleDelayOverlay()
        }
        is PlayerEvent.OnAdjustSubtitleDelay -> {
            adjustSubtitleDelay(event.deltaMs)
        }
        PlayerEvent.OnShowSpeedDialog -> {
            _uiState.update {
                it.copy(
                    showSpeedDialog = true,
                    showMoreDialog = false,
                    showSubtitleDelayOverlay = false,
                    showControls = true
                )
            }
        }
        PlayerEvent.OnShowMoreDialog -> {
            _uiState.update {
                it.copy(
                    showMoreDialog = true,
                    showAudioDialog = false,
                    showSubtitleDialog = false,
                    showSubtitleDelayOverlay = false,
                    showSpeedDialog = false,
                    showControls = true
                )
            }
        }
        PlayerEvent.OnDismissMoreDialog -> {
            _uiState.update { it.copy(showMoreDialog = false) }
            scheduleHideControls()
        }
        PlayerEvent.OnShowEpisodesPanel -> {
            showEpisodesPanel()
        }
        PlayerEvent.OnDismissEpisodesPanel -> {
            dismissEpisodesPanel()
        }
        PlayerEvent.OnBackFromEpisodeStreams -> {
            _uiState.update {
                it.copy(
                    showEpisodeStreams = false,
                    isLoadingEpisodeStreams = false
                )
            }
        }
        is PlayerEvent.OnEpisodeSeasonSelected -> {
            selectEpisodesSeason(event.season)
        }
        is PlayerEvent.OnEpisodeSelected -> {
            loadStreamsForEpisode(event.video)
        }
        PlayerEvent.OnReloadEpisodeStreams -> {
            reloadEpisodeStreams()
        }
        is PlayerEvent.OnEpisodeAddonFilterSelected -> {
            filterEpisodeStreamsByAddon(event.addonName)
        }
        is PlayerEvent.OnEpisodeStreamSelected -> {
            switchToEpisodeStream(event.stream)
        }
        PlayerEvent.OnShowSourcesPanel -> {
            showSourcesPanel()
        }
        PlayerEvent.OnDismissSourcesPanel -> {
            dismissSourcesPanel()
        }
        PlayerEvent.OnReloadSourceStreams -> {
            loadSourceStreams(forceRefresh = true)
        }
        is PlayerEvent.OnSourceAddonFilterSelected -> {
            filterSourceStreamsByAddon(event.addonName)
        }
        is PlayerEvent.OnSourceStreamSelected -> {
            switchToSourceStream(event.stream)
        }
        PlayerEvent.OnDismissDialog -> {
            _uiState.update { 
                it.copy(
                    showAudioDialog = false, 
                    showSubtitleDialog = false, 
                    showSpeedDialog = false,
                    showSubtitleDelayOverlay = false,
                    showMoreDialog = false
                ) 
            }
            scheduleHideControls()
        }
        PlayerEvent.OnRetry -> {
            hasRenderedFirstFrame = false
            hasRetriedCurrentStreamAfter416 = false
            hasRetriedCurrentStreamAfterUnexpectedNpe = false
            hasRetriedCurrentStreamAfterMediaPeriodHolderCrash = false
            resetNextEpisodeCardState(clearEpisode = false)
            _uiState.update { state ->
                state.copy(
                    error = null,
                    showLoadingOverlay = state.loadingOverlayEnabled,
                    showSubtitleDelayOverlay = false
                )
            }
            releasePlayer()
            initializePlayer(currentStreamUrl, currentHeaders)
        }
        is PlayerEvent.OnShowDisplayModeInfo -> {
            _uiState.update {
                it.copy(
                    displayModeInfo = event.info,
                    showDisplayModeInfo = true
                )
            }
        }
        PlayerEvent.OnHideDisplayModeInfo -> {
            _uiState.update { it.copy(showDisplayModeInfo = false) }
        }
        PlayerEvent.OnDismissPauseOverlay -> {
            cancelPauseOverlay()
        }
        PlayerEvent.OnSkipIntro -> {
            _uiState.value.activeSkipInterval?.let { interval ->
                val duration = backendDuration().takeIf { it > 0 } ?: Long.MAX_VALUE
                val target = if (interval.endTime == Double.MAX_VALUE) duration
                else (interval.endTime * 1000).toLong()
                val seekMs = target.coerceAtMost(duration)
                beginSeekTelemetry(seekMs)
                backendSeekTo(seekMs)
                scheduleProgressSyncAfterSeek()
                _uiState.update { it.copy(activeSkipInterval = null, skipIntervalDismissed = true) }
            }
        }
        PlayerEvent.OnDismissSkipIntro -> {
            _uiState.update { it.copy(skipIntervalDismissed = true) }
        }
        PlayerEvent.OnPlayNextEpisode -> {
            playNextEpisode()
        }
        PlayerEvent.OnDismissNextEpisodeCard -> {
            nextEpisodeAutoPlayJob?.cancel()
            nextEpisodeAutoPlayJob = null
            _uiState.update {
                it.copy(
                    showNextEpisodeCard = false,
                    nextEpisodeCardDismissed = true,
                    nextEpisodeAutoPlaySearching = false,
                    nextEpisodeAutoPlaySourceName = null,
                    nextEpisodeAutoPlayCountdownSec = null
                )
            }
        }
        is PlayerEvent.OnSetSubtitleSize -> {
            scope.launch { playerSettingsDataStore.setSubtitleSize(event.size) }
        }
        is PlayerEvent.OnSetSubtitleBold -> {
            scope.launch { playerSettingsDataStore.setSubtitleBold(event.bold) }
        }
        is PlayerEvent.OnSetSubtitleOutlineEnabled -> {
            scope.launch { playerSettingsDataStore.setSubtitleOutlineEnabled(event.enabled) }
        }
        is PlayerEvent.OnSetSubtitleOutlineColor -> {
            scope.launch { playerSettingsDataStore.setSubtitleOutlineColor(event.color) }
        }
        is PlayerEvent.OnSetSubtitleVerticalOffset -> {
            scope.launch { playerSettingsDataStore.setSubtitleVerticalOffset(event.offset) }
        }
        PlayerEvent.OnResetSubtitleDefaults -> {
            scope.launch {
                val defaults = SubtitleStyleSettings()
                playerSettingsDataStore.setSubtitleSize(defaults.size)
                playerSettingsDataStore.setSubtitleBold(defaults.bold)
                playerSettingsDataStore.setSubtitleOutlineEnabled(defaults.outlineEnabled)
                playerSettingsDataStore.setSubtitleOutlineColor(defaults.outlineColor)
                playerSettingsDataStore.setSubtitleOutlineWidth(defaults.outlineWidth)
                playerSettingsDataStore.setSubtitleVerticalOffset(defaults.verticalOffset)
                playerSettingsDataStore.setSubtitleBackgroundColor(defaults.backgroundColor)
            }
        }
        PlayerEvent.OnToggleAspectRatio -> {
            val currentMode = _uiState.value.resizeMode
            val newMode = PlayerDisplayModeUtils.nextResizeMode(currentMode)
            val modeText = PlayerDisplayModeUtils.resizeModeLabel(newMode, context)
            Log.d("PlayerViewModel", "Aspect ratio toggled: $currentMode -> $newMode")
            _uiState.update { 
                it.copy(
                    resizeMode = newMode,
                    showAspectRatioIndicator = true,
                    aspectRatioIndicatorText = modeText
                ) 
            }
            // Auto-hide indicator after 1.5 seconds
            hideAspectRatioIndicatorJob?.cancel()
            hideAspectRatioIndicatorJob = scope.launch {
                delay(1500)
                _uiState.update { it.copy(showAspectRatioIndicator = false) }
            }
        }
    }
}

private fun PlayerRuntimeController.beginSeekTelemetry(targetMs: Long) {
    val requestTimeMs = System.currentTimeMillis()
    pendingSeekTelemetryRequestedAtMs = requestTimeMs
    pendingSeekTelemetryTargetMs = targetMs
    if (backendIsReady() && !_uiState.value.isBuffering) {
        pendingSeekTelemetryReadyAtMs = requestTimeMs
        pendingSeekTelemetryReadyLatencyMs = 0L
        pendingSeekTelemetryReadyAssumed = true
    } else {
        pendingSeekTelemetryReadyAtMs = 0L
        pendingSeekTelemetryReadyLatencyMs = -1L
        pendingSeekTelemetryReadyAssumed = false
    }
    pendingSeekTelemetryAwaitingFirstFrame = true
}
