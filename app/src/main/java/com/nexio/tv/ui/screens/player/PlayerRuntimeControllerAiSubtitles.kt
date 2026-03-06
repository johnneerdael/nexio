package com.nexio.tv.ui.screens.player

import androidx.media3.common.MimeTypes
import com.nexio.tv.R
import com.nexio.tv.domain.model.Subtitle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.update

internal fun PlayerRuntimeController.toggleAiSubtitles() {
    if (_uiState.value.aiSubtitlesEnabled) {
        disableAiSubtitles()
    } else {
        enableAiSubtitles()
    }
}

internal fun PlayerRuntimeController.selectAddonSubtitleRespectingAi(subtitle: Subtitle) {
    if (_uiState.value.aiSubtitlesEnabled) {
        translateAndSelectAddonSubtitle(subtitle)
    } else {
        selectAddonSubtitle(subtitle)
    }
}

internal fun PlayerRuntimeController.enableAiSubtitles() {
    if (!geminiEnabled || geminiApiKey.isBlank()) {
        _uiState.update {
            it.copy(
                aiSubtitlesEnabled = false,
                aiSubtitleError = context.getString(R.string.subtitle_ai_translate_missing_key)
            )
        }
        return
    }

    val targetLanguage = _uiState.value.subtitleStyle.preferredLanguage
    if (targetLanguage.isBlank() || targetLanguage.equals("none", ignoreCase = true)) {
        _uiState.update {
            it.copy(
                aiSubtitlesEnabled = false,
                aiSubtitleError = context.getString(R.string.subtitle_ai_translate_missing_target)
            )
        }
        return
    }

    _uiState.update {
        it.copy(
            aiSubtitlesEnabled = true,
            aiSubtitleError = null
        )
    }
    refreshBuiltInAiOverlayState()

    val selectedAddonSubtitle = _uiState.value.selectedAddonSubtitle
    if (selectedAddonSubtitle == null && _uiState.value.selectedSubtitleTrackIndex >= 0) {
        handleBuiltInCueGroupUpdate()
        return
    }

    selectedAddonSubtitle?.let { subtitle ->
        translateAndSelectAddonSubtitle(subtitle)
    }
}

internal fun PlayerRuntimeController.disableAiSubtitles() {
    aiTranslationSelectionGeneration += 1L
    aiSubtitleTranslationJob?.cancel()
    aiSubtitleTranslationJob = null

    val selectedAddonSubtitle = _uiState.value.selectedAddonSubtitle
    _uiState.update {
        it.copy(
            aiSubtitlesEnabled = false,
            isAiSubtitleTranslating = false,
            aiSubtitleError = null,
            translatedBuiltInCues = emptyList()
        )
    }
    refreshBuiltInAiOverlayState()

    if (selectedAddonSubtitle != null) {
        selectAddonSubtitle(selectedAddonSubtitle)
    }
}

internal fun PlayerRuntimeController.translateAndSelectAddonSubtitle(sourceSubtitle: Subtitle) {
    refreshBuiltInAiOverlayState()
    if (!supportsAiTranslation(sourceSubtitle)) {
        _uiState.update {
            it.copy(
                isAiSubtitleTranslating = false,
                aiSubtitleError = context.getString(R.string.subtitle_ai_translate_unsupported_format)
            )
        }
        return
    }

    val requestGeneration = aiTranslationSelectionGeneration + 1L
    aiTranslationSelectionGeneration = requestGeneration
    aiSubtitleTranslationJob?.cancel()
    _uiState.update {
        it.copy(
            aiSubtitlesEnabled = true,
            isAiSubtitleTranslating = true,
            aiSubtitleError = null
        )
    }

    val targetLanguage = _uiState.value.subtitleStyle.preferredLanguage
    aiSubtitleTranslationJob = scope.launch {
        val result = geminiSubtitleTranslationService.translateSubtitle(
            sourceSubtitle = sourceSubtitle,
            targetLanguageCode = targetLanguage,
            apiKey = geminiApiKey
        )

        if (requestGeneration != aiTranslationSelectionGeneration) {
            return@launch
        }

        result
            .onSuccess { asset ->
                selectAddonSubtitle(
                    subtitle = asset.translatedSubtitle,
                    selectedSubtitle = asset.sourceSubtitle
                )
                _uiState.update {
                    it.copy(
                        isAiSubtitleTranslating = false,
                        aiSubtitleError = null
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
                        aiSubtitleError = error.message?.takeIf { message -> message.isNotBlank() }
                            ?: context.getString(R.string.subtitle_ai_translate_failed)
                    )
                }
            }
    }
}

private fun supportsAiTranslation(subtitle: Subtitle): Boolean {
    return when (PlayerSubtitleUtils.mimeTypeFromUrl(subtitle.url)) {
        MimeTypes.APPLICATION_SUBRIP,
        MimeTypes.TEXT_VTT -> true
        else -> false
    }
}
