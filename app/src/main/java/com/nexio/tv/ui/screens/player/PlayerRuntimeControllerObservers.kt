package com.nexio.tv.ui.screens.player

import android.net.Uri
import android.util.Log
import com.nexio.tv.core.metadata.router.resolver.SkipSegmentRequest
import com.nexio.tv.core.player.AndroidFrameRateSettings
import com.nexio.tv.core.player.DoviBridge
import com.nexio.tv.core.player.Dv5HardwareToneMapRpuTap
import com.nexio.tv.core.player.FfmpegStreamMetadataProbe
import com.nexio.tv.core.player.MatroskaDolbyVisionHookInstaller
import com.nexio.tv.core.player.BurnInProtectionState
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.Util
import androidx.media3.common.util.UnstableApi
import com.nexio.tv.data.local.SubtitleOrganizationMode
import com.nexio.tv.data.local.diskSpoolTargetBitrateMbps
import com.nexio.tv.domain.model.Subtitle
import com.nexio.tv.domain.model.WatchProgress
import com.nexio.tv.ui.screens.player.spool.SpoolStorageProbeResult
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

internal data class SubtitleFetchRequest(
    val type: String,
    val id: String,
    val videoId: String?,
    val imdbId: String?
)

internal fun PlayerRuntimeController.buildSubtitleFetchRequest(): SubtitleFetchRequest? {
    val id = contentId ?: return null
    val type = contentType ?: return null
    return SubtitleFetchRequest(
        type = type.lowercase(),
        id = id,
        videoId = currentVideoId,
        imdbId = imdbId
    )
}

internal suspend fun PlayerRuntimeController.fetchAddonSubtitlesNow(
    preferredLanguageOverride: String? = null,
    secondaryLanguageOverride: String? = null
): List<Subtitle> {
    val request = buildSubtitleFetchRequest() ?: return emptyList()

    // Compute hash lazily for providers that support OpenSubtitles-style matching.
    if (currentVideoHash == null && currentStreamUrl.isNotBlank()) {
        val result = openSubtitlesHashIntegrationProvider.compute(currentStreamUrl, currentHeaders)
        if (result != null) {
            currentVideoHash = result.hash
            if (currentVideoSize == null) currentVideoSize = result.fileSize
            // Update cache now that we have the computed hash
            val key = streamCacheKey
            val url = currentStreamUrl.takeIf { it.isNotBlank() }
            if (key != null && url != null) {
                val state = _uiState.value
                val selectedAudio = state.audioTracks.getOrNull(state.selectedAudioTrackIndex)
                streamLinkCacheDataStore.save(
                    contentKey = key,
                    url = url,
                    streamName = state.currentStreamName ?: title,
                    headers = currentHeaders,
                    rememberedAudioLanguage = selectedAudio?.language ?: rememberedAudioLanguage,
                    rememberedAudioName = selectedAudio?.name ?: rememberedAudioName,
                    filename = currentFilename,
                    videoHash = currentVideoHash,
                    videoSize = currentVideoSize
                )
            }
        }
    }

    val hints = com.nexio.tv.data.integration.subtitles.wyzie.WyzieIdHintsParser.parse(request.id)
    val fetched = subtitleRepository.getSubtitles(
        type = request.type,
        id = request.id,
        videoId = request.videoId,
        videoHash = currentVideoHash,
        videoSize = currentVideoSize,
        filename = currentFilename,
        wyzieHints = hints,
        season = currentSeason,
        episode = currentEpisode,
        imdbId = request.imdbId,
    )
    val preferredLanguage = preferredLanguageOverride ?: _uiState.value.subtitleStyle.preferredLanguage
    val secondaryLanguage = secondaryLanguageOverride ?: _uiState.value.subtitleStyle.secondaryPreferredLanguage
    val filtered = filterAndSortAddonSubtitlesForPreferences(
        subtitles = fetched,
        preferredLanguage = preferredLanguage,
        secondaryLanguage = secondaryLanguage
    )
    Log.d(
        PlayerRuntimeController.TAG,
        "ADDON_SUBS filtered total=${fetched.size} kept=${filtered.size} preferred=$preferredLanguage secondary=$secondaryLanguage"
    )
    return filtered
}

