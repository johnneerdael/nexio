package com.nexio.tv.ui.screens.player.translation

import com.nexio.tv.data.repository.DEFAULT_TRANSLATION_RAMP_UP_SCHEDULE
import com.nexio.tv.data.repository.SubtitleTranslationService
import com.nexio.tv.domain.model.SubtitleTranslationSettings

internal class SubtitleTimelineTranslationPipeline(
    private val translationService: SubtitleTranslationService,
    maxBatchSize: Int = 60
) {
    private val maxBatchSize = maxBatchSize.coerceAtLeast(1)

    suspend fun translatePending(
        session: TranslationTimelineSessionKey,
        store: TranslatedSubtitleTimelineStore,
        sourceLanguageCode: String?,
        targetLanguageCode: String,
        settings: SubtitleTranslationSettings
    ) {
        val pending = store.pendingBackfill(session)
        if (pending.isEmpty()) return

        val batches = pending.planBatches(maxBatchSize)
        for (batchIndex in batches.indices) {
            val batch = batches[batchIndex]
            val sourceTexts = batch.distinctSourceTexts()
            if (sourceTexts.isEmpty()) continue

            translationService.translateCueTexts(
                texts = sourceTexts,
                targetLanguageCode = targetLanguageCode,
                sourceLanguageCode = sourceLanguageCode,
                settings = settings
            ).onSuccess { translatedTexts ->
                for (cueIndex in batch.indices) {
                    val sourceCue = batch[cueIndex]
                    val translatedCueGroup = TranslatedSubtitleTimelineStore.translateCueGroupTexts(
                        cueGroup = sourceCue.cueGroup,
                        translatedTexts = translatedTexts
                    )
                    store.putTranslatedCueGroup(
                        sessionKey = session,
                        sourceCueGroup = sourceCue.cueGroup,
                        translatedCueGroup = translatedCueGroup
                    )
                }
            }.onFailure {
                return
            }
        }
    }

    private fun List<TranslationTimelineSourceCue>.planBatches(
        maxBatchSize: Int
    ): List<List<TranslationTimelineSourceCue>> {
        if (isEmpty()) return emptyList()

        val batches = mutableListOf<List<TranslationTimelineSourceCue>>()
        var cursor = 0
        while (cursor < size) {
            val scheduleIndex = batches.size.coerceAtMost(DEFAULT_TRANSLATION_RAMP_UP_SCHEDULE.lastIndex)
            val batchSize = DEFAULT_TRANSLATION_RAMP_UP_SCHEDULE[scheduleIndex]
                .coerceAtMost(maxBatchSize)
                .coerceAtLeast(1)
            val end = (cursor + batchSize).coerceAtMost(size)
            batches += subList(cursor, end)
            cursor = end
        }
        return batches
    }

    private fun List<TranslationTimelineSourceCue>.distinctSourceTexts(): List<String> {
        val seen = LinkedHashSet<String>()
        val sourceTexts = mutableListOf<String>()
        for (index in indices) {
            val sourceText = this[index].sourceText.trim()
            if (sourceText.isNotBlank() && seen.add(sourceText)) {
                sourceTexts += sourceText
            }
        }
        return sourceTexts
    }
}
