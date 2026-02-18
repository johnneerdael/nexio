package com.nuvio.tv.ui.screens.player

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.TrackSelectionOverride
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal fun PlayerRuntimeController.filterEpisodeStreamsByAddon(addonName: String?) {
    val allStreams = _uiState.value.episodeAllStreams
    val filteredStreams = if (addonName == null) {
        allStreams
    } else {
        allStreams.filter { it.addonName == addonName }
    }

    _uiState.update {
        it.copy(
            episodeSelectedAddonFilter = addonName,
            episodeFilteredStreams = filteredStreams
        )
    }
}

internal fun PlayerRuntimeController.showControlsTemporarily() {
    hideSeekOverlayJob?.cancel()
    _uiState.update { it.copy(showControls = true, showSeekOverlay = false) }
    scheduleHideControls()
}

internal fun PlayerRuntimeController.showSeekOverlayTemporarily() {
    hideSeekOverlayJob?.cancel()
    _uiState.update { it.copy(showSeekOverlay = true) }
    hideSeekOverlayJob = scope.launch {
        delay(1500)
        _uiState.update { it.copy(showSeekOverlay = false) }
    }
}

internal fun PlayerRuntimeController.selectAudioTrack(trackIndex: Int) {
    _exoPlayer?.let { player ->
        val tracks = player.currentTracks
        var currentAudioIndex = 0
        
        tracks.groups.forEach { trackGroup ->
            if (trackGroup.type == C.TRACK_TYPE_AUDIO) {
                for (i in 0 until trackGroup.length) {
                    if (currentAudioIndex == trackIndex) {
                        val override = TrackSelectionOverride(trackGroup.mediaTrackGroup, i)
                        player.trackSelectionParameters = player.trackSelectionParameters
                            .buildUpon()
                            .setOverrideForType(override)
                            .build()
                        persistRememberedLinkAudioSelection(trackIndex)
                        return
                    }
                    currentAudioIndex++
                }
            }
        }
    }
}

internal fun PlayerRuntimeController.persistRememberedLinkAudioSelection(trackIndex: Int) {
    if (!streamReuseLastLinkEnabled) return

    val key = streamCacheKey ?: return
    val url = currentStreamUrl.takeIf { it.isNotBlank() } ?: return
    val streamName = _uiState.value.currentStreamName?.takeIf { it.isNotBlank() } ?: title
    val selectedTrack = _uiState.value.audioTracks.getOrNull(trackIndex)

    scope.launch {
        streamLinkCacheDataStore.save(
            contentKey = key,
            url = url,
            streamName = streamName,
            headers = currentHeaders,
            rememberedAudioLanguage = selectedTrack?.language,
            rememberedAudioName = selectedTrack?.name
        )
    }
}

internal fun PlayerRuntimeController.selectSubtitleTrack(trackIndex: Int) {
    _exoPlayer?.let { player ->
        Log.d(PlayerRuntimeController.TAG, "Selecting INTERNAL subtitle trackIndex=$trackIndex")
        val tracks = player.currentTracks
        var currentSubIndex = 0
        
        tracks.groups.forEach { trackGroup ->
            if (trackGroup.type == C.TRACK_TYPE_TEXT) {
                for (i in 0 until trackGroup.length) {
                    if (currentSubIndex == trackIndex) {
                        val override = TrackSelectionOverride(trackGroup.mediaTrackGroup, i)
                        player.trackSelectionParameters = player.trackSelectionParameters
                            .buildUpon()
                            .setOverrideForType(override)
                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                            .build()
                        return
                    }
                    currentSubIndex++
                }
            }
        }
    }
}

internal fun PlayerRuntimeController.disableSubtitles() {
    _exoPlayer?.let { player ->
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
    }
}

internal fun PlayerRuntimeController.selectAddonSubtitle(subtitle: com.nuvio.tv.domain.model.Subtitle) {
    _exoPlayer?.let { player ->
        if (_uiState.value.selectedAddonSubtitle?.id == subtitle.id) {
            return@let
        }
        Log.d(PlayerRuntimeController.TAG, "Selecting ADDON subtitle lang=${subtitle.lang} id=${subtitle.id}")

        val normalizedLang = PlayerSubtitleUtils.normalizeLanguageCode(subtitle.lang)
        pendingAddonSubtitleLanguage = normalizedLang

        
        val subtitleConfig = MediaItem.SubtitleConfiguration.Builder(
            android.net.Uri.parse(subtitle.url)
        )
            .setMimeType(PlayerSubtitleUtils.mimeTypeFromUrl(subtitle.url))
            .setLanguage(subtitle.lang)
            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
            .build()

        val currentPosition = player.currentPosition
        val playWhenReady = player.playWhenReady

        player.setMediaSource(
            mediaSourceFactory.createMediaSource(
                url = currentStreamUrl,
                headers = currentHeaders,
                subtitleConfigurations = listOf(subtitleConfig)
            ),
            currentPosition
        )
        player.prepare()
        player.playWhenReady = playWhenReady

        
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setPreferredTextLanguage(normalizedLang)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .build()
        
        _uiState.update { 
            it.copy(
                selectedAddonSubtitle = subtitle,
                selectedSubtitleTrackIndex = -1 
            )
        }
    }
}
