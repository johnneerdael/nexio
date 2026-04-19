package com.nexio.tv.ui.screens.player

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.text.CueGroup
import androidx.media3.exoplayer.text.CueGroupSubtitleTranslator
import com.nexio.tv.data.repository.SubtitleTranslationService
import com.nexio.tv.domain.model.SubtitleTranslationSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

private const val BUILT_IN_SUBTITLE_PREFETCH_DURATION_US = 20L * 60L * 1_000_000L
private const val BUILT_IN_SUBTITLE_PROVIDER_FAILURE_COOLDOWN_MS = 60_000L

internal class BuiltInSubtitleCueTranslator(
    private val scope: CoroutineScope,
    private val translationService: SubtitleTranslationService,
    private val isEnabledProvider: () -> Boolean,
    private val settingsProvider: () -> SubtitleTranslationSettings,
    private val targetLanguageProvider: () -> String?,
    private val onTranslatingChanged: (Boolean) -> Unit,
    private val onTranslationError: (String?) -> Unit,
    private val providerFailureCooldownMs: Long = BUILT_IN_SUBTITLE_PROVIDER_FAILURE_COOLDOWN_MS,
    private val nowMs: () -> Long = { System.currentTimeMillis() }
) : CueGroupSubtitleTranslator {

    private val activeRequestCount = AtomicInteger(0)
    private val suppressedProviderFailure = AtomicReference<SuppressedProviderFailure?>(null)

    override fun getConfigurationToken(format: Format): String? {
        if (!isEnabledProvider()) {
            return null
        }
        if (format.isAssSsaCueTranslationUnsupported()) {
            return null
        }
        val settings = settingsProvider()
        val targetLanguage = targetLanguageProvider()?.trim().orEmpty()
        if (!settings.enabled || settings.apiKey.isBlank() || targetLanguage.isBlank()) {
            return null
        }
        return builtInTranslationConfigurationToken(format, settings, targetLanguage)
    }

    override fun getPrefetchDurationUs(): Long = BUILT_IN_SUBTITLE_PREFETCH_DURATION_US

    override fun translate(
        format: Format,
        cueGroups: List<CueGroup>,
        callback: CueGroupSubtitleTranslator.TranslationCallback
    ) {
        val settings = settingsProvider()
        val targetLanguage = targetLanguageProvider()?.trim().orEmpty()
        if (!isEnabledProvider() || !settings.enabled || settings.apiKey.isBlank() || targetLanguage.isBlank()) {
            callback.onFailure(IllegalStateException("Built-in subtitle translation is not configured."))
            return
        }

        if (cueGroups.isEmpty()) {
            callback.onSuccess(emptyList())
            return
        }

        val configurationToken = builtInTranslationConfigurationToken(format, settings, targetLanguage)
        suppressedProviderFailure.get()?.let { failure ->
            if (failure.configurationToken == configurationToken && failure.retryAfterMs > nowMs()) {
                onTranslationError(failure.message)
                callback.onFailure(Exception(failure.message))
                return
            }
            if (failure.configurationToken != configurationToken || failure.retryAfterMs <= nowMs()) {
                suppressedProviderFailure.compareAndSet(failure, null)
            }
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
                    sourceLanguageCode = format.language,
                    settings = settings
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
                    suppressedProviderFailure.get()?.let { failure ->
                        if (failure.configurationToken == configurationToken) {
                            suppressedProviderFailure.compareAndSet(failure, null)
                        }
                    }
                    callback.onSuccess(translatedCueGroups)
                }.onFailure { error ->
                    val message = error.message?.takeIf(String::isNotBlank)
                        ?: "Failed to translate subtitle."
                    suppressedProviderFailure.set(
                        SuppressedProviderFailure(
                            configurationToken = configurationToken,
                            message = message,
                            retryAfterMs = nowMs() + providerFailureCooldownMs
                        )
                    )
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

    private fun builtInTranslationConfigurationToken(
        format: Format,
        settings: SubtitleTranslationSettings,
        targetLanguage: String
    ): String {
        return "${format.sampleMimeType}|${format.language.orEmpty()}|$targetLanguage|${settings.provider}|${settings.model}|${settings.baseUrl}|${settings.apiKey.hashCode()}"
    }

    private data class SuppressedProviderFailure(
        val configurationToken: String,
        val message: String,
        val retryAfterMs: Long
    )
}

private fun Format.isAssSsaCueTranslationUnsupported(): Boolean {
    if (sampleMimeType == MimeTypes.TEXT_SSA || sampleMimeType == "text/x-ass") {
        return true
    }
    return codecs
        ?.split(',')
        ?.map { it.trim().lowercase(Locale.US) }
        ?.any { codec -> codec == MimeTypes.TEXT_SSA || codec == "text/x-ass" }
        ?: false
}