internal fun PlayerRuntimeController.fetchAddonSubtitles() {
    if (buildSubtitleFetchRequest() == null) return
    
    scope.launch {
        _uiState.update { it.copy(isLoadingAddonSubtitles = true, addonSubtitlesError = null) }
        
        try {
            val subtitles = fetchAddonSubtitlesNow()
            
            _uiState.update { 
                it.copy(
                    addonSubtitles = subtitles,
                    isLoadingAddonSubtitles = false
                ) 
            }
            tryAutoSelectPreferredSubtitleFromAvailableTracks()
        } catch (e: CancellationException) {
            _uiState.update {
                it.copy(isLoadingAddonSubtitles = false)
            }
            throw e
        } catch (e: Exception) {
            _uiState.update { 
                it.copy(
                    isLoadingAddonSubtitles = false,
                    addonSubtitlesError = e.message
                ) 
            }
        }
    }
}

private fun PlayerRuntimeController.filterAndSortAddonSubtitlesForPreferences(
    subtitles: List<Subtitle>,
    preferredLanguage: String?,
    secondaryLanguage: String?
): List<Subtitle> {
    val targets = listOfNotNull(preferredLanguage, secondaryLanguage)
        .map { it.trim() }
        .filter { it.isNotBlank() && !it.equals("none", ignoreCase = true) }
        .map(PlayerSubtitleUtils::normalizeLanguageCode)
        .distinct()

    if (targets.isEmpty()) {
        return subtitles
    }

    fun matchRank(subtitle: Subtitle): Int {
        val normalizedLang = PlayerSubtitleUtils.normalizeLanguageCode(subtitle.lang)
        return targets.indexOfFirst { target ->
            PlayerSubtitleUtils.matchesLanguageCode(normalizedLang, target)
        }.let { index -> if (index >= 0) index else Int.MAX_VALUE }
    }

    return subtitles
        .mapNotNull { subtitle ->
            val rank = matchRank(subtitle)
            if (rank == Int.MAX_VALUE) null else subtitle to rank
        }
        .sortedWith(
            compareBy<Pair<Subtitle, Int>>(
                { it.second },
                { PlayerSubtitleUtils.normalizeLanguageCode(it.first.lang) },
                { it.first.addonName.lowercase() },
                { it.first.id },
                { it.first.url }
            )
        )
        .map { it.first }
}

internal fun PlayerRuntimeController.refreshSubtitlesForCurrentEpisode() {
    autoSubtitleSelected = false
    hasScannedTextTracksOnce = false
    pendingAddonSubtitleLanguage = null
    pendingAddonSubtitleTrackId = null
    pendingAudioSelectionAfterSubtitleRefresh = null
    attachedAddonSubtitleKeys = emptySet()
    _uiState.update {
        it.copy(
            addonSubtitles = emptyList(),
            selectedAddonSubtitle = null,
            selectedSubtitleTrackIndex = -1,
            isLoadingAddonSubtitles = true,
            addonSubtitlesError = null
        )
    }
    fetchAddonSubtitles()
}

internal fun PlayerRuntimeController.observeBlurUnwatchedEpisodes() {
    scope.launch {
        layoutPreferenceDataStore.blurUnwatchedEpisodes.collectLatest { enabled ->
            _uiState.update { it.copy(blurUnwatchedEpisodes = enabled) }
        }
    }
}

internal fun PlayerRuntimeController.observeDebugSettings() {
    scope.launch {
        debugSettingsDataStore.streamDiagnosticsEnabled.collectLatest { enabled ->
            streamDiagnosticsEnabled = enabled
        }
    }
    scope.launch {
        debugSettingsDataStore.diskSpoolDiagnosticsEnabled.collectLatest { enabled ->
            mediaSourceFactory.diskSpoolDiagnosticsEnabled = enabled
        }
    }
    scope.launch {
        debugSettingsDataStore.dolbyVisionDiagnosticsEnabled.collectLatest { enabled ->
            dolbyVisionDiagnosticsEnabled = enabled
            FfmpegStreamMetadataProbe.setDiagnosticsEnabled(enabled)
            DoviBridge.setVerboseLoggingEnabled(enabled)
            MatroskaDolbyVisionHookInstaller.setDiagnosticsEnabled(enabled)
            Dv5HardwareToneMapRpuTap.setDiagnosticsEnabled(enabled)
        }
    }
}

