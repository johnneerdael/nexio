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
    private val aheadTranslatedCueGroupsByTimedKey = LinkedHashMap<String, CueGroup>()
    private val aheadTranslatedCueGroupsByTextKey = LinkedHashMap<String, CueGroup>()
    private val aheadPendingKeys = mutableSetOf<String>()
    private val aheadPendingAwaiters = mutableListOf<AheadPendingAwaiter>()
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
        val timedKey = aheadCueGroupTimedKey(sourceCueGroup)
        val textKey = aheadCueGroupTextKey(sourceCueGroup)
        synchronized(aheadCacheLock) {
            ensureAheadCacheTokenLocked(configurationToken)
            timedKey?.let { key ->
                aheadTranslatedCueGroupsByTimedKey[key]?.let { return it.atPresentationTime(sourceCueGroup.presentationTimeUs) }
            }
            textKey?.let { key ->
                aheadTranslatedCueGroupsByTextKey[key]?.let { return it.atPresentationTime(sourceCueGroup.presentationTimeUs) }
            }
            return null
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
        val timedKey = aheadCueGroupTimedKey(cueGroup)
        val textKey = aheadCueGroupTextKey(cueGroup)
        val pendingKey = aheadCueGroupPendingKey(cueGroup)
        if (pendingKey == null) {
            callback(emptyList())
            return
        }

        synchronized(aheadCacheLock) {
            ensureAheadCacheTokenLocked(configurationToken)
            timedKey?.let { key ->
                aheadTranslatedCueGroupsByTimedKey[key]?.let { cached ->
                    callback(listOf(cached.atPresentationTime(cueGroup.presentationTimeUs)))
                    return
                }
            }
            textKey?.let { key ->
                aheadTranslatedCueGroupsByTextKey[key]?.let { cached ->
                    callback(listOf(cached.atPresentationTime(cueGroup.presentationTimeUs)))
                    return
                }
            }
            if (!aheadPendingKeys.add(pendingKey)) {
                return
            }
        }

        translateInternal(
            format = format,
            cueGroups = listOf(cueGroup),
            waitForAheadPending = false,
            callback = object : CueGroupSubtitleTranslator.TranslationCallback {
                override fun onSuccess(translatedCueGroups: List<CueGroup>) {
                    val translatedCueGroup = translatedCueGroups
                        .firstOrNull { it.presentationTimeUs == cueGroup.presentationTimeUs }
                        ?: translatedCueGroups.firstOrNull()
                    synchronized(aheadCacheLock) {
                        if (aheadCacheConfigurationToken == configurationToken) {
                            aheadPendingKeys.remove(pendingKey)
                            if (translatedCueGroup != null) {
                                timedKey?.let { key ->
                                    aheadTranslatedCueGroupsByTimedKey[key] = translatedCueGroup
                                }
                                textKey?.let { key ->
                                    aheadTranslatedCueGroupsByTextKey[key] = translatedCueGroup
                                }
                                trimAheadCacheLocked()
                            }
                        }
                    }
                    callback(translatedCueGroups)
                }

                override fun onFailure(exception: Exception) {
                    synchronized(aheadCacheLock) {
                        if (aheadCacheConfigurationToken == configurationToken) {
                            aheadPendingKeys.remove(pendingKey)
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
        translateInternal(
            format = format,
            cueGroups = cueGroups,
            waitForAheadPending = true,
            callback = callback
        )
    }

    private fun translateInternal(
        format: Format,
        cueGroups: List<CueGroup>,
        waitForAheadPending: Boolean,
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

        val cachedCueGroups = synchronized(aheadCacheLock) {
            ensureAheadCacheTokenLocked(configurationToken)
            cachedAheadCueGroupsLocked(cueGroups)
        }
        if (cachedCueGroups != null) {
            callback.onSuccess(cachedCueGroups)
            return
        }

        val waitingForAhead = if (waitForAheadPending) {
            synchronized(aheadCacheLock) {
                ensureAheadCacheTokenLocked(configurationToken)
                val pendingKeys = cueGroups.map { cueGroup -> aheadCueGroupPendingKey(cueGroup) }
                if (pendingKeys.isNotEmpty() && pendingKeys.all { key -> key != null && aheadPendingKeys.contains(key) }) {
                    aheadPendingAwaiters += AheadPendingAwaiter(
                        cueGroups = cueGroups,
                        callback = callback
                    )
                    true
                } else {
                    false
                }
            }
        } else {
            false
        }
        if (waitingForAhead) {
            return
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
            val translatedCueGroups = translateBuiltInCueGroups(cueGroups, translatedTexts)
            val readyAwaiters = synchronized(aheadCacheLock) {
                if (aheadCacheConfigurationToken == entry.configurationToken) {
                    cacheAheadTranslationsLocked(cueGroups, translatedCueGroups)
                    collectReadyAheadAwaitersLocked()
                } else {
                    emptyList()
                }
            }
            entry.callback.onSuccess(translatedCueGroups)
            for (index in readyAwaiters.indices) {
                val readyAwaiter = readyAwaiters[index]
                readyAwaiter.callback.onSuccess(readyAwaiter.translatedCueGroups)
            }
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
        aheadTranslatedCueGroupsByTimedKey.clear()
        aheadTranslatedCueGroupsByTextKey.clear()
        aheadPendingKeys.clear()
        aheadPendingAwaiters.clear()
    }

    private fun trimAheadCacheLocked() {
        trimLinkedMapLocked(aheadTranslatedCueGroupsByTimedKey)
        trimLinkedMapLocked(aheadTranslatedCueGroupsByTextKey)
    }

    private fun trimLinkedMapLocked(map: LinkedHashMap<String, CueGroup>) {
        while (map.size > BUILT_IN_SUBTITLE_AHEAD_CACHE_MAX_CUE_GROUPS) {
            val iterator = map.entries.iterator()
            if (!iterator.hasNext()) return
            iterator.next()
            iterator.remove()
        }
    }

    private fun aheadCueGroupTimedKey(cueGroup: CueGroup): String? {
        val text = aheadCueGroupTextKey(cueGroup) ?: return null
        return "${cueGroup.presentationTimeUs}|$text"
    }

    private fun aheadCueGroupPendingKey(cueGroup: CueGroup): String? {
        return aheadCueGroupTextKey(cueGroup) ?: aheadCueGroupTimedKey(cueGroup)
    }

    private fun aheadCueGroupTextKey(cueGroup: CueGroup): String? {
        return cueGroup.cues
            .asSequence()
            .mapNotNull { cue -> cue.text?.toString()?.trim()?.takeIf(String::isNotBlank) }
            .joinToString(separator = "\n")
            .takeIf(String::isNotBlank)
    }

    private fun cachedAheadCueGroupsLocked(cueGroups: List<CueGroup>): List<CueGroup>? {
        if (cueGroups.isEmpty()) return emptyList()
        val cachedCueGroups = ArrayList<CueGroup>(cueGroups.size)
        for (index in cueGroups.indices) {
            val sourceCueGroup = cueGroups[index]
            val cachedCueGroup = cachedAheadCueGroupLocked(sourceCueGroup) ?: return null
            cachedCueGroups += cachedCueGroup
        }
        return cachedCueGroups
    }

    private fun cachedAheadCueGroupLocked(sourceCueGroup: CueGroup): CueGroup? {
        aheadCueGroupTimedKey(sourceCueGroup)?.let { key ->
            aheadTranslatedCueGroupsByTimedKey[key]?.let {
                return it.atPresentationTime(sourceCueGroup.presentationTimeUs)
            }
        }
        aheadCueGroupTextKey(sourceCueGroup)?.let { key ->
            aheadTranslatedCueGroupsByTextKey[key]?.let {
                return it.atPresentationTime(sourceCueGroup.presentationTimeUs)
            }
        }
        return null
    }

    private fun cacheAheadTranslationsLocked(
        sourceCueGroups: List<CueGroup>,
        translatedCueGroups: List<CueGroup>
    ) {
        val count = minOf(sourceCueGroups.size, translatedCueGroups.size)
        for (index in 0 until count) {
            val sourceCueGroup = sourceCueGroups[index]
            val translatedCueGroup = translatedCueGroups[index]
            aheadCueGroupTimedKey(sourceCueGroup)?.let { key ->
                aheadTranslatedCueGroupsByTimedKey[key] = translatedCueGroup
            }
            aheadCueGroupTextKey(sourceCueGroup)?.let { key ->
                aheadTranslatedCueGroupsByTextKey[key] = translatedCueGroup
            }
            aheadCueGroupPendingKey(sourceCueGroup)?.let { key ->
                aheadPendingKeys.remove(key)
            }
        }
        trimAheadCacheLocked()
    }

    private fun collectReadyAheadAwaitersLocked(): List<ReadyAheadAwaiter> {
        if (aheadPendingAwaiters.isEmpty()) return emptyList()
        val readyAwaiters = mutableListOf<ReadyAheadAwaiter>()
        val iterator = aheadPendingAwaiters.iterator()
        while (iterator.hasNext()) {
            val awaiter = iterator.next()
            val translatedCueGroups = cachedAheadCueGroupsLocked(awaiter.cueGroups)
            if (translatedCueGroups != null) {
                readyAwaiters += ReadyAheadAwaiter(awaiter.callback, translatedCueGroups)
                iterator.remove()
            }
        }
        return readyAwaiters
    }

    private fun CueGroup.atPresentationTime(presentationTimeUs: Long): CueGroup {
        if (this.presentationTimeUs == presentationTimeUs) return this
        return CueGroup(cues, presentationTimeUs)
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

    private data class AheadPendingAwaiter(
        val cueGroups: List<CueGroup>,
        val callback: CueGroupSubtitleTranslator.TranslationCallback
    )

    private data class ReadyAheadAwaiter(
        val callback: CueGroupSubtitleTranslator.TranslationCallback,
        val translatedCueGroups: List<CueGroup>
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
