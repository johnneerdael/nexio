package com.nexio.tv.ui.screens.player.translation

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MatroskaTextTrackHarvesterTest {

    @Test
    fun sinkConvertsSamplesToSourceCueGroups() {
        val store = TranslatedSubtitleTimelineStore()
        val session = session()
        val sink = TimelinePublishingMatroskaTextTrackSink(
            cueSink = TimelinePublishingTextCueSink(
                sessionKey = session,
                container = EmbeddedSubtitleContainer.MATROSKA,
                timelineStore = store
            )
        )

        store.beginSession(session)
        sink.onSubtitleSample(
            HarvestedMatroskaTextSample(
                trackId = 1,
                supportedTrackOrdinal = 0,
                timeUs = 1_000L,
                text = "bonjour",
                format = subRipFormat()
            )
        )

        assertEquals(1, store.stats(session).sourceCueCount)
        assertEquals("bonjour", store.pendingBackfill(session).single().sourceText)
    }

    @Test
    fun sinkDoesNotCountSamplesWhenSessionIsInactive() {
        val store = TranslatedSubtitleTimelineStore()
        val session = session()
        val sink = TimelinePublishingMatroskaTextTrackSink(
            cueSink = TimelinePublishingTextCueSink(
                sessionKey = session,
                container = EmbeddedSubtitleContainer.MATROSKA,
                timelineStore = store
            )
        )

        sink.onSubtitleSample(
            HarvestedMatroskaTextSample(
                trackId = 1,
                supportedTrackOrdinal = 0,
                timeUs = 1_000L,
                text = "bonjour",
                format = subRipFormat()
            )
        )

        assertEquals(0, sink.sampleCount)
        assertEquals(0, store.stats(session).sourceCueCount)
        assertTrue(store.pendingBackfill(session).isEmpty())
    }

    @Test
    fun extractorInputLengthAddsPositionToKnownRemainingLength() {
        assertEquals(
            1_500L,
            matroskaExtractorInputLength(
                position = 500L,
                remainingLength = 1_000L
            )
        )
        assertEquals(
            C.LENGTH_UNSET.toLong(),
            matroskaExtractorInputLength(
                position = 500L,
                remainingLength = C.LENGTH_UNSET.toLong()
            )
        )
    }

    private fun session(): TranslationTimelineSessionKey {
        return TranslationTimelineSessionKey(
            streamKey = "stream",
            trackKey = "track",
            targetLanguage = "nl",
            settingsKey = "settings"
        )
    }

    private fun subRipFormat(): Format {
        return Format.Builder()
            .setSampleMimeType(MimeTypes.APPLICATION_SUBRIP)
            .build()
    }
}