internal fun PlayerRuntimeController.observeEpisodeWatchProgress() {
    val id = contentId ?: return
    val type = contentType ?: return
    if (type.lowercase() != "series") return
    val baseId = id.split(":").firstOrNull() ?: id
    scope.launch {
        watchProgressRepository.getAllEpisodeProgress(
            profileId = playbackOwnerContext.ownerProfileId,
            contentId = baseId
        ).collectLatest { progressMap ->
            _uiState.update { it.copy(episodeWatchProgressMap = progressMap) }
            val watchedSet = progressMap
                .filterValues { it.isCompleted() }
                .keys
            _uiState.update { it.copy(watchedEpisodeKeys = watchedSet) }
        }
    }
}

internal fun PlayerRuntimeController.observeSubtitleSettings() {
    scope.launch {
        playerSettingsDataStore.playerSettings.collect { settings ->
            _uiState.update { state ->
                val shouldShowOverlay = when {
                    !settings.loadingOverlayEnabled -> false
                    !hasRenderedFirstFrame && state.isBuffering -> true
                    else -> false
                }

                state.copy(
                    subtitleStyle = settings.subtitleStyle,
                    subtitleOrganizationMode = SubtitleOrganizationMode.BY_LANGUAGE,
                    loadingOverlayEnabled = settings.loadingOverlayEnabled,
                    showLoadingOverlay = shouldShowOverlay,
                    pauseOverlayEnabled = settings.pauseOverlayEnabled,
                    osdClockEnabled = settings.osdClockEnabled,
                    burnInProtection = if (settings.burnInProtection.enabled) {
                        state.burnInProtection.copy(enabled = true)
                    } else {
                        BurnInProtectionState.DISABLED
                    }
                )
            }
            bufferLogsEnabled = settings.enableBufferLogs
            if (!AndroidFrameRateSettings.canRequestFrameRate(context)) {
                frameRateProbeJob?.cancel()
                _uiState.update {
                    it.copy(
                        detectedFrameRateRaw = 0f,
                        detectedFrameRate = 0f,
                        detectedFrameRateSource = null,
                        afrProbeRunning = false
                    )
                }
            }

            if (!settings.pauseOverlayEnabled) {
                cancelPauseOverlay()
            } else if (!_uiState.value.isPlaying &&
                !_uiState.value.showPauseOverlay && pauseOverlayJob == null &&
                userPausedManually && hasRenderedFirstFrame
            ) {
                schedulePauseOverlay()
            }
            streamReuseLastLinkEnabled = settings.streamReuseLastLinkEnabled
            lastPreferredAudioLanguage = settings.preferredAudioLanguage
            lastSecondaryPreferredAudioLanguage = settings.secondaryPreferredAudioLanguage
            streamAutoPlayModeSetting = settings.streamAutoPlayMode
            streamAutoPlayNextEpisodeEnabledSetting = settings.streamAutoPlayNextEpisodeEnabled
            nextEpisodeThresholdModeSetting = settings.nextEpisodeThresholdMode
            nextEpisodeThresholdPercentSetting = settings.nextEpisodeThresholdPercent
            nextEpisodeThresholdMinutesBeforeEndSetting = settings.nextEpisodeThresholdMinutesBeforeEnd
            mediaSourceFactory.vodCacheSizeMode = settings.vodCacheSizeMode
            mediaSourceFactory.vodCacheSizeMb = settings.vodCacheSizeMb
            mediaSourceFactory.vodCacheWarmAheadEnabled = settings.vodCacheWarmAheadEnabled
            mediaSourceFactory.progressivePlaybackDiskMode = settings.progressivePlaybackDiskMode
            mediaSourceFactory.diskSpoolStorageLocation = settings.diskSpoolStorageLocation
            mediaSourceFactory.spoolStorageProbeResult =
                SpoolStorageProbeResult.fromJsonOrNull(settings.spoolStorageProbeResultJson)
            mediaSourceFactory.diskSpoolTargetBitrateMbps = settings.diskSpoolTargetBitrateMbps()
            applySubtitlePreferences(
                settings.subtitleStyle.preferredLanguage,
                settings.subtitleStyle.secondaryPreferredLanguage
            )
            val subtitlePreferenceChanged =
                lastSubtitlePreferredLanguage != settings.subtitleStyle.preferredLanguage ||
                    lastSubtitleSecondaryLanguage != settings.subtitleStyle.secondaryPreferredLanguage
            if (subtitlePreferenceChanged) {
                autoSubtitleSelected = false
                lastSubtitlePreferredLanguage = settings.subtitleStyle.preferredLanguage
                lastSubtitleSecondaryLanguage = settings.subtitleStyle.secondaryPreferredLanguage
                tryAutoSelectPreferredSubtitleFromAvailableTracks()
            }

            val wasEnabled = skipIntroEnabled
            skipIntroEnabled = settings.skipIntroEnabled
            if (!skipIntroEnabled) {
                if (skipIntervals.isNotEmpty() || _uiState.value.activeSkipInterval != null) {
                    skipIntervals = emptyList()
                    skipIntroFetchedKey = null
                    _uiState.update { it.copy(activeSkipInterval = null, skipIntervalDismissed = true) }
                }
            } else {
                if (!wasEnabled || skipIntroFetchedKey == null) {
                    _uiState.update { it.copy(skipIntervalDismissed = false) }
                    fetchSkipIntervals(contentId, currentSeason, currentEpisode)
                }
            }
        }
    }
}

