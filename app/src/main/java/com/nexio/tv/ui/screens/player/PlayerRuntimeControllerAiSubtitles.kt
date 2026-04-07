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
    if (_uiState.value.aiSubtitlesEnabled && !addonSubtitleIsAlreadyInPrimaryLanguage(subtitle)) {
        translateAndSelectAddonSubtitle(subtitle)
        return
    }
    // Manual pick of a primary-language sub: AI translation would be a no-op
    // (Dutch → Dutch). Clear the AI flag silently so the picker shows only
    // one selected source instead of both the AI toggle and the addon entry.
    if (_uiState.value.aiSubtitlesEnabled || _uiState.value.isAiSubtitleTranslating) {
        clearAiTranslationStateSilently()
    }
    selectAddonSubtitle(subtitle)
}

private fun PlayerRuntimeController.addonSubtitleIsAlreadyInPrimaryLanguage(
    subtitle: Subtitle
): Boolean {
    val normalizedSubLang = PlayerSubtitleUtils.normalizeLanguageCode(subtitle.lang)
    val normalizedPrimary = PlayerSubtitleUtils.normalizeLanguageCode(
        _uiState.value.subtitleStyle.preferredLanguage
    )
    return normalizedSubLang.isNotBlank() &&
        normalizedPrimary.isNotBlank() &&
        normalizedSubLang == normalizedPrimary
}

internal fun PlayerRuntimeController.clearAiTranslationStateSilently() {
    aiTranslationSelectionGeneration += 1L
    aiSubtitleTranslationJob?.cancel()
    aiSubtitleTranslationJob = null
    _uiState.update {
        it.copy(
            aiSubtitlesEnabled = false,
            isAiSubtitleTranslating = false,
            aiSubtitleError = null,
            translatedBuiltInCues = emptyList()
        )
    }
    refreshBuiltInAiOverlayState()
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
                if (_uiState.value.selectedAddonSubtitle?.url != sourceSubtitle.url) {
                    selectAddonSubtitle(sourceSubtitle)
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
