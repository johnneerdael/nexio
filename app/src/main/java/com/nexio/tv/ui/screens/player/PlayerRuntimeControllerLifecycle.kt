package com.nexio.tv.ui.screens.player

import android.content.Intent
import android.media.audiofx.AudioEffect
import androidx.media3.common.C
import androidx.media3.common.util.DolbyVisionCompatibility
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.flow.update

@androidx.annotation.OptIn(UnstableApi::class)
internal fun PlayerRuntimeController.releasePlayer() {
    assSsaRenderController?.release()
    assSsaRenderController = null
    activePlayerUsesAssSsaRenderer = false
    _uiState.update { it.copy(useAssSsaRenderOverlay = false) }
    flushPlaybackSnapshotForSwitchOrExit()
    playbackSessionGuard.onPlayerReleased()
    playbackIdleGateState.onPlayerSessionEnded()
    cancelFirstFrameWatchdog()
    cancelPostFirstFrameBufferingWatchdog()
    deactivateAddonSubtitleOverlay()

    notifyAudioSessionUpdate(false)

    try {
        loudnessEnhancer?.release()
        loudnessEnhancer = null
    } catch (e: Exception) {
        e.printStackTrace()
    }
    try {
        currentMediaSession?.release()
        currentMediaSession = null
    } catch (e: Exception) {
        e.printStackTrace()
    }
    progressJob?.cancel()
    hideControlsJob?.cancel()
    watchProgressSaveJob?.cancel()
    scrobbleHeartbeatJob?.cancel()
    seekProgressSyncJob?.cancel()
    frameRateProbeJob?.cancel()
    startupAfrPreflightJob?.cancel()
    startupAfrPreflightJob = null
    startupSubtitlePreparationJob?.cancel()
    startupSubtitlePreparationJob = null
    hideStreamSourceIndicatorJob?.cancel()
    hideSubtitleDelayOverlayJob?.cancel()
    nextEpisodeAutoPlayJob?.cancel()
    nextEpisodeAutoPlayJob = null
    builtInAiSubtitleTranslationJob?.cancel()
    builtInAiSubtitleTranslationJob = null
    _exoPlayer?.release()
    _exoPlayer = null
    DolbyVisionCompatibility.setMapDv7ToHevcEnabled(false)
    mediaSourceFactory.closeActiveDiskSpoolSessionForPlaybackStop()
}

internal fun PlayerRuntimeController.notifyAudioSessionUpdate(active: Boolean) {
    _exoPlayer?.let { player ->
        if (isKodiCustomAudioSinkActiveForCurrentPlayback) {
            return
        }
        if (player.audioSessionId == C.AUDIO_SESSION_ID_UNSET || player.audioSessionId <= 0) {
            return
        }
        try {
            val intent = Intent(
                if (active) AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION
                else AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION
            )
            intent.putExtra(AudioEffect.EXTRA_AUDIO_SESSION, player.audioSessionId)
            intent.putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.packageName)
            if (active) {
                intent.putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MOVIE)
            }
            context.sendBroadcast(intent)
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }
}
