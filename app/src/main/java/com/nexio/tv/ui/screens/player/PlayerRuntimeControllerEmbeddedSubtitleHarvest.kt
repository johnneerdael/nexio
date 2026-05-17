package com.nexio.tv.ui.screens.player

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.DummyTrackOutput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import com.nexio.tv.ui.screens.player.translation.EmbeddedSubtitleHarvestDiagnostics
import com.nexio.tv.ui.screens.player.translation.EmbeddedSubtitleHarvestEligibility
import com.nexio.tv.ui.screens.player.translation.EmbeddedSubtitleHarvestState
import com.nexio.tv.ui.screens.player.translation.MatroskaTextTrackHarvestRequest
import com.nexio.tv.ui.screens.player.translation.MatroskaTextTrackHarvester
import com.nexio.tv.ui.screens.player.translation.SubtitleTimelineTranslationPipeline
import com.nexio.tv.ui.screens.player.translation.TranslationTimelineSessionKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val EMBEDDED_SUBTITLE_BACKFILL_INTERVAL_MS = 2_000L

internal fun PlayerRuntimeController.updateEmbeddedSubtitleHarvest() {
    val state = _uiState.value
    val selectedTrack = state.subtitleTracks.getOrNull(state.selectedSubtitleTrackIndex)
    embeddedSubtitleHarvestCoordinator.update(
        EmbeddedSubtitleHarvestState(
            streamUrl = currentStreamUrl,
            filename = currentFilename,
            headers = currentHeaders,
            selectedTrack = selectedTrack,
            selectedSupportedSubRipOrdinal = selectedSubRipOrdinalForHarvest(
                subtitleTracks = state.subtitleTracks,
                selectedTrack = selectedTrack
            ),
            selectedAddonSubtitlePresent = state.selectedAddonSubtitle != null,
            autoTranslateEnabled = state.aiSubtitlesEnabled,
            targetLanguage = state.subtitleStyle.preferredLanguage,
            settings = subtitleTranslationSettings
        )
    )
}

internal fun PlayerRuntimeController.startEmbeddedSubtitleHarvest(
    sessionKey: TranslationTimelineSessionKey,
    state: EmbeddedSubtitleHarvestState
): Job {
    return scope.launch {
        try {
            val harvested = MatroskaTextTrackHarvester().harvest(
                MatroskaTextTrackHarvestRequest(
                    streamUrl = state.streamUrl,
                    headers = state.headers,
                    selectedSupportedSubRipOrdinal = state.selectedSupportedSubRipOrdinal ?: -1,
                    sourceLanguage = state.selectedTrack?.language,
                    sessionKey = sessionKey,
                    timelineStore = translatedSubtitleTimelineStore,
                    extractorOutput = HarvestDiscardingExtractorOutput()
                )
            )
            EmbeddedSubtitleHarvestDiagnostics.progress(
                session = sessionKey,
                harvested = harvested,
                stats = translatedSubtitleTimelineStore.stats(sessionKey),
                fallbackOriginal = builtInSubtitleCueTranslator.timelineFallbackOriginalCount()
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            EmbeddedSubtitleHarvestDiagnostics.harvestFailed(
                session = sessionKey,
                reason = error.message?.takeIf(String::isNotBlank)
                    ?: error::class.java.simpleName
            )
        }
    }
}

internal fun PlayerRuntimeController.startEmbeddedSubtitleTranslateLoop(
    sessionKey: TranslationTimelineSessionKey,
    state: EmbeddedSubtitleHarvestState
): Job {
    val targetLanguage = state.targetLanguage?.trim().orEmpty()
    val sourceLanguage = state.selectedTrack?.language
    val settings = state.settings
    val pipeline = SubtitleTimelineTranslationPipeline(subtitleTranslationService)
    return scope.launch {
        while (isActive) {
            pipeline.translatePending(
                session = sessionKey,
                store = translatedSubtitleTimelineStore,
                sourceLanguageCode = sourceLanguage,
                targetLanguageCode = targetLanguage,
                settings = settings
            )
            val stats = translatedSubtitleTimelineStore.stats(sessionKey)
            EmbeddedSubtitleHarvestDiagnostics.progress(
                session = sessionKey,
                harvested = stats.sourceCueCount,
                stats = stats,
                fallbackOriginal = builtInSubtitleCueTranslator.timelineFallbackOriginalCount()
            )
            delay(EMBEDDED_SUBTITLE_BACKFILL_INTERVAL_MS)
        }
    }
}

@OptIn(UnstableApi::class)
@Suppress("DEPRECATION")
private class HarvestDiscardingExtractorOutput : ExtractorOutput {
    override fun track(id: Int, type: Int): TrackOutput = DummyTrackOutput()
    override fun endTracks() = Unit
    override fun seekMap(seekMap: SeekMap) = Unit
}

internal fun selectedSubRipOrdinalForHarvest(
    subtitleTracks: List<TrackInfo>,
    selectedTrack: TrackInfo?
): Int? {
    selectedTrack ?: return null
    if (!EmbeddedSubtitleHarvestEligibility.isSubRip(selectedTrack)) return null

    var supportedOrdinal = 0
    for (index in subtitleTracks.indices) {
        val track = subtitleTracks[index]
        if (!EmbeddedSubtitleHarvestEligibility.isSubRip(track)) continue
        if (track == selectedTrack || track.index == selectedTrack.index) {
            return supportedOrdinal
        }
        supportedOrdinal += 1
    }
    return null
}
