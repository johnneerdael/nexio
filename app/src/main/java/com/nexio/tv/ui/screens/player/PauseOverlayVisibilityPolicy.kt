package com.nexio.tv.ui.screens.player

internal fun shouldShowPauseOverlayAfterDelay(state: PlayerUiState): Boolean {
    val anyPanelOpen = state.showSubtitleDialog ||
        state.showSpeedDialog ||
        state.showMoreDialog ||
        state.showEpisodesPanel ||
        state.showSourcesPanel ||
        state.showAudioDialog

    return !state.isPlaying &&
        state.pauseOverlayEnabled &&
        state.error == null &&
        !anyPanelOpen
}