internal fun PlayerRuntimeController.observeSubtitleTranslationSettings() {
    scope.launch {
        subtitleTranslationSettingsDataStore.settings.collectLatest { settings ->
            subtitleTranslationSettings = settings
            val configured = settings.enabled && settings.apiKey.isNotBlank()
            val wasEnabled = _uiState.value.aiSubtitlesEnabled
            if (!configured && wasEnabled) {
                disableAiSubtitles()
            }
            _uiState.update { state ->
                state.copy(
                    aiSubtitlesAvailable = configured,
                    aiSubtitlesEnabled = if (configured) state.aiSubtitlesEnabled else false,
                    isAiSubtitleTranslating = if (configured) state.isAiSubtitleTranslating else false,
                    aiSubtitleError = if (configured) state.aiSubtitleError else null
                )
            }
            if (!configured) {
                aiSubtitleTranslationJob?.cancel()
            }
            refreshBuiltInAiOverlayState()
            if (configured) {
                handleBuiltInCueGroupUpdate()
            }
        }
    }
}

internal fun PlayerRuntimeController.observeTheIntroDbSettings() {
    scope.launch {
        theIntroDbSettingsDataStore.settings.collectLatest { settings ->
            theIntroDbEnabledSetting = true
            val signature = listOf(
                true,
                settings.showIntroButton,
                settings.showRecapButton,
                settings.showCreditsButton,
                settings.showPreviewButton
            ).joinToString("|")

            val previous = lastTheIntroDbSettingsSignature
            lastTheIntroDbSettingsSignature = signature
            if (previous == null || previous == signature) return@collectLatest

            if (isAnimePrimarySkipPath()) return@collectLatest

            skipSegmentResolver.clearCachedIntervals()
            skipIntroFetchedKey = null
            skipIntervals = emptyList()

            if (!skipIntroEnabled) {
                _uiState.update {
                    it.copy(
                        activeSkipInterval = null,
                        skipIntervalDismissed = true,
                        showNextEpisodeCard = false,
                        nextEpisodeCardDismissed = false
                    )
                }
            } else {
                _uiState.update {
                    it.copy(
                        activeSkipInterval = null,
                        skipIntervalDismissed = false,
                        showNextEpisodeCard = false,
                        nextEpisodeCardDismissed = false
                    )
                }
                fetchSkipIntervals(contentId, currentSeason, currentEpisode)
            }
        }
    }
}

internal fun PlayerRuntimeController.loadSavedProgressFor(
    season: Int?,
    episode: Int?,
    routeResumeProgress: WatchProgress? = null
) {
    if (contentId == null) return
    
    scope.launch {
        pendingResumeProgress = routeResumeProgress?.takeIf(::shouldUseSavedProgressForResume)
        val progress = if (season != null && episode != null) {
            watchProgressRepository.getEpisodeProgress(
                profileId = playbackOwnerContext.ownerProfileId,
                contentId = contentId,
                season = season,
                episode = episode
            ).firstOrNull()
        } else {
            watchProgressRepository.getProgress(
                profileId = playbackOwnerContext.ownerProfileId,
                contentId = contentId
            ).firstOrNull()
        }
        
        progress?.let { saved ->
            
            if (shouldUseSavedProgressForResume(saved)) {
                pendingResumeProgress = saved
                if (backendIsReady()) {
                    _exoPlayer?.let { player ->
                        if (player.playbackState == Player.STATE_READY) {
                            tryApplyPendingResumeProgress(player)
                        }
                    }
                }
            }
        } ?: run {
            if (pendingResumeProgress != null) {
                tryApplyPendingResumeProgressForCurrentBackend()
            }
        }
    }
}

