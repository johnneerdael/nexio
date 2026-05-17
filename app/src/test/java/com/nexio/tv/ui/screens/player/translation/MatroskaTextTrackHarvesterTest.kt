package com.nexio.tv.ui.screens.player.translation

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Test

class MatroskaTextTrackHarvesterTest {

    @Test
    fun sinkConvertsSamplesToSourceCueGroups() {
        val store = TranslatedSubtitleTimelineStore()
        val session = session()
        val sink = TimelinePublishingMatroskaTextTrackSink(
            sessionKey = session,
            timelineStore = store
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
