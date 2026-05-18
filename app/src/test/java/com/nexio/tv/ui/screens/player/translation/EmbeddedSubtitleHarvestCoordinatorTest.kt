package com.nexio.tv.ui.screens.player.translation

import androidx.media3.common.MimeTypes
import com.nexio.tv.domain.model.SubtitleTranslationSettings
import com.nexio.tv.ui.screens.player.TrackInfo
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddedSubtitleHarvestCoordinatorTest {
    @Test
    fun eligibleSessionBeginsTimeline() = runTest {
        val timelineStore = TranslatedSubtitleTimelineStore()
        val startedHarvests = mutableListOf<TranslationTimelineSessionKey>()
        val coordinator = coordinator(
            timelineStore = timelineStore,
            startHarvest = { key, _ ->
                startedHarvests += key
                Job()
            }
        )

        coordinator.update(eligibleState(targetLanguage = "nl"))

        val activeKey = coordinator.activeSessionKey()
        assertNotNull(activeKey)
        assertEquals("nl", activeKey?.targetLanguage)
        assertEquals(1, startedHarvests.size)
        assertEquals(activeKey, startedHarvests.single())
    }

    @Test
    fun ineligibleAddonSubtitleCancelsAndDoesNotStart() = runTest {
        val coordinator = coordinator()

        coordinator.update(eligibleState())
        assertNotNull(coordinator.activeSessionKey())

        coordinator.update(eligibleState(selectedAddonSubtitlePresent = true))

        assertNull(coordinator.activeSessionKey())
    }

    @Test
    fun sameSessionUpdateDoesNotRestartJobs() = runTest {
        val startedHarvests = mutableListOf<TranslationTimelineSessionKey>()
        val coordinator = coordinator(
            startHarvest = { key, _ ->
                startedHarvests += key
                Job()
            }
        )
        val state = eligibleState()

        coordinator.update(state)
        coordinator.update(state)

        assertEquals(1, startedHarvests.size)
    }

    @Test
    fun changedTargetStartsNewSessionAndCancelsPreviousJobs() = runTest {
        val harvestJobs = mutableListOf<Job>()
        val diagnostics = RecordingHarvestDiagnostics()
        val coordinator = coordinator(
            diagnostics = diagnostics,
            startHarvest = { _, _ ->
                Job().also(harvestJobs::add)
            }
        )

        coordinator.update(eligibleState(targetLanguage = "nl"))
        val firstKey = coordinator.activeSessionKey()
        coordinator.update(eligibleState(targetLanguage = "de"))
        val secondKey = coordinator.activeSessionKey()

        assertEquals(2, harvestJobs.size)
        assertTrue(harvestJobs.first().isCancelled)
        assertFalse(harvestJobs.last().isCancelled)
        assertEquals("nl", firstKey?.targetLanguage)
        assertEquals("de", secondKey?.targetLanguage)
        assertEquals(listOf("session_changed"), diagnostics.cancelReasons)
    }

    @Test
    fun broaderTextEligibilityDoesNotStartBeforeTextOrdinalExists() = runTest {
        val startedHarvests = mutableListOf<TranslationTimelineSessionKey>()
        val diagnostics = RecordingHarvestDiagnostics()
        val coordinator = coordinator(
            diagnostics = diagnostics,
            startHarvest = { key, _ ->
                startedHarvests += key
                Job()
            }
        )

        coordinator.update(
            eligibleState(
                selectedSupportedTextOrdinal = null,
                track = TrackInfo(
                    index = 3,
                    name = "English",
                    language = "en",
                    trackId = "subtitle:3",
                    isSelected = true,
                    mimeType = MimeTypes.APPLICATION_MP4VTT
                )
            )
        )

        assertNull(coordinator.activeSessionKey())
        assertEquals(emptyList<TranslationTimelineSessionKey>(), startedHarvests)
        assertEquals(listOf("missing_text_ordinal"), diagnostics.unsupportedReasons)
    }

    @Test
    fun mp4EligibleSessionCarriesContainerAndTextOrdinal() = runTest {
        val capturedStates = mutableListOf<EmbeddedSubtitleHarvestState>()
        val coordinator = coordinator(
            startHarvest = { _, state ->
                capturedStates += state
                Job()
            }
        )

        coordinator.update(
            eligibleState(
                streamUrl = "https://example.test/video.mp4",
                filename = "video.mp4",
                track = TrackInfo(
                    index = 5,
                    name = "English",
                    language = "en",
                    trackId = "text-5",
                    isSelected = true,
                    mimeType = MimeTypes.APPLICATION_TX3G
                ),
                selectedSupportedTextOrdinal = 1
            )
        )

        assertEquals(EmbeddedSubtitleContainer.MP4, capturedStates.single().container)
        assertEquals(1, capturedStates.single().selectedSupportedTextOrdinal)
    }

    private fun TestScope.coordinator(
        timelineStore: TranslatedSubtitleTimelineStore = TranslatedSubtitleTimelineStore(),
        diagnostics: EmbeddedSubtitleHarvestDiagnosticsLogger = EmbeddedSubtitleHarvestDiagnostics,
        startHarvest: (
            TranslationTimelineSessionKey,
            EmbeddedSubtitleHarvestState
        ) -> Job = { _, _ -> Job() },
        startTranslateLoop: (
            TranslationTimelineSessionKey,
            EmbeddedSubtitleHarvestState
        ) -> Job = { _, _ -> Job() }
    ): EmbeddedSubtitleHarvestCoordinator {
        return EmbeddedSubtitleHarvestCoordinator(
            scope = this,
            timelineStore = timelineStore,
            diagnostics = diagnostics,
            startHarvest = startHarvest,
            startTranslateLoop = startTranslateLoop
        )
    }

    private fun eligibleState(
        streamUrl: String = "https://example.test/video.mkv?token=abc",
        filename: String = "video.mkv",
        targetLanguage: String = "nl",
        selectedAddonSubtitlePresent: Boolean = false,
        track: TrackInfo? = subRipTrack(),
        selectedSupportedTextOrdinal: Int? = if (track == null) null else 0,
        settings: SubtitleTranslationSettings = SubtitleTranslationSettings(
            enabled = true,
            apiKey = "test-key",
            model = "test-model"
        )
    ): EmbeddedSubtitleHarvestState {
        return EmbeddedSubtitleHarvestState(
            streamUrl = streamUrl,
            filename = filename,
            headers = mapOf("Authorization" to "Bearer token"),
            selectedTrack = track,
            selectedSupportedTextOrdinal = selectedSupportedTextOrdinal,
            selectedAddonSubtitlePresent = selectedAddonSubtitlePresent,
            autoTranslateEnabled = true,
            targetLanguage = targetLanguage,
            settings = settings
        )
    }

    private fun subRipTrack(): TrackInfo {
        return TrackInfo(
            index = 2,
            name = "English",
            language = "en",
            trackId = "subtitle:2",
            isSelected = true,
            mimeType = MimeTypes.APPLICATION_SUBRIP
        )
    }

    private class RecordingHarvestDiagnostics : EmbeddedSubtitleHarvestDiagnosticsLogger {
        val cancelReasons = mutableListOf<String>()
        val unsupportedReasons = mutableListOf<String>()

        override fun stateEvaluated(
            state: EmbeddedSubtitleHarvestState,
            eligible: Boolean,
            reason: String
        ) = Unit

        override fun sessionStarted(
            session: TranslationTimelineSessionKey,
            streamUrl: String,
            track: TrackInfo?
        ) = Unit

        override fun sessionCancelled(session: TranslationTimelineSessionKey?, reason: String) {
            cancelReasons += reason
        }

        override fun unsupported(reason: String) {
            unsupportedReasons += reason
        }
    }
}
