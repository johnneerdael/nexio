package com.nexio.tv.ui.screens.player

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.DummyTrackOutput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import com.nexio.tv.ui.screens.player.translation.EmbeddedSubtitleContainer
import com.nexio.tv.ui.screens.player.translation.EmbeddedSubtitleHarvestDiagnostics
import com.nexio.tv.ui.screens.player.translation.EmbeddedSubtitleHarvestEligibility
import com.nexio.tv.ui.screens.player.translation.EmbeddedSubtitleHarvestState
import com.nexio.tv.ui.screens.player.translation.EmbeddedSubtitleTrackHarvestRequest
import com.nexio.tv.ui.screens.player.translation.MatroskaTextTrackHarvester
import com.nexio.tv.ui.screens.player.translation.Mp4TextTrackHarvester
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
            selectedSupportedTextOrdinal = selectedTextOrdinalForHarvest(
                subtitleTracks = state.subtitleTracks,
                selectedTrack = selectedTrack,
                container = EmbeddedSubtitleHarvestEligibility.containerFor(
                    streamUrl = currentStreamUrl,
                    filename = currentFilename
                )
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
            val harvester = when (state.container) {
                EmbeddedSubtitleContainer.MATROSKA -> MatroskaTextTrackHarvester()
                EmbeddedSubtitleContainer.MP4 -> Mp4TextTrackHarvester()
                null -> error("Unsupported embedded subtitle container")
            }
            val result = harvester.harvest(
                EmbeddedSubtitleTrackHarvestRequest(
                    streamUrl = state.streamUrl,
                    headers = state.headers,
                    selectedSupportedTextOrdinal = state.selectedSupportedTextOrdinal ?: -1,
                    sourceLanguage = state.selectedTrack?.language,
                    sessionKey = sessionKey,
                    timelineStore = translatedSubtitleTimelineStore,
                    extractorOutput = HarvestDiscardingExtractorOutput()
                )
            )
            EmbeddedSubtitleHarvestDiagnostics.progress(
                session = sessionKey,
                container = result.container,
                harvested = result.harvested,
                stats = translatedSubtitleTimelineStore.stats(sessionKey),
                fallbackOriginal = builtInSubtitleCueTranslator.timelineFallbackOriginalCount()
            )
            EmbeddedSubtitleHarvestDiagnostics.harvestCompleted(
                session = sessionKey,
                container = result.container,
                harvested = result.harvested,
                durationMs = result.durationMs
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
                container = state.container,
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

internal fun selectedTextOrdinalForHarvest(
    subtitleTracks: List<TrackInfo>,
    selectedTrack: TrackInfo?,
    container: EmbeddedSubtitleContainer? = null
): Int? {
    selectedTrack ?: return null
    if (!isSupportedTextTrackForContainer(selectedTrack, container)) return null

    var supportedOrdinal = 0
    for (index in subtitleTracks.indices) {
        val track = subtitleTracks[index]
        if (!isSupportedTextTrackForContainer(track, container)) continue
        if (track == selectedTrack || track.index == selectedTrack.index) {
            return supportedOrdinal
        }
        supportedOrdinal += 1
    }
    return null
}

private fun isSupportedTextTrackForContainer(
    track: TrackInfo,
    container: EmbeddedSubtitleContainer?
): Boolean {
    return when (container) {
        EmbeddedSubtitleContainer.MATROSKA -> EmbeddedSubtitleHarvestEligibility.isSubRip(track)
        EmbeddedSubtitleContainer.MP4,
        null -> EmbeddedSubtitleHarvestEligibility.isSupportedTextTrack(track)
    }
}
