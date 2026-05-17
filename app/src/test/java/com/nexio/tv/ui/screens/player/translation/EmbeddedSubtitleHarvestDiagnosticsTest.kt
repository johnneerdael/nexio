package com.nexio.tv.ui.screens.player.translation

import androidx.media3.common.MimeTypes
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import com.nexio.tv.ui.screens.player.TrackInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddedSubtitleHarvestDiagnosticsTest {
    @Test
    fun sessionStartedProofLineIncludesTrackAndModeFields() {
        val line = EmbeddedSubtitleHarvestDiagnostics.sessionStartedLine(
            session = session(),
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
        assertTrue(line.contains("translationMode=embedded_mkv_timeline"))
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
    fun cueProofLinesUseStoreCueKeyFields() {
        val store = TranslatedSubtitleTimelineStore()
        val cueGroup = cueGroup(" bonjour ", 42_000L)
        val cueKey = store.cueKeyFor(cueGroup)

        val harvested = EmbeddedSubtitleHarvestDiagnostics.cueHarvestedLine(
            session = session(),
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
        assertTrue(harvested.contains("cueTimeUs=42000"))
        assertTrue(harvested.contains("cueHash=${cueKey?.sourceTextHash}"))
        assertTrue(harvested.contains("sourceLanguage=fr"))

        assertTrue(translated.contains("event=cue_translated"))
        assertTrue(translated.contains("cueTimeUs=42000"))
        assertTrue(translated.contains("cueHash=${cueKey?.sourceTextHash}"))

        assertTrue(lookupHit.contains("event=renderer_lookup_hit"))
        assertTrue(lookupHit.contains("translationMode=embedded_mkv_timeline"))
        assertTrue(lookupHit.contains("cueHash=${cueKey?.sourceTextHash}"))
        assertTrue(lookupMiss.contains("event=renderer_lookup_miss"))
    }

    @Test
    fun progressProofLineIncludesAllCounters() {
        val line = EmbeddedSubtitleHarvestDiagnostics.progressLine(
            session = session(),
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
                "harvested=7 sourceStored=6 translated=4 pendingBackfill=2 " +
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
