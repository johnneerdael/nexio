package com.nexio.tv.ui.screens.player.translation

import androidx.media3.common.MimeTypes
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import com.nexio.tv.ui.screens.player.TrackInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddedSubtitleHarvestDiagnosticsTest {
    @Test
    fun sessionStartedProofLineIncludesTrackAndModeFields() {
        val line = EmbeddedSubtitleHarvestDiagnostics.sessionStartedLine(
            session = session(),
            container = EmbeddedSubtitleContainer.MP4,
            streamUrl = "https://real-debrid.example.test/movie.mkv?token=secret",
            track = TrackInfo(
                index = 3,
                name = "English SDH",
                language = "en",
                trackId = "sub-3",
                mimeType = MimeTypes.APPLICATION_SUBRIP
            )
        )

        assertTrue(line.startsWith("EMBEDDED_SUB_TIMELINE "))
        assertTrue(line.contains("event=session_started"))
        assertTrue(line.contains("session=stream-key"))
        assertTrue(line.contains("container=mp4"))
        assertTrue(line.contains("translationMode=embedded_text_timeline"))
        assertTrue(line.contains("streamHost=real-debrid.example.test"))
        assertTrue(line.contains("trackIndex=3"))
        assertTrue(line.contains("trackId=sub-3"))
        assertTrue(line.contains("mime=application/x-subrip"))
        assertTrue(line.contains("language=en"))
        assertTrue(line.contains("trackName=English_SDH"))
    }

    @Test
    fun unsupportedProofLineUsesRendererFallbackModeAndReason() {
        val line = EmbeddedSubtitleHarvestDiagnostics.unsupportedLine("not_mkv")

        assertEquals(
            "EMBEDDED_SUB_TIMELINE event=unsupported " +
                "translationMode=renderer_prefetch_fallback reason=not_mkv",
            line
        )
    }

    @Test
    fun stateProofLineIncludesEligibilityInputs() {
        val line = EmbeddedSubtitleHarvestDiagnostics.stateEvaluatedLine(
            state = EmbeddedSubtitleHarvestState(
                streamUrl = "https://real-debrid.example.test/movie.mkv?token=secret",
                filename = "movie.mkv",
                headers = emptyMap(),
                selectedTrack = TrackInfo(
                    index = 3,
                    name = "English",
                    language = "en",
                    trackId = "sub-3",
                    mimeType = MimeTypes.APPLICATION_SUBRIP,
                    codec = MimeTypes.APPLICATION_SUBRIP
                ),
                selectedSupportedTextOrdinal = 1,
                selectedAddonSubtitlePresent = false,
                autoTranslateEnabled = true,
                targetLanguage = "nl",
                settings = com.nexio.tv.domain.model.SubtitleTranslationSettings(
                    enabled = true,
                    apiKey = "test-key"
                )
            ),
            eligible = true,
            reason = "eligible"
        )

        assertTrue(line.contains("event=state"))
        assertTrue(line.contains("eligible=true"))
        assertTrue(line.contains("reason=eligible"))
        assertTrue(line.contains("streamHost=real-debrid.example.test"))
        assertTrue(line.contains("container=mkv"))
        assertTrue(line.contains("hasApiKey=true"))
        assertTrue(line.contains("trackIndex=3"))
        assertTrue(line.contains("mime=application/x-subrip"))
        assertTrue(line.contains("selectedTextOrdinal=1"))
        assertFalse(line.contains("isMkv="))
        assertFalse(line.contains("subRipOrdinal="))
    }

    @Test
    fun sessionCancelledProofLineIncludesReason() {
        val line = EmbeddedSubtitleHarvestDiagnostics.sessionCancelledLine(
            session = session(),
            reason = "track_changed"
        )

        assertEquals(
            "EMBEDDED_SUB_TIMELINE event=session_cancelled " +
                "session=stream-key reason=track_changed",
            line
        )
    }

    @Test
    fun harvestFailedProofLineIncludesSessionAndReason() {
        val line = EmbeddedSubtitleHarvestDiagnostics.harvestFailedLine(
            session = session(),
            container = EmbeddedSubtitleContainer.MP4,
            reason = "network_failed"
        )

        assertEquals(
            "EMBEDDED_SUB_TIMELINE event=harvest_failed " +
                "session=stream-key container=mp4 reason=network_failed",
            line
        )
    }

    @Test
    fun mp4HarvestCompletedLineIncludesContainerAndDuration() {
        val line = EmbeddedSubtitleHarvestDiagnostics.harvestCompletedLine(
            session = session(),
            container = EmbeddedSubtitleContainer.MP4,
            harvested = 120,
            durationMs = 3_456L
        )

        assertEquals(
            "EMBEDDED_SUB_TIMELINE event=harvest_completed " +
                "session=stream-key container=mp4 harvested=120 durationMs=3456",
            line
        )
    }

    @Test
    fun mkvHarvestCompletedLineIncludesContainerAndDuration() {
        val line = EmbeddedSubtitleHarvestDiagnostics.harvestCompletedLine(
            session = session(),
            container = EmbeddedSubtitleContainer.MATROSKA,
            harvested = 75,
            durationMs = 2_000L
        )

        assertEquals(
            "EMBEDDED_SUB_TIMELINE event=harvest_completed " +
                "session=stream-key container=mkv harvested=75 durationMs=2000",
            line
        )
    }

    @Test
    fun initialSeekProofLineIncludesRequestedTimeAndResolvedOffset() {
        val line = EmbeddedSubtitleHarvestDiagnostics.initialSeekAppliedLine(
            session = session(),
            container = EmbeddedSubtitleContainer.MP4,
            requestedTimeUs = 815_000_000L,
            seekTimeUs = 814_750_000L,
            seekPosition = 123_456_789L,
            seekable = true
        )

        assertEquals(
            "EMBEDDED_SUB_TIMELINE event=initial_seek_applied session=stream-key " +
                "container=mp4 requestedTimeUs=815000000 seekTimeUs=814750000 " +
                "seekPosition=123456789 seekable=true",
            line
        )
    }

    @Test
    fun cueProofLinesUseStoreCueKeyFields() {
        val store = TranslatedSubtitleTimelineStore()
        val cueGroup = cueGroup(" bonjour ", 42_000L)
        val cueKey = store.cueKeyFor(cueGroup)

        val harvested = EmbeddedSubtitleHarvestDiagnostics.cueHarvestedLine(
            session = session(),
            container = EmbeddedSubtitleContainer.MP4,
            cueKey = cueKey,
            sourceLanguage = "fr"
        )
        val translated = EmbeddedSubtitleHarvestDiagnostics.cueTranslatedLine(
            session = session(),
            cueKey = cueKey
        )
        val lookupHit = EmbeddedSubtitleHarvestDiagnostics.rendererLookupLine(
            session = session(),
            cueKey = cueKey,
            hit = true
        )
        val lookupMiss = EmbeddedSubtitleHarvestDiagnostics.rendererLookupLine(
            session = session(),
            cueKey = cueKey,
            hit = false
        )

        assertTrue(harvested.contains("event=cue_harvested"))
        assertTrue(harvested.contains("session=stream-key"))
        assertTrue(harvested.contains("container=mp4"))
        assertTrue(harvested.contains("cueTimeUs=42000"))
        assertTrue(harvested.contains("cueHash=${cueKey?.sourceTextHash}"))
        assertTrue(harvested.contains("sourceLanguage=fr"))

        assertTrue(translated.contains("event=cue_translated"))
        assertTrue(translated.contains("cueTimeUs=42000"))
        assertTrue(translated.contains("cueHash=${cueKey?.sourceTextHash}"))

        assertTrue(lookupHit.contains("event=renderer_lookup_hit"))
        assertTrue(lookupHit.contains("translationMode=embedded_text_timeline"))
        assertTrue(lookupHit.contains("cueHash=${cueKey?.sourceTextHash}"))
        assertTrue(lookupMiss.contains("event=renderer_lookup_miss"))
    }

    @Test
    fun progressProofLineIncludesAllCounters() {
        val line = EmbeddedSubtitleHarvestDiagnostics.progressLine(
            session = session(),
            container = EmbeddedSubtitleContainer.MP4,
            harvested = 7,
            stats = TranslationTimelineStats(
                sourceCueCount = 6,
                translatedCueCount = 4,
                pendingBackfillCount = 2,
                hitCount = 3,
                missCount = 5
            ),
            fallbackOriginal = 9
        )

        assertEquals(
            "EMBEDDED_SUB_TIMELINE event=progress session=stream-key " +
                "container=mp4 harvested=7 sourceStored=6 translated=4 pendingBackfill=2 " +
                "lookupHit=3 lookupMiss=5 fallbackOriginal=9",
            line
        )
    }

    private fun session(): TranslationTimelineSessionKey {
        return TranslationTimelineSessionKey(
            streamKey = "stream-key",
            trackKey = "track-key",
            targetLanguage = "nl",
            settingsKey = "settings-key"
        )
    }

    private fun cueGroup(text: String, presentationTimeUs: Long): CueGroup {
        return CueGroup(listOf(Cue.Builder().setText(text).build()), presentationTimeUs)
    }
}