internal fun shouldUseSavedProgressForResume(progress: WatchProgress): Boolean {
    if (progress.isCompleted()) return false
    if (progress.progressPercentage >= 0.02f) return true

    val hasStartedPlayback = progress.position > 0L ||
        progress.progressPercent?.let { it > 0f } == true
    return hasStartedPlayback &&
        progress.source != WatchProgress.SOURCE_TRAKT_HISTORY &&
        progress.source != WatchProgress.SOURCE_TRAKT_SHOW_PROGRESS
}

internal fun PlayerRuntimeController.fetchSkipIntervals(id: String?, season: Int?, episode: Int?) {
    if (!skipIntroEnabled) return
    if (id.isNullOrBlank()) return

    val request = buildSkipSegmentRequest(id, season, episode)
    val key = skipSegmentResolver.cacheKeyFor(request) ?: return
    if (skipIntroFetchedKey == key) return
    skipIntroFetchedKey = key

    scope.launch {
        skipIntervals = skipSegmentResolver.resolveSkipSegments(request)
    }
}

internal fun PlayerRuntimeController.buildSkipSegmentRequest(
    id: String? = contentId,
    season: Int? = currentSeason,
    episode: Int? = currentEpisode
): SkipSegmentRequest = SkipSegmentRequest(
    contentId = id,
    currentVideoId = currentVideoId,
    season = season,
    episode = episode,
    contentType = contentType
)

