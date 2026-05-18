package com.nexio.tv.ui.screens.player

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.text.CueGroup
import androidx.media3.exoplayer.text.CueGroupSubtitleTranslator
import com.nexio.tv.data.repository.DEFAULT_TRANSLATION_RAMP_UP_SCHEDULE
import com.nexio.tv.data.repository.SubtitleTranslationService
import com.nexio.tv.domain.model.SubtitleTranslationSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

// Long.MAX_VALUE / 2 — an effectively unbounded horizon that still leaves headroom for
// TextRenderer's `horizonUs = positionUs + prefetchDurationUs` arithmetic without overflow.
private const val BUILT_IN_SUBTITLE_PREFETCH_DURATION_US = Long.MAX_VALUE / 2
private const val BUILT_IN_SUBTITLE_PROVIDER_FAILURE_COOLDOWN_MS = 60_000L
private const val BUILT_IN_SUBTITLE_DISPATCH_DEBOUNCE_MS = 250L
private const val BUILT_IN_SUBTITLE_DISPATCH_MAX_BATCH_CUE_GROUPS = 60
private const val BUILT_IN_SUBTITLE_AHEAD_CACHE_MAX_CUE_GROUPS = 1_000

internal class BuiltInSubtitleCueTranslator(
    private val scope: CoroutineScope,
    private val translationService: SubtitleTranslationService,
    private val isEnabledProvider: () -> Boolean,
    private val settingsProvider: () -> SubtitleTranslationSettings,
    private val targetLanguageProvider: () -> String?,
    private val onTranslatingChanged: (Boolean) -> Unit,
    private val onTranslationError: (String?) -> Unit,
    private val providerFailureCooldownMs: Long = BUILT_IN_SUBTITLE_PROVIDER_FAILURE_COOLDOWN_MS,
    private val dispatchDebounceMs: Long = BUILT_IN_SUBTITLE_DISPATCH_DEBOUNCE_MS,
    private val maxBatchCueGroups: Int = BUILT_IN_SUBTITLE_DISPATCH_MAX_BATCH_CUE_GROUPS,
    private val nowMs: () -> Long = { System.currentTimeMillis() }
) : CueGroupSubtitleTranslator {

    private val activeRequestCount = AtomicInteger(0)
    private val suppressedProviderFailure = AtomicReference<SuppressedProviderFailure?>(null)
    private val aheadCacheLock = Any()
    private val aheadTranslatedCueGroupsByKey = LinkedHashMap<String, CueGroup>()
    private val aheadPendingKeys = mutableSetOf<String>()
    private var aheadCacheConfigurationToken: String? = null

    private val pendingLock = Any()
    private val pendingEntries = mutableListOf<PendingTranslate>()
    private var pendingCueGroupCount = 0
    private var flushJob: Job? = null

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

    override fun getTranslatedCueGroup(format: Format, sourceCueGroup: CueGroup): CueGroup? {
        val configurationToken = getConfigurationToken(format) ?: return null
        val key = aheadCueGroupKey(sourceCueGroup) ?: return null
        synchronized(aheadCacheLock) {
            ensureAheadCacheTokenLocked(configurationToken)
            return aheadTranslatedCueGroupsByKey[key]
        }
    }

    internal fun enqueueAheadCue(
        format: Format,
        cueGroup: CueGroup,
        callback: (List<CueGroup>) -> Unit = {}
    ) {
        val configurationToken = getConfigurationToken(format)
        if (configurationToken == null) {
            callback(emptyList())
            return
        }
        val key = aheadCueGroupKey(cueGroup)
        if (key == null) {
            callback(emptyList())
            return
        }

        synchronized(aheadCacheLock) {
            ensureAheadCacheTokenLocked(configurationToken)
            aheadTranslatedCueGroupsByKey[key]?.let { cached ->
                callback(listOf(cached))
                return
            }
            if (!aheadPendingKeys.add(key)) {
                return
            }
        }

        translate(
            format = format,
            cueGroups = listOf(cueGroup),
            callback = object : CueGroupSubtitleTranslator.TranslationCallback {
                override fun onSuccess(translatedCueGroups: List<CueGroup>) {
                    val translatedCueGroup = translatedCueGroups
                        .firstOrNull { it.presentationTimeUs == cueGroup.presentationTimeUs }
                        ?: translatedCueGroups.firstOrNull()
                    synchronized(aheadCacheLock) {
                        if (aheadCacheConfigurationToken == configurationToken) {
                            aheadPendingKeys.remove(key)
                            if (translatedCueGroup != null) {
                                aheadTranslatedCueGroupsByKey[key] = translatedCueGroup
                                trimAheadCacheLocked()
                            }
                        }
                    }
                    callback(translatedCueGroups)
                }

                override fun onFailure(exception: Exception) {
                    synchronized(aheadCacheLock) {
                        if (aheadCacheConfigurationToken == configurationToken) {
                            aheadPendingKeys.remove(key)
                        }
                    }
                    callback(emptyList())
                }
            }
        )
    }

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

        val entry = PendingTranslate(
            format = format,
            cueGroups = cueGroups,
            callback = callback,
            configurationToken = configurationToken,
            settings = settings,
            targetLanguage = targetLanguage
        )

        updateActiveRequests(delta = 1)

        val immediateFlush: List<PendingTranslate>? = synchronized(pendingLock) {
            pendingEntries.add(entry)
            pendingCueGroupCount += cueGroups.size
            if (pendingCueGroupCount >= maxBatchCueGroups) {
                flushJob?.cancel()
                flushJob = null
                drainPendingLocked()
            } else {
                if (flushJob == null) {
                    flushJob = scope.launch {
                        delay(dispatchDebounceMs)
                        val drained = synchronized(pendingLock) {
                            flushJob = null
                            drainPendingLocked()
                        } ?: return@launch
                        dispatchBatch(drained)
                    }
                }
                null
            }
        }

        immediateFlush?.let { dispatchBatch(it) }
    }

    private fun drainPendingLocked(): List<PendingTranslate>? {
        if (pendingEntries.isEmpty()) return null
        val drained = pendingEntries.toList()
        pendingEntries.clear()
        pendingCueGroupCount = 0
        return drained
    }

    private fun dispatchBatch(entries: List<PendingTranslate>) {
        if (entries.isEmpty()) return
        // Group by configurationToken so a mid-flight provider/model/apiKey change splits
        // into independent dispatches that don't share translation state.
        entries.groupBy { it.configurationToken }.forEach { (_, sameTokenEntries) ->
            scope.launch {
                dispatchSameTokenEntries(sameTokenEntries)
            }
        }
    }

    private suspend fun dispatchSameTokenEntries(entries: List<PendingTranslate>) {
        try {
            val pendingCueGroups = buildList {
                for (entryIndex in entries.indices) {
                    val entry = entries[entryIndex]
                    for (cueGroupIndex in entry.cueGroups.indices) {
                        add(PendingCueGroup(entry, entry.cueGroups[cueGroupIndex]))
                    }
                }
            }
            val batches = planProgressiveCueGroupBatches(pendingCueGroups, maxBatchCueGroups)
            if (batches.isEmpty()) {
                onTranslationError(null)
                for (entryIndex in entries.indices) {
                    val entry = entries[entryIndex]
                    entry.callback.onSuccess(entry.cueGroups)
                }
                return
            }

            for (batchIndex in batches.indices) {
                val batch = batches[batchIndex]
                val sourceTexts = sourceTextsForBuiltInTranslation(batch.map { it.cueGroup })
                if (sourceTexts.isEmpty()) {
                    onTranslationError(null)
                    publishPendingCueGroupBatch(batch, emptyMap())
                    continue
                }

                val anchor = batch.first().entry
                translationService.translateCueTexts(
                    texts = sourceTexts,
                    targetLanguageCode = anchor.targetLanguage,
                    sourceLanguageCode = anchor.format.language,
                    settings = anchor.settings
                ).onSuccess { translatedTexts ->
                    onTranslationError(null)
                    suppressedProviderFailure.get()?.let { failure ->
                        if (failure.configurationToken == anchor.configurationToken) {
                            suppressedProviderFailure.compareAndSet(failure, null)
                        }
                    }
                    publishPendingCueGroupBatch(batch, translatedTexts)
                }.onFailure { error ->
                    val message = error.message?.takeIf(String::isNotBlank)
                        ?: "Failed to translate subtitle."
                    suppressedProviderFailure.set(
                        SuppressedProviderFailure(
                            configurationToken = anchor.configurationToken,
                            message = message,
                            retryAfterMs = nowMs() + providerFailureCooldownMs
                        )
                    )
                    onTranslationError(message)
                    for (entryIndex in entries.indices) {
                        val entry = entries[entryIndex]
                        entry.callback.onFailure(Exception(message, error))
                    }
                    return
                }
            }
        } finally {
            for (index in entries.indices) {
                updateActiveRequests(delta = -1)
            }
        }
    }

    private fun planProgressiveCueGroupBatches(
        cueGroups: List<PendingCueGroup>,
        maxBatchCueGroups: Int
    ): List<List<PendingCueGroup>> {
        if (cueGroups.isEmpty()) return emptyList()
        val batches = mutableListOf<List<PendingCueGroup>>()
        var cursor = 0
        while (cursor < cueGroups.size) {
            val scheduleIndex = batches.size.coerceAtMost(DEFAULT_TRANSLATION_RAMP_UP_SCHEDULE.lastIndex)
            val scheduledSize = DEFAULT_TRANSLATION_RAMP_UP_SCHEDULE[scheduleIndex]
                .coerceAtMost(maxBatchCueGroups)
                .coerceAtLeast(1)
            val end = (cursor + scheduledSize).coerceAtMost(cueGroups.size)
            batches += cueGroups.subList(cursor, end)
            cursor = end
        }
        return batches
    }

    private fun publishPendingCueGroupBatch(
        batch: List<PendingCueGroup>,
        translatedTexts: Map<String, String>
    ) {
        val cueGroupsByEntry = linkedMapOf<PendingTranslate, MutableList<CueGroup>>()
        for (index in batch.indices) {
            val pending = batch[index]
            cueGroupsByEntry.getOrPut(pending.entry) { mutableListOf() } += pending.cueGroup
        }
        cueGroupsByEntry.forEach { (entry, cueGroups) ->
            entry.callback.onSuccess(translateBuiltInCueGroups(cueGroups, translatedTexts))
        }
    }

    private fun sourceTextsForBuiltInTranslation(cueGroups: List<CueGroup>): List<String> {
        return cueGroups
            .asSequence()
            .flatMap { cueGroup -> cueGroup.cues.asSequence() }
            .mapNotNull { cue -> cue.text?.toString()?.trim()?.takeIf(String::isNotBlank) }
            .distinct()
            .toList()
    }

    private fun translateBuiltInCueGroups(
        cueGroups: List<CueGroup>,
        translatedTexts: Map<String, String>
    ): List<CueGroup> {
        return cueGroups.map { cueGroup ->
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
    }

    private fun updateActiveRequests(delta: Int) {
        val active = (activeRequestCount.addAndGet(delta)).coerceAtLeast(0)
        if (active == 0 && delta < 0) {
            activeRequestCount.set(0)
        }
        onTranslatingChanged(active > 0)
    }

    private fun ensureAheadCacheTokenLocked(configurationToken: String) {
        if (aheadCacheConfigurationToken == configurationToken) return
        aheadCacheConfigurationToken = configurationToken
        aheadTranslatedCueGroupsByKey.clear()
        aheadPendingKeys.clear()
    }

    private fun trimAheadCacheLocked() {
        while (aheadTranslatedCueGroupsByKey.size > BUILT_IN_SUBTITLE_AHEAD_CACHE_MAX_CUE_GROUPS) {
            val iterator = aheadTranslatedCueGroupsByKey.entries.iterator()
            if (!iterator.hasNext()) return
            iterator.next()
            iterator.remove()
        }
    }

    private fun aheadCueGroupKey(cueGroup: CueGroup): String? {
        val text = cueGroup.cues
            .asSequence()
            .mapNotNull { cue -> cue.text?.toString()?.trim()?.takeIf(String::isNotBlank) }
            .joinToString(separator = "\n")
            .takeIf(String::isNotBlank)
            ?: return null
        return "${cueGroup.presentationTimeUs}|$text"
    }

    private fun builtInTranslationConfigurationToken(
        format: Format,
        settings: SubtitleTranslationSettings,
        targetLanguage: String
    ): String {
        return "${format.sampleMimeType}|${format.language.orEmpty()}|$targetLanguage|${settings.provider}|${settings.model}|${settings.baseUrl}|${settings.apiKey.hashCode()}"
    }

    private data class PendingTranslate(
        val format: Format,
        val cueGroups: List<CueGroup>,
        val callback: CueGroupSubtitleTranslator.TranslationCallback,
        val configurationToken: String,
        val settings: SubtitleTranslationSettings,
        val targetLanguage: String
    )

    private data class PendingCueGroup(
        val entry: PendingTranslate,
        val cueGroup: CueGroup
    )

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
