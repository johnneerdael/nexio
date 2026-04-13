package com.nexio.tv.ui.screens.player

import androidx.media3.common.C
import androidx.media3.common.Player
import kotlinx.coroutines.flow.update

internal fun PlayerRuntimeController.backendCurrentPosition(): Long {
    return _exoPlayer?.currentPosition ?: 0L
}

internal fun PlayerRuntimeController.backendDuration(): Long {
    return _exoPlayer?.duration ?: 0L
}

internal fun PlayerRuntimeController.backendIsReady(): Boolean {
    return _exoPlayer?.playbackState == Player.STATE_READY
}

internal fun PlayerRuntimeController.backendIsPlaying(): Boolean {
    return _exoPlayer?.isPlaying == true
}

internal fun PlayerRuntimeController.backendPause() {
    _exoPlayer?.pause()
}

internal fun PlayerRuntimeController.backendPlay() {
    _exoPlayer?.play()
}

internal fun PlayerRuntimeController.backendStop() {
    _exoPlayer?.stop()
}

internal fun PlayerRuntimeController.backendSeekTo(positionMs: Long) {
    _exoPlayer?.seekTo(positionMs)
}

internal fun PlayerRuntimeController.backendSetPlaybackSpeed(speed: Float) {
    _exoPlayer?.setPlaybackSpeed(speed)
}

internal fun PlayerRuntimeController.backendSetSubtitleDelay(delayMs: Int) {
    subtitleDelayUs.set(delayMs * 1000L)
    _exoPlayer?.let { player ->
        player.setTrackSelectionParameters(
            player.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .build()
        )
    }
}

internal fun shouldResumeAutoplayAfterLifecyclePause(
    hadAutoplayIntent: Boolean,
    hasRenderedFirstFrame: Boolean,
    userPausedManually: Boolean
): Boolean {
    return hadAutoplayIntent && !hasRenderedFirstFrame && !userPausedManually
}

internal fun PlayerRuntimeController.pausePlaybackForLifecycle() {
    val player = _exoPlayer
    if (player == null) {
        resumeAutoplayAfterLifecyclePause = false
        return
    }

    resumeAutoplayAfterLifecyclePause = shouldResumeAutoplayAfterLifecyclePause(
        hadAutoplayIntent = player.playWhenReady || shouldEnforceAutoplayOnFirstReady,
        hasRenderedFirstFrame = hasRenderedFirstFrame,
        userPausedManually = userPausedManually
    )
    player.pause()
}

internal fun PlayerRuntimeController.resumePlaybackForLifecycle() {
    val player = _exoPlayer
    if (player == null) {
        resumeAutoplayAfterLifecyclePause = false
        return
    }

    if (!resumeAutoplayAfterLifecyclePause) return
    resumeAutoplayAfterLifecyclePause = false
    if (!shouldResumeAutoplayAfterLifecyclePause(
            hadAutoplayIntent = true,
            hasRenderedFirstFrame = hasRenderedFirstFrame,
            userPausedManually = userPausedManually
        )
    ) {
        return
    }
    if (_uiState.value.error != null || _uiState.value.playbackEnded) return
    if (player.playbackState == Player.STATE_ENDED) return

    player.playWhenReady = true
    player.play()
}

internal fun PlayerRuntimeController.isPlaybackCurrentlyPlaying(): Boolean {
    return _exoPlayer?.isPlaying == true
}

internal fun PlayerRuntimeController.seekPlaybackTo(positionMs: Long) {
    _exoPlayer?.seekTo(positionMs)
}

internal fun PlayerRuntimeController.setPlaybackSpeedInternal(speed: Float) {
    _exoPlayer?.setPlaybackSpeed(speed)
}

internal fun PlayerRuntimeController.setPlaybackPaused(paused: Boolean) {
    _exoPlayer?.let { player ->
        if (paused) player.pause() else player.play()
    }
}

internal fun PlayerRuntimeController.tryApplyPendingResumeProgressForCurrentBackend() {
    val saved = pendingResumeProgress ?: return
    val player = _exoPlayer ?: return
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
