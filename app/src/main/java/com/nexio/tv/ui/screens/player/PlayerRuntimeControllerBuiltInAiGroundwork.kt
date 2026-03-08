package com.nexio.tv.ui.screens.player

import com.nexio.tv.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
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
    builtInAiSubtitleTranslationJob?.cancel()
    builtInAiSubtitleTranslationJob = null
    if (usesLibmpvBackend()) {
        setLibmpvSubtitleVisibility(true)
    }
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
        if (usesLibmpvBackend()) {
            setLibmpvSubtitleVisibility(true)
        }
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
        if (usesLibmpvBackend()) {
            setLibmpvSubtitleVisibility(true)
            _uiState.update {
                it.copy(
                    aiSubtitleError = null,
                    useBuiltInAiSubtitleOverlay = false,
                    translatedBuiltInCues = emptyList()
                )
            }
        } else {
        _uiState.update {
            it.copy(aiSubtitleError = null)
        }
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

    if (usesLibmpvBackend()) {
        translateCurrentLibmpvBuiltInCues(cueGroup)
    }
}

private fun PlayerRuntimeController.translateCurrentLibmpvBuiltInCues(
    cueGroup: androidx.media3.common.text.CueGroup
) {
    val apiKey = geminiApiKey.trim()
    val targetLanguage = _uiState.value.subtitleStyle.preferredLanguage.trim()
    if (apiKey.isBlank() || targetLanguage.isBlank() || targetLanguage.equals("none", ignoreCase = true)) {
        setLibmpvSubtitleVisibility(true)
        _uiState.update {
            it.copy(
                isAiSubtitleTranslating = false,
                useBuiltInAiSubtitleOverlay = false,
                translatedBuiltInCues = emptyList()
            )
        }
        return
    }

    val sourceTexts = cueGroup.cues
        .mapNotNull { cue -> cue.text?.toString()?.trim()?.takeIf(String::isNotBlank) }
        .distinct()
    if (sourceTexts.isEmpty()) {
        setLibmpvSubtitleVisibility(true)
        _uiState.update {
            it.copy(
                isAiSubtitleTranslating = false,
                useBuiltInAiSubtitleOverlay = false,
                translatedBuiltInCues = emptyList()
            )
        }
        return
    }

    val generation = builtInAiCueGeneration + 1L
    builtInAiCueGeneration = generation
    builtInAiSubtitleTranslationJob?.cancel()
    _uiState.update {
        it.copy(
            isAiSubtitleTranslating = true,
            aiSubtitleError = null
        )
    }

    builtInAiSubtitleTranslationJob = scope.launch {
        geminiSubtitleTranslationService.translateCueTexts(
            texts = sourceTexts,
            targetLanguageCode = targetLanguage,
            apiKey = apiKey
        ).onSuccess { translatedTexts ->
            if (generation != builtInAiCueGeneration || !shouldUseBuiltInAiTranslation()) {
                return@onSuccess
            }
            val translatedCues = cueGroup.cues.map { cue ->
                val sourceText = cue.text?.toString()?.trim()
                val translatedText = sourceText
                    ?.let { translatedTexts[it] }
                    ?.takeIf(String::isNotBlank)
                if (translatedText.isNullOrBlank()) {
                    cue
                } else {
                    cue.buildUpon()
                        .setText(translatedText)
                        .build()
                }
            }
            val overlayHasTranslatedCues = translatedCues.any { cue ->
                cue.text?.toString()?.trim()?.isNotBlank() == true
            }
            setLibmpvSubtitleVisibility(!overlayHasTranslatedCues)
            _uiState.update {
                it.copy(
                    isAiSubtitleTranslating = false,
                    aiSubtitleError = null,
                    useBuiltInAiSubtitleOverlay = overlayHasTranslatedCues,
                    translatedBuiltInCues = translatedCues
                )
            }
        }.onFailure { error ->
            if (error is CancellationException) return@onFailure
            if (generation != builtInAiCueGeneration) return@onFailure
            setLibmpvSubtitleVisibility(true)
            _uiState.update {
                it.copy(
                    isAiSubtitleTranslating = false,
                    useBuiltInAiSubtitleOverlay = false,
                    translatedBuiltInCues = emptyList(),
                    aiSubtitleError = error.message?.takeIf(String::isNotBlank)
                        ?: context.getString(R.string.subtitle_ai_translate_failed)
                )
            }
        }
    }
}
