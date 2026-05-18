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
    var lastCueTimeUs: Long = -1L
        private set

    fun publish(cueGroup: CueGroup, language: String? = null) {
        val sourceCue = timelineStore.putSourceCue(sessionKey, cueGroup) ?: return
        sampleCount += 1
        lastCueTimeUs = cueGroup.presentationTimeUs
        timelineStore.registerMiss(sessionKey, cueGroup)
        EmbeddedSubtitleHarvestDiagnostics.cueHarvested(
            session = sessionKey,
            container = container,
            cueKey = sourceCue.cueKey,
            sourceLanguage = sourceLanguage ?: language
        )
    }
}
