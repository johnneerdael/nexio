package com.nexio.tv.ui.screens.player

import com.nexio.tv.R
import com.nexio.tv.data.local.InternalPlayerEngine
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal fun PlayerRuntimeController.switchInternalPlayerEngineManually() {
    switchInternalPlayerEngine(
        messageResId = R.string.player_engine_switching_manual_message
    )
}

internal fun PlayerRuntimeController.maybeAutoSwitchInternalPlayerOnStartupError(message: String): Boolean {
    if (!autoSwitchInternalPlayerOnErrorEnabled) return false
    if (hasRenderedFirstFrame) return false
    if (backendCurrentPosition() > 0L) return false
    return switchInternalPlayerEngine(
        messageResId = R.string.player_engine_switching_message,
        diagnosticMessage = message
    )
}

private fun PlayerRuntimeController.switchInternalPlayerEngine(
    messageResId: Int,
    diagnosticMessage: String? = null
): Boolean {
    if (currentStreamUrl.isBlank()) return false

    val targetEngine = when (currentInternalPlayerEngine) {
        InternalPlayerEngine.EXOPLAYER -> InternalPlayerEngine.LIBMPV
        InternalPlayerEngine.LIBMPV -> InternalPlayerEngine.EXOPLAYER
    }
    val currentPosition = backendCurrentPosition().coerceAtLeast(0L)
    if (currentPosition > 0L) {
        pendingResumeProgress = null
        _uiState.update { it.copy(pendingSeekPosition = currentPosition) }
    }

    hidePlayerEngineSwitchInfoJob?.cancel()
    currentInternalPlayerEngine = targetEngine
    val targetLabel = targetEngineLabel(targetEngine)
    _uiState.update {
        it.copy(
            error = null,
            showPauseOverlay = false,
            showLoadingOverlay = it.loadingOverlayEnabled,
            showControls = false,
            showAudioDialog = false,
            showSubtitleDialog = false,
            showSubtitleDelayOverlay = false,
            showSpeedDialog = false,
            showMoreDialog = false,
            internalPlayerEngine = targetEngine,
            showPlayerEngineSwitchInfo = true,
            playerEngineSwitchInfoText = context.getString(messageResId, targetLabel)
        )
    }

    scope.launch {
        diagnosticMessage?.let {
            android.util.Log.w(PlayerRuntimeController.TAG, "Switching internal player engine after startup error: $it")
        }
        playerSettingsDataStore.setInternalPlayerEngine(targetEngine)
        releasePlayer()
        initializePlayer(currentStreamUrl, currentHeaders)
        hidePlayerEngineSwitchInfoJob = launch {
            delay(2200)
            _uiState.update { state -> state.copy(showPlayerEngineSwitchInfo = false) }
        }
    }
    return true
}

internal fun PlayerRuntimeController.targetEngineLabel(targetEngine: InternalPlayerEngine): String {
    return when (targetEngine) {
        InternalPlayerEngine.EXOPLAYER -> context.getString(R.string.playback_engine_exoplayer)
        InternalPlayerEngine.LIBMPV -> context.getString(R.string.playback_engine_libmpv)
    }
}
