package com.nexio.tv.ui.screens.player

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.exoplayer.ExoPlayer
import com.nexio.tv.domain.model.Subtitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

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
            rememberedAudioName = selectedTrack?.name,
            filename = currentFilename,
            videoHash = currentVideoHash,
            videoSize = currentVideoSize
        )
    }
}

internal fun PlayerRuntimeController.applyAddonSubtitleOverride(addonTrackId: String): Boolean {
    val player = _exoPlayer ?: return false
    player.currentTracks.groups.forEach { trackGroup ->
        if (trackGroup.type != C.TRACK_TYPE_TEXT) return@forEach
        for (i in 0 until trackGroup.length) {
            val format = trackGroup.getTrackFormat(i)
            if (format.id?.contains(addonTrackId) == true || format.id == addonTrackId) {
                val override = TrackSelectionOverride(trackGroup.mediaTrackGroup, i)
                player.trackSelectionParameters = player.trackSelectionParameters
                    .buildUpon()
                    .setOverrideForType(override)
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    .build()
                Log.i(PlayerRuntimeController.TAG, "ADDON_SUB: override matched id=${format.id} group/track=$i")
                return true
            }
        }
    }
    Log.i(PlayerRuntimeController.TAG, "ADDON_SUB: override id=$addonTrackId NOT FOUND in current tracks")
    return false
}

internal fun PlayerRuntimeController.applyAddonSubtitleOverrideByLanguage(
    language: String
): Boolean {
    val player = _exoPlayer ?: return false
    player.currentTracks.groups.forEach { trackGroup ->
        if (trackGroup.type != C.TRACK_TYPE_TEXT) return@forEach
        for (i in 0 until trackGroup.length) {
            val format = trackGroup.getTrackFormat(i)
            if (format.id?.contains(PlayerRuntimeController.ADDON_SUBTITLE_TRACK_ID_PREFIX) != true) {
                continue
            }
            if (!PlayerSubtitleUtils.matchesLanguageCode(format.language, language)) {
                continue
            }
            val override = TrackSelectionOverride(trackGroup.mediaTrackGroup, i)
            player.trackSelectionParameters = player.trackSelectionParameters
                .buildUpon()
                .setOverrideForType(override)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .build()
            Log.d(
                PlayerRuntimeController.TAG,
                "applyAddonSubtitleOverrideByLanguage: found id=${format.id} lang=${format.language} at group/track $i"
            )
            return true
        }
    }
    Log.d(
        PlayerRuntimeController.TAG,
        "applyAddonSubtitleOverrideByLanguage: track not found yet for language=$language"
    )
    return false
}