internal fun PlayerRuntimeController.tryApplyPendingResumeProgress(player: Player) {
    val saved = pendingResumeProgress ?: return
    if (!player.isCurrentMediaItemSeekable) {
        pendingResumeProgress = null
        _uiState.update { it.copy(pendingSeekPosition = null) }
        return
    }
    val duration = player.duration
    val target = when {
        duration > 0L -> saved.resolveResumePosition(duration)
        saved.position > 0L -> saved.position
        else -> 0L
    }

    if (target > 0L) {
        player.seekTo(target)
        _uiState.update { it.copy(pendingSeekPosition = null) }
        pendingResumeProgress = null
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
internal fun PlayerRuntimeController.retryCurrentStreamFromStartAfter416() {
    if (hasRetriedCurrentStreamAfter416) return
    hasRetriedCurrentStreamAfter416 = true
    pendingResumeProgress = null
    scheduleDeferredPlayerReinitialize(fromPositionMs = 0L, clearResumeProgress = true)
}

@androidx.annotation.OptIn(UnstableApi::class)
internal fun PlayerRuntimeController.retryCurrentStreamAfterTimeout(fromPositionMs: Long) {
    if (timeoutRecoveryAttempts >= PlayerRuntimeController.MAX_TIMEOUT_RECOVERY_ATTEMPTS) return
    timeoutRecoveryAttempts += 1
    Log.w(
        PlayerRuntimeController.TAG,
        "Retrying stream after timeout attempt=$timeoutRecoveryAttempts/" +
            "${PlayerRuntimeController.MAX_TIMEOUT_RECOVERY_ATTEMPTS} " +
            "host=${Uri.parse(currentStreamUrl).host ?: "unknown"} " +
            "fromPositionMs=$fromPositionMs"
    )
    scheduleDeferredPlayerReinitialize(fromPositionMs = fromPositionMs)
}

@androidx.annotation.OptIn(UnstableApi::class)
internal fun PlayerRuntimeController.retryCurrentStreamAfterUnexpectedNpe(fromPositionMs: Long) {
    scheduleDeferredPlayerReinitialize(fromPositionMs = fromPositionMs)
}

internal fun PlayerRuntimeController.retryCurrentStreamAfterMediaPeriodHolderCrash(fromPositionMs: Long) {
    scheduleDeferredPlayerReinitialize(fromPositionMs = fromPositionMs)
}

internal fun PlayerRuntimeController.retryCurrentStreamWithSafeAudioFallback(fromPositionMs: Long) {
    scheduleDeferredPlayerReinitialize(fromPositionMs = fromPositionMs)
}

internal fun PlayerRuntimeController.retryCurrentStreamWithAudioDisabled(fromPositionMs: Long) {
    scheduleDeferredPlayerReinitialize(fromPositionMs = fromPositionMs)
}

internal fun PlayerRuntimeController.retryCurrentStreamWithVc1SoftwareFallback(fromPositionMs: Long) {
    scheduleDeferredPlayerReinitialize(fromPositionMs = fromPositionMs)
}

internal fun PlayerRuntimeController.retryCurrentStreamWithAv1FfmpegFallback(fromPositionMs: Long) {
    scheduleDeferredPlayerReinitialize(fromPositionMs = fromPositionMs)
}

internal fun PlayerRuntimeController.retryCurrentStreamWithVc1TrackSelectionBypass(fromPositionMs: Long) {
    scheduleDeferredPlayerReinitialize(fromPositionMs = fromPositionMs)
}

internal fun PlayerRuntimeController.retryCurrentStreamWithDv5SoftwareToneMap(fromPositionMs: Long) {
    scheduleDeferredPlayerReinitialize(fromPositionMs = fromPositionMs)
}

internal fun PlayerRuntimeController.retryCurrentStreamWithDv5HardwareToneMap(fromPositionMs: Long) {
    scheduleDeferredPlayerReinitialize(fromPositionMs = fromPositionMs)
}

internal fun PlayerRuntimeController.cancelFirstFrameWatchdog() {
    firstFrameWatchdogJob?.cancel()
    firstFrameWatchdogJob = null
}

internal fun PlayerRuntimeController.maybeScheduleFirstFrameWatchdog() {
    if (hasRenderedFirstFrame || !currentStreamHasVideoTrack) return
    val player = _exoPlayer ?: return
    if (player.playbackState != Player.STATE_READY || !player.playWhenReady) return
    if (firstFrameWatchdogJob?.isActive == true) return

    firstFrameWatchdogJob = scope.launch {
        delay(PlayerRuntimeController.FIRST_FRAME_TIMEOUT_MS)

        val livePlayer = _exoPlayer ?: return@launch
        if (hasRenderedFirstFrame) return@launch
        if (livePlayer.playbackState != Player.STATE_READY || !livePlayer.playWhenReady) return@launch

        val currentPosition = livePlayer.currentPosition
        Log.w(
            PlayerRuntimeController.TAG,
                "VIDEO_TIMEOUT: no first frame after ${PlayerRuntimeController.FIRST_FRAME_TIMEOUT_MS}ms " +
                    "mime=${currentVideoTrackMimeType ?: "unknown"} " +
                    "codecs=${currentVideoTrackCodecs ?: "unknown"} " +
                    "size=${currentVideoTrackWidth}x${currentVideoTrackHeight} " +
                    "dv5=$currentVideoTrackIsLikelyDv5 " +
                    "dv5ToneMapSetting=$isDv5SoftwareToneMapSettingEnabledForCurrentPlayback " +
                    "dv5HwToneMapSetting=$isDv5HardwareToneMapSettingEnabledForCurrentPlayback " +
                    "dv5HwToneMapNativeSupported=$isDv5HardwareToneMapNativeSupportedForCurrentPlayback " +
                    "dv5ToneMapNativeSupported=$isDv5SoftwareToneMapNativeSupportedForCurrentPlayback " +
                    "dvDisplayCapable=$isCurrentDisplayDolbyVisionCapable " +
                    "shieldDevice=$isCurrentDeviceNvidiaShield " +
                    "dv5HwToneMapActive=$isDv5HardwareToneMapActiveForCurrentPlayback " +
                    "dv5ToneMapActive=$isDv5SoftwareToneMapActiveForCurrentPlayback " +
                    "vc1=$currentVideoTrackIsLikelyVc1 " +
                    "selected=$currentVideoTrackSelected " +
                    "bestSupport=${Util.getFormatSupportString(currentVideoTrackBestSupport)} " +
                    "vc1FallbackActive=$isVc1SoftwareFallbackActiveForCurrentPlayback " +
                    "vc1TrackBypassActive=$isVc1TrackSelectionBypassActiveForCurrentPlayback " +
                "positionMs=$currentPosition " +
                "host=${Uri.parse(currentStreamUrl).host ?: "unknown"}"
        )

        if (currentVideoTrackIsLikelyVc1 && !isVc1SoftwareFallbackActiveForCurrentPlayback) {
            vc1SoftwarePreferredStreamUrls.add(currentStreamUrl)
            Log.w(
                PlayerRuntimeController.TAG,
                "VIDEO_TIMEOUT: retrying with VC-1 software-preferred decoder path " +
                    "host=${Uri.parse(currentStreamUrl).host ?: "unknown"} positionMs=$currentPosition"
            )
            retryCurrentStreamWithVc1SoftwareFallback(currentPosition)
            return@launch
        }

        if (currentVideoTrackIsLikelyVc1 &&
            !currentVideoTrackSelected &&
            isVc1SoftwareFallbackActiveForCurrentPlayback &&
            !isVc1TrackSelectionBypassActiveForCurrentPlayback
        ) {
            vc1TrackSelectionBypassStreamUrls.add(currentStreamUrl)
            Log.w(
                PlayerRuntimeController.TAG,
                "VIDEO_TIMEOUT: retrying with VC-1 track-selection bypass " +
                    "host=${Uri.parse(currentStreamUrl).host ?: "unknown"} positionMs=$currentPosition"
            )
            retryCurrentStreamWithVc1TrackSelectionBypass(currentPosition)
            return@launch
        }

        if (currentVideoTrackIsLikelyDv5 &&
            isDv5HardwareToneMapSettingEnabledForCurrentPlayback &&
            isDv5HardwareToneMapNativeSupportedForCurrentPlayback &&
            isCurrentDeviceNvidiaShield &&
            !isCurrentDisplayDolbyVisionCapable &&
            !isDv5HardwareToneMapActiveForCurrentPlayback
        ) {
            dv5HardwareToneMapPreferredStreamUrls.add(currentStreamUrl)
            dv5SoftwareToneMapPreferredStreamUrls.remove(currentStreamUrl)
            Log.w(
                PlayerRuntimeController.TAG,
                "VIDEO_TIMEOUT: retrying with DV5 hardware tone-map path " +
                    "host=${Uri.parse(currentStreamUrl).host ?: "unknown"} positionMs=$currentPosition"
            )
            retryCurrentStreamWithDv5HardwareToneMap(currentPosition)
            return@launch
        }

        if (currentVideoTrackIsLikelyDv5 &&
            isDv5SoftwareToneMapSettingEnabledForCurrentPlayback &&
            !isDv5HardwareToneMapSettingEnabledForCurrentPlayback &&
            isDv5SoftwareToneMapNativeSupportedForCurrentPlayback &&
            !isCurrentDisplayDolbyVisionCapable &&
            !isDv5HardwareToneMapActiveForCurrentPlayback &&
            !isDv5SoftwareToneMapActiveForCurrentPlayback
        ) {
            dv5SoftwareToneMapPreferredStreamUrls.add(currentStreamUrl)
            Log.w(
                PlayerRuntimeController.TAG,
                "VIDEO_TIMEOUT: retrying with DV5 software tone-map path " +
                    "host=${Uri.parse(currentStreamUrl).host ?: "unknown"} positionMs=$currentPosition"
            )
            retryCurrentStreamWithDv5SoftwareToneMap(currentPosition)
        }
    }
}

internal fun PlayerRuntimeController.scheduleDeferredPlayerReinitialize(
    fromPositionMs: Long,
    clearResumeProgress: Boolean = false
) {
    cancelFirstFrameWatchdog()
    if (clearResumeProgress) {
        pendingResumeProgress = null
    }
    _uiState.update {
        it.copy(
            pendingSeekPosition = if (fromPositionMs > 0L) fromPositionMs else null,
            error = null,
            showLoadingOverlay = it.loadingOverlayEnabled
        )
    }
    scope.launch {
        yield()
        runCatching {
            releasePlayer()
            initializePlayer(currentStreamUrl, currentHeaders)
        }.onFailure { e ->
            _uiState.update {
                it.copy(
                    error = e.message ?: "Playback error",
                    showLoadingOverlay = false,
                    showPauseOverlay = false
                )
            }
        }
    }
}
