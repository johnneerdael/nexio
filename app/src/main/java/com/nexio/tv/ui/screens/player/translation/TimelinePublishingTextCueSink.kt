package com.nexio.tv.ui.screens.player.translation

import androidx.media3.common.text.CueGroup

internal class TimelinePublishingTextCueSink(
    private val sessionKey: TranslationTimelineSessionKey,
    private val container: EmbeddedSubtitleContainer,
    private val timelineStore: TranslatedSubtitleTimelineStore,
    private val sourceLanguage: String? = null
) {
    var sampleCount: Int = 0
        private set

    fun publish(cueGroup: CueGroup, language: String? = null) {
        val beforeSourceCueCount = timelineStore.stats(sessionKey).sourceCueCount
        timelineStore.putSourceCue(sessionKey, cueGroup)
        val sourceCue = timelineStore.registerMiss(sessionKey, cueGroup)
        val afterSourceCueCount = timelineStore.stats(sessionKey).sourceCueCount
        if (sourceCue != null && afterSourceCueCount > beforeSourceCueCount) {
            sampleCount += 1
            EmbeddedSubtitleHarvestDiagnostics.cueHarvested(
                session = sessionKey,
                cueKey = sourceCue.cueKey,
                sourceLanguage = sourceLanguage ?: language
            )
        }
    }
}
