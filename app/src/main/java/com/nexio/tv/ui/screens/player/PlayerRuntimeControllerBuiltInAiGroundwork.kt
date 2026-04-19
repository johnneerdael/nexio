package com.nexio.tv.ui.screens.player

import com.nexio.tv.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.update

private const val AI_SUBTITLE_UNSUPPORTED_ERROR_MS = 2_000L

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

internal fun shouldAllowBuiltInAiSubtitleEnable(
    selectedAddonSubtitlePresent: Boolean,
    selectedSubtitleTrackIndex: Int,
    currentCueGroupHasCues: Boolean,
    currentCueGroupIsTextOnly: Boolean
): Boolean {
    if (selectedAddonSubtitlePresent || selectedSubtitleTrackIndex < 0) return true
    if (!currentCueGroupHasCues) return true
    return currentCueGroupIsTextOnly
}

internal fun PlayerRuntimeController.showTransientAiSubtitleError(message: String) {
    aiSubtitleErrorDismissJob?.cancel()
    _uiState.update {
        it.copy(
            aiSubtitlesEnabled = false,
            isAiSubtitleTranslating = false,
            useBuiltInAiSubtitleOverlay = false,
            translatedBuiltInCues = emptyList(),
            aiSubtitleError = message
        )
    }
    aiSubtitleErrorDismissJob = scope.launch {
        delay(AI_SUBTITLE_UNSUPPORTED_ERROR_MS)
        _uiState.update { state ->
            if (state.aiSubtitleError == message) {
                state.copy(aiSubtitleError = null)
            } else {
                state
            }
        }
    }
}

internal fun PlayerRuntimeController.refreshBuiltInAiOverlayState() {
    builtInAiSubtitleTranslationJob?.cancel()
    builtInAiSubtitleTranslationJob = null
    _uiState.update { state ->
        state.copy(
            useBuiltInAiSubtitleOverlay = false,
            translatedBuiltInCues = emptyList()
        )
    }
}

internal fun PlayerRuntimeController.handleBuiltInCueGroupUpdate() {
    if (!shouldUseBuiltInAiTranslation()) {
        builtInAiSubtitleTranslationJob?.cancel()
        builtInAiSubtitleTranslationJob = null
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
            it.copy(
                aiSubtitleError = null,
                useBuiltInAiSubtitleOverlay = false,
                translatedBuiltInCues = emptyList()
            )
        }
        return
    }

    if (!currentBuiltInCueGroupIsTextOnly()) {
        showTransientAiSubtitleError(context.getString(R.string.subtitle_ai_translate_unsupported_builtin))
        return
    }

    _uiState.update {
        it.copy(aiSubtitleError = null)
    }
}