internal fun PlayerRuntimeController.selectSubtitleTrack(trackIndex: Int) {
    _exoPlayer?.let { player ->
        Log.d(PlayerRuntimeController.TAG, "Selecting INTERNAL subtitle trackIndex=$trackIndex")
        val tracks = player.currentTracks
        var currentSubIndex = 0
        
        tracks.groups.forEach { trackGroup ->
            if (trackGroup.type == C.TRACK_TYPE_TEXT) {
                for (i in 0 until trackGroup.length) {
                    val format = trackGroup.getTrackFormat(i)
                    if (format.id?.contains(PlayerRuntimeController.ADDON_SUBTITLE_TRACK_ID_PREFIX) == true) continue
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

internal fun PlayerRuntimeController.buildAddonSubtitleTrackId(subtitle: Subtitle): String {
    val urlHashSuffix = subtitle.url.hashCode().toUInt().toString(16)
    return "${PlayerRuntimeController.ADDON_SUBTITLE_TRACK_ID_PREFIX}${subtitle.id}:$urlHashSuffix"
}

internal fun PlayerRuntimeController.addonSubtitleKey(subtitle: Subtitle): String {
    return "${subtitle.id}|${subtitle.url}"
}

internal fun PlayerRuntimeController.toSubtitleConfiguration(subtitle: Subtitle): MediaItem.SubtitleConfiguration {
    val normalizedLang = PlayerSubtitleUtils.normalizeLanguageCode(subtitle.lang)
    val subtitleMimeType = PlayerSubtitleUtils.mimeTypeFromUrl(subtitle.url)
    val addonTrackId = buildAddonSubtitleTrackId(subtitle)

    return MediaItem.SubtitleConfiguration.Builder(
        android.net.Uri.parse(subtitle.url)
    )
        .setId(addonTrackId)
        .setLanguage(normalizedLang)
        .setMimeType(subtitleMimeType)
        .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
        .build()
}

internal fun PlayerRuntimeController.selectAddonSubtitle(
    subtitle: Subtitle,
    selectedSubtitle: Subtitle = subtitle
) {
    _exoPlayer?.let { player ->
        Log.i(
            PlayerRuntimeController.TAG,
            "ADDON_SUB: select id=${subtitle.id} lang=${subtitle.lang} url=${subtitle.url.take(120)}"
        )
        deactivateAddonSubtitleOverlay()
        assSsaRenderController?.clearOverlay()

        val normalizedLang = PlayerSubtitleUtils.normalizeLanguageCode(subtitle.lang)
        val subtitleMimeType = PlayerSubtitleUtils.mimeTypeFromUrl(subtitle.url)
        val addonTrackId = buildAddonSubtitleTrackId(subtitle)
        val preAttachedByStartup = attachedAddonSubtitleKeys.contains(addonSubtitleKey(subtitle))
        Log.i(
            PlayerRuntimeController.TAG,
            "ADDON_SUB: normalizedLang=$normalizedLang trackId=$addonTrackId preAttached=$preAttachedByStartup mime=$subtitleMimeType"
        )
        val appliedWithoutReload = applyAddonSubtitleOverride(addonTrackId) ||
            (preAttachedByStartup && applyAddonSubtitleOverrideByLanguage(normalizedLang))

        if (appliedWithoutReload) {
            Log.i(
                PlayerRuntimeController.TAG,
                "ADDON_SUB: fast-path applied (no media reload) id=${subtitle.id}"
            )
            pendingAddonSubtitleLanguage = null
            pendingAddonSubtitleTrackId = null
            pendingAudioSelectionAfterSubtitleRefresh = null

            _uiState.update {
                it.copy(
                    selectedAddonSubtitle = selectedSubtitle,
                    selectedSubtitleTrackIndex = -1,
                    addonOverlayCues = emptyList()
                )
            }
            return@let
        }

        val fallback = {
            selectAddonSubtitleViaMediaSourceRefresh(
                subtitle = subtitle,
                selectedSubtitle = selectedSubtitle,
                player = player,
                normalizedLang = normalizedLang,
                addonTrackId = addonTrackId
            )
        }
        if (addonSubtitleSupportsOverlay(subtitleMimeType)) {
            Log.i(
                PlayerRuntimeController.TAG,
                "ADDON_SUB: overlay-path selected (no media reload) id=${subtitle.id} mime=$subtitleMimeType"
            )
            assSsaPipelineOverrideForCurrentStream = false
            activateAddonSubtitleOverlay(
                subtitle = subtitle,
                selectedSubtitle = selectedSubtitle
            )
            return@let
        }

        fallback()
    }
}

internal fun addonSubtitleSupportsOverlay(mimeType: String): Boolean {
    return mimeType == MimeTypes.APPLICATION_SUBRIP || mimeType == MimeTypes.TEXT_VTT
}

internal fun PlayerRuntimeController.selectAddonSubtitleViaMediaSourceRefresh(
    subtitle: Subtitle,
    selectedSubtitle: Subtitle,
    player: ExoPlayer,
    normalizedLang: String,
    addonTrackId: String
) {
    pendingAddonSubtitleLanguage = normalizedLang
    pendingAddonSubtitleTrackId = addonTrackId
    pendingAudioSelectionAfterSubtitleRefresh =
        captureCurrentAudioSelectionForSubtitleRefresh(player)

    val currentPosition = player.currentPosition
    val playWhenReady = player.playWhenReady
    val previousSelectedAddonSubtitle = _uiState.value.selectedAddonSubtitle

    // Suppress the buffering/loading UI during the swap so the user
    // doesn't see a black flash + loading overlay for an .srt change.
    _uiState.update { it.copy(isSwappingAddonSubtitle = true) }

    Log.i(
        PlayerRuntimeController.TAG,
        "ADDON_SUB: slow-path entry pos=$currentPosition pwr=$playWhenReady"
    )

    scope.launch {
        // Use the addon-provided subtitle URL directly. A previous
        // attempt to pre-download the .srt to a local cache was reverted
        // because many addons require specific request headers that a
        // bare HttpURLConnection cannot replicate, which caused Media3
        // to receive HTML/error bytes and silently drop the track.
        // Media3's own DataSource (with the addon URL) handles the fetch
        // correctly, so let it do the work; the swap-flag below still
        // suppresses the visible rebuffer flash.
        val subtitleConfigurations = (_uiState.value.addonSubtitles + subtitle)
            .distinctBy { "${it.id}|${it.url}" }
            .map(::toSubtitleConfiguration)
        attachedAddonSubtitleKeys = (_uiState.value.addonSubtitles + subtitle)
            .distinctBy { addonSubtitleKey(it) }
            .map(::addonSubtitleKey)
            .toSet()
        Log.i(
            PlayerRuntimeController.TAG,
            "ADDON_SUB: building refreshed source subs=${subtitleConfigurations.size} " +
                "configs=${subtitleConfigurations.map { "${it.id}|${it.language}|${it.mimeType}" }}"
        )

        val createStartMs = System.currentTimeMillis()
        val refreshedMediaSource = withTimeoutOrNull(SUBTITLE_SWAP_TIMEOUT_MS) {
            withContext(Dispatchers.IO) {
                mediaSourceFactory.createMediaSource(
                    url = currentStreamUrl,
                    headers = currentHeaders,
                    subtitleConfigurations = subtitleConfigurations
                )
            }
        }

        if (refreshedMediaSource == null) {
            Log.w(
                PlayerRuntimeController.TAG,
                "ADDON_SUB: createMediaSource TIMEOUT after ${System.currentTimeMillis() - createStartMs}ms; aborting swap"
            )
            _uiState.update {
                it.copy(
                    isSwappingAddonSubtitle = false,
                    selectedAddonSubtitle = previousSelectedAddonSubtitle
                )
            }
            pendingAddonSubtitleLanguage = null
            pendingAddonSubtitleTrackId = null
            pendingAudioSelectionAfterSubtitleRefresh = null
            return@launch
        }

        Log.i(
            PlayerRuntimeController.TAG,
            "ADDON_SUB: createMediaSource ok in ${System.currentTimeMillis() - createStartMs}ms; calling setMediaSource+prepare"
        )
        player.setMediaSource(refreshedMediaSource, currentPosition)
        player.prepare()
        player.playWhenReady = playWhenReady

        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setPreferredTextLanguage(normalizedLang)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .build()
        Log.i(
            PlayerRuntimeController.TAG,
            "ADDON_SUB: trackSelectionParameters set preferredText=$normalizedLang; awaiting onTracksChanged"
        )

        _uiState.update {
            it.copy(
                selectedAddonSubtitle = selectedSubtitle,
                selectedSubtitleTrackIndex = -1,
                addonOverlayCues = emptyList()
            )
        }
        // Safety net: clear the swap flag after a bounded delay even if
        // STATE_READY never fires (e.g. surface paused). The buffering
        // observer will also clear it on the first STATE_READY.
        scope.launch {
            delay(SUBTITLE_SWAP_TIMEOUT_MS)
            if (_uiState.value.isSwappingAddonSubtitle) {
                _uiState.update { it.copy(isSwappingAddonSubtitle = false) }
            }
        }
    }
}

private const val SUBTITLE_SWAP_TIMEOUT_MS: Long = 15_000L

/**
 * Downloads the addon subtitle to a local cache file with fsync so the
 * resulting file URI is safe to hand to Media3 immediately. Returns a
 * Subtitle whose `url` points at the cached file, or null if the download
 * failed (caller will fall back to the original remote URL).
 */
private fun PlayerRuntimeController.materializeAddonSubtitleToCache(
    subtitle: Subtitle
): Subtitle? {
    val originalUrl = subtitle.url
    if (originalUrl.startsWith("file:")) return subtitle

    return try {
        val cacheRoot = File(context.cacheDir, "addon-subtitles").apply { mkdirs() }
        val extension = PlayerSubtitleUtils.mimeTypeFromUrl(originalUrl)
            .let { mime ->
                when {
                    mime.endsWith("srt", ignoreCase = true) -> "srt"
                    mime.endsWith("vtt", ignoreCase = true) -> "vtt"
                    originalUrl.lowercase().substringBefore('?').endsWith(".vtt") -> "vtt"
                    else -> "srt"
                }
            }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(originalUrl.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val cacheFile = File(cacheRoot, "$digest.$extension")

        if (!cacheFile.exists() || cacheFile.length() == 0L) {
            val connection = (URL(originalUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8_000
                readTimeout = 8_000
                instanceFollowRedirects = true
                requestMethod = "GET"
            }
            try {
                if (connection.responseCode !in 200..299) {
                    Log.w(
                        PlayerRuntimeController.TAG,
                        "Addon subtitle download failed http=${connection.responseCode}"
                    )
                    return null
                }
                connection.inputStream.use { input ->
                    FileOutputStream(cacheFile).use { fos ->
                        input.copyTo(fos)
                        fos.flush()
                        try {
                            fos.fd.sync()
                        } catch (_: Throwable) {
                            // sync may not be supported on every fs; ignore.
                        }
                    }
                }
            } finally {
                connection.disconnect()
            }
        }

        if (!cacheFile.exists() || cacheFile.length() == 0L) {
            Log.w(PlayerRuntimeController.TAG, "Addon subtitle cache file empty after write")
            return null
        }
        subtitle.copy(url = cacheFile.toURI().toString())
    } catch (t: Throwable) {
        Log.w(PlayerRuntimeController.TAG, "materializeAddonSubtitleToCache failed: ${t.message}")
        null
    }
}

internal fun PlayerRuntimeController.captureCurrentAudioSelectionForSubtitleRefresh(
    player: Player
): PlayerRuntimeController.PendingAudioSelection? {
    val state = _uiState.value
    state.audioTracks.getOrNull(state.selectedAudioTrackIndex)?.let { selected ->
        return PlayerRuntimeController.PendingAudioSelection(
            language = selected.language,
            name = selected.name,
            streamUrl = currentStreamUrl
        )
    }

    player.currentTracks.groups.forEach { trackGroup ->
        if (trackGroup.type != C.TRACK_TYPE_AUDIO) return@forEach
        for (i in 0 until trackGroup.length) {
            if (trackGroup.isTrackSelected(i)) {
                val format = trackGroup.getTrackFormat(i)
                return PlayerRuntimeController.PendingAudioSelection(
                    language = format.language,
                    name = format.label ?: format.language,
                    streamUrl = currentStreamUrl
                )
            }
        }
    }
    return null
}
