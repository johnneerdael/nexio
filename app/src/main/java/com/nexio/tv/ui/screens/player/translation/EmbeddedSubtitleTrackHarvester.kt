package com.nexio.tv.ui.screens.player.translation

import androidx.media3.extractor.ExtractorOutput

internal data class EmbeddedSubtitleTrackHarvestRequest(
    val streamUrl: String,
    val headers: Map<String, String>,
    val selectedSupportedTextOrdinal: Int,
    val initialPositionMs: Long = 0L,
    val sourceLanguage: String?,
    val sessionKey: TranslationTimelineSessionKey,
    val timelineStore: TranslatedSubtitleTimelineStore,
    val extractorOutput: ExtractorOutput
)

internal data class EmbeddedSubtitleTrackHarvestResult(
    val container: EmbeddedSubtitleContainer,
    val harvested: Int,
    val durationMs: Long
)

internal interface EmbeddedSubtitleTrackHarvester {
    val container: EmbeddedSubtitleContainer
    suspend fun harvest(request: EmbeddedSubtitleTrackHarvestRequest): EmbeddedSubtitleTrackHarvestResult
}
