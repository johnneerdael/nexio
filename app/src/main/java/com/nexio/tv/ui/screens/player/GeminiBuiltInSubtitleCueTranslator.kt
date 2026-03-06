package com.nexio.tv.ui.screens.player

import androidx.media3.common.Format
import androidx.media3.common.text.CueGroup
import androidx.media3.exoplayer.text.CueGroupSubtitleTranslator
import com.nexio.tv.data.repository.GeminiSubtitleTranslationService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

private const val BUILT_IN_SUBTITLE_PREFETCH_DURATION_US = 20_000_000L

internal class GeminiBuiltInSubtitleCueTranslator(
    private val scope: CoroutineScope,
    private val translationService: GeminiSubtitleTranslationService,
    private val isEnabledProvider: () -> Boolean,
    private val apiKeyProvider: () -> String,
    private val targetLanguageProvider: () -> String?,
    private val onTranslatingChanged: (Boolean) -> Unit,
    private val onTranslationError: (String?) -> Unit
) : CueGroupSubtitleTranslator {

    private val activeRequestCount = AtomicInteger(0)

    override fun getConfigurationToken(format: Format): String? {
        if (!isEnabledProvider()) {
            return null
        }
        val apiKey = apiKeyProvider().trim()
        val targetLanguage = targetLanguageProvider()?.trim().orEmpty()
        if (apiKey.isBlank() || targetLanguage.isBlank()) {
            return null
        }
        return "${format.sampleMimeType}|$targetLanguage|${apiKey.hashCode()}"
    }

    override fun getPrefetchDurationUs(): Long = BUILT_IN_SUBTITLE_PREFETCH_DURATION_US

    override fun translate(
        format: Format,
        cueGroups: List<CueGroup>,
        callback: CueGroupSubtitleTranslator.TranslationCallback
    ) {
        val apiKey = apiKeyProvider().trim()
        val targetLanguage = targetLanguageProvider()?.trim().orEmpty()
        if (!isEnabledProvider() || apiKey.isBlank() || targetLanguage.isBlank()) {
            callback.onFailure(IllegalStateException("Built-in subtitle translation is not configured."))
            return
        }

        if (cueGroups.isEmpty()) {
            callback.onSuccess(emptyList())
            return
        }

        updateActiveRequests(delta = 1)
        scope.launch(Dispatchers.IO) {
            try {
                val sourceTexts = cueGroups
                    .flatMap { cueGroup -> cueGroup.cues }
                    .mapNotNull { cue -> cue.text?.toString()?.trim()?.takeIf(String::isNotBlank) }
                    .distinct()

                if (sourceTexts.isEmpty()) {
                    onTranslationError(null)
                    callback.onSuccess(cueGroups)
                    return@launch
                }

                translationService.translateCueTexts(
                    texts = sourceTexts,
                    targetLanguageCode = targetLanguage,
                    apiKey = apiKey
                ).onSuccess { translatedTexts ->
                    val translatedCueGroups = cueGroups.map { cueGroup ->
                        CueGroup(
                            cueGroup.cues.map { cue ->
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
                            },
                            cueGroup.presentationTimeUs
                        )
                    }
                    onTranslationError(null)
                    callback.onSuccess(translatedCueGroups)
                }.onFailure { error ->
                    val message = error.message?.takeIf(String::isNotBlank)
                        ?: "Failed to translate subtitle."
                    onTranslationError(message)
                    callback.onFailure(Exception(message, error))
                }
            } finally {
                updateActiveRequests(delta = -1)
            }
        }
    }

    private fun updateActiveRequests(delta: Int) {
        val active = (activeRequestCount.addAndGet(delta)).coerceAtLeast(0)
        if (active == 0 && delta < 0) {
            activeRequestCount.set(0)
        }
        onTranslatingChanged(active > 0)
    }
}
