package com.nexio.tv.ui.screens.player

import com.nexio.tv.R
import kotlinx.coroutines.flow.update

internal fun PlayerRuntimeController.currentBuiltInCueTexts(): List<String> {
    return currentCueGroup.cues.mapNotNull { cue ->
        val text = cue.text?.toString()?.trim()
        text?.takeIf { it.isNotBlank() }
    }
}

internal fun PlayerRuntimeController.currentBuiltInCueGroupIsTextOnly(): Boolean {
    val cues = currentCueGroup.cues
    if (cues.isEmpty()) return false
    return cues.all { cue ->
        cue.bitmap == null && !cue.text.isNullOrBlank()
    }
}

internal fun PlayerRuntimeController.shouldUseBuiltInAiTranslation(): Boolean {
    val state = _uiState.value
    return state.aiSubtitlesEnabled &&
        state.selectedAddonSubtitle == null &&
        state.selectedSubtitleTrackIndex >= 0
}

internal fun PlayerRuntimeController.refreshBuiltInAiOverlayState() {
    _uiState.update { state ->
        state.copy(
            useBuiltInAiSubtitleOverlay = false,
            translatedBuiltInCues = emptyList()
        )
    }
}

internal fun PlayerRuntimeController.handleBuiltInCueGroupUpdate() {
    if (!shouldUseBuiltInAiTranslation()) {
        _uiState.update { state ->
            state.copy(
                aiSubtitleError = null,
                useBuiltInAiSubtitleOverlay = false,
                translatedBuiltInCues = emptyList()
            )
        }
        return
    }

    val cueGroup = currentCueGroup
    if (cueGroup.cues.isEmpty()) {
        _uiState.update {
            it.copy(aiSubtitleError = null)
        }
        return
    }

    if (!currentBuiltInCueGroupIsTextOnly()) {
        _uiState.update {
            it.copy(
                isAiSubtitleTranslating = false,
                aiSubtitleError = context.getString(R.string.subtitle_ai_translate_unsupported_builtin)
            )
        }
        return
    }

    _uiState.update {
        it.copy(aiSubtitleError = null)
    }
}
