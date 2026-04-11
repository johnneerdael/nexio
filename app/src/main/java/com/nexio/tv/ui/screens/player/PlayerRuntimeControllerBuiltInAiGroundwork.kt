package com.nexio.tv.ui.screens.player

import androidx.media3.common.text.Cue
import com.nexio.tv.R
import com.nexio.tv.domain.model.SubtitleTranslationSettings
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
    builtInAiCueGeneration += 1L
    builtInAiSubtitleTranslationJob?.cancel()
    builtInAiSubtitleTranslationJob = null
    _uiState.update { state ->
        state.copy(
            useBuiltInAiSubtitleOverlay = false,
            translatedBuiltInCues = emptyList()
        )
    }
}

internal fun builtInCueTranslationCacheKey(
    text: String,
    targetLanguage: String,
    settings: SubtitleTranslationSettings
): String {
    val settingsSignature = "${settings.provider}|${settings.model.trim()}|${settings.baseUrl.trim()}"
    return "${targetLanguage.trim().lowercase()}|$settingsSignature|${text.trim()}"
}

internal fun translateBuiltInCuesWhenAllTextsReady(
    cues: List<Cue>,
    translatedTexts: Map<String, String>
): List<Cue> {
    if (cues.isEmpty()) return emptyList()
    return cues.map { cue ->
        val sourceText = cue.text?.toString()?.trim()?.takeIf { it.isNotBlank() }
            ?: return emptyList()
        val translatedText = translatedTexts[sourceText]?.takeIf { it.isNotBlank() }
            ?: return emptyList()
        cue.buildUpon()
            .setText(translatedText)
            .build()
    }
}

internal fun PlayerRuntimeController.handleBuiltInCueGroupUpdate() {
    if (!shouldUseBuiltInAiTranslation()) {
        builtInAiCueGeneration += 1L
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
        builtInAiCueGeneration += 1L
        builtInAiSubtitleTranslationJob?.cancel()
        builtInAiSubtitleTranslationJob = null
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
        builtInAiCueGeneration += 1L
        builtInAiSubtitleTranslationJob?.cancel()
        builtInAiSubtitleTranslationJob = null
        _uiState.update {
            it.copy(
                isAiSubtitleTranslating = false,
                useBuiltInAiSubtitleOverlay = false,
                translatedBuiltInCues = emptyList(),
                aiSubtitleError = context.getString(R.string.subtitle_ai_translate_unsupported_builtin)
            )
        }
        return
    }

    val targetLanguage = _uiState.value.subtitleStyle.preferredLanguage.trim()
    val settings = subtitleTranslationSettings
    val sourceTexts = currentBuiltInCueTexts().distinct()
    val cachedTranslations = sourceTexts.mapNotNull { text ->
        builtInAiCueTranslationCache[
            builtInCueTranslationCacheKey(text, targetLanguage, settings)
        ]
            ?.let { translatedText -> text to translatedText }
    }.toMap()
    val cachedCues = translateBuiltInCuesWhenAllTextsReady(cueGroup.cues, cachedTranslations)
    if (cachedCues.isNotEmpty()) {
        builtInAiCueGeneration += 1L
        builtInAiSubtitleTranslationJob?.cancel()
        builtInAiSubtitleTranslationJob = null
        _uiState.update {
            it.copy(
                aiSubtitleError = null,
                isAiSubtitleTranslating = false,
                useBuiltInAiSubtitleOverlay = true,
                translatedBuiltInCues = cachedCues
            )
        }
        return
    }

    val requestGeneration = builtInAiCueGeneration + 1L
    builtInAiCueGeneration = requestGeneration
    builtInAiSubtitleTranslationJob?.cancel()
    _uiState.update {
        it.copy(
            aiSubtitleError = null,
            isAiSubtitleTranslating = true,
            useBuiltInAiSubtitleOverlay = true,
            translatedBuiltInCues = emptyList()
        )
    }

    builtInAiSubtitleTranslationJob = scope.launch {
        val result = subtitleTranslationService.translateCueTexts(
            texts = sourceTexts,
            targetLanguageCode = targetLanguage,
            settings = settings
        )
        if (requestGeneration != builtInAiCueGeneration) {
            return@launch
        }
        result
            .onSuccess { translatedTexts ->
                translatedTexts.forEach { (sourceText, translatedText) ->
                    if (translatedText.isNotBlank()) {
                        builtInAiCueTranslationCache[
                            builtInCueTranslationCacheKey(sourceText, targetLanguage, settings)
                        ] = translatedText
                    }
                }
                _uiState.update {
                    it.copy(
                        aiSubtitleError = null,
                        isAiSubtitleTranslating = false,
                        useBuiltInAiSubtitleOverlay = true,
                        translatedBuiltInCues = translateBuiltInCuesWhenAllTextsReady(
                            cues = cueGroup.cues,
                            translatedTexts = translatedTexts
                        )
                    )
                }
            }
            .onFailure { error ->
                if (error is CancellationException) {
                    return@onFailure
                }
                _uiState.update {
                    it.copy(
                        isAiSubtitleTranslating = false,
                        useBuiltInAiSubtitleOverlay = true,
                        translatedBuiltInCues = emptyList(),
                        aiSubtitleError = error.message?.takeIf { message -> message.isNotBlank() }
                            ?: context.getString(R.string.subtitle_ai_translate_failed)
                    )
                }
            }
    }
}
