package com.nexio.tv.ui.screens.player

import com.nexio.tv.ui.screens.player.translation.EmbeddedSubtitleHarvestState

internal fun PlayerRuntimeController.updateEmbeddedSubtitleHarvest() {
    val state = _uiState.value
    val selectedTrack = state.subtitleTracks.getOrNull(state.selectedSubtitleTrackIndex)
    embeddedSubtitleHarvestCoordinator.update(
        EmbeddedSubtitleHarvestState(
            streamUrl = currentStreamUrl,
            filename = currentFilename,
            headers = currentHeaders,
            selectedTrack = selectedTrack,
            selectedAddonSubtitlePresent = state.selectedAddonSubtitle != null,
            autoTranslateEnabled = state.aiSubtitlesEnabled,
            targetLanguage = state.subtitleStyle.preferredLanguage,
            settings = subtitleTranslationSettings
        )
    )
}
