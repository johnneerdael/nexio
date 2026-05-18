package com.nexio.tv.ui.screens.player.translation

import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import org.junit.Assert.assertEquals
import org.junit.Test

class TimelinePublishingTextCueSinkTest {
    @Test
    fun publishesNonBlankCueGroup() {
        val store = TranslatedSubtitleTimelineStore()
        val session = session()
        store.beginSession(session)
        val sink = TimelinePublishingTextCueSink(
            sessionKey = session,
            container = EmbeddedSubtitleContainer.MP4,
            timelineStore = store,
            sourceLanguage = "en"
        )

        sink.publish(CueGroup(listOf(Cue.Builder().setText("Hello").build()), 1_000L))

        assertEquals(1, sink.sampleCount)
        assertEquals(1, store.stats(session).sourceCueCount)
        assertEquals(1, store.stats(session).pendingBackfillCount)
    }

    @Test
    fun skipsBlankCueGroup() {
        val store = TranslatedSubtitleTimelineStore()
        val session = session()
        store.beginSession(session)
        val sink = TimelinePublishingTextCueSink(
            sessionKey = session,
            container = EmbeddedSubtitleContainer.MP4,
            timelineStore = store
        )

        sink.publish(CueGroup(listOf(Cue.Builder().setText("   ").build()), 1_000L))

        assertEquals(0, sink.sampleCount)
        assertEquals(0, store.stats(session).sourceCueCount)
    }

    private fun session(): TranslationTimelineSessionKey {
        return TranslationTimelineSessionKey(
            streamKey = "stream",
            trackKey = "track",
            targetLanguage = "nl",
            settingsKey = "settings"
        )
    }
}
