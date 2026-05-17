package com.nexio.tv.ui.screens.player.translation

import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TranslatedSubtitleTimelineStoreTest {

    @Test
    fun lookupReturnsTranslatedCueOnlyForMatchingSessionAndCue() {
        val store = TranslatedSubtitleTimelineStore()
        val session = session(targetLanguage = "nl")
        val otherLanguageSession = session(targetLanguage = "de")
        val source = cueGroup("bonjour", presentationTimeUs = 1_000L)

        store.beginSession(session)
        store.putTranslatedCueGroup(
            sessionKey = session,
            sourceCueGroup = source,
            translatedCueGroup = cueGroup("hallo", presentationTimeUs = 1_000L)
        )

        assertEquals("hallo", store.lookupCueGroup(session, source)?.singleCueText())
        assertNull(store.lookupCueGroup(otherLanguageSession, source))
        assertEquals(
            TranslationTimelineStats(
                sourceCueCount = 1,
                translatedCueCount = 1,
                pendingBackfillCount = 0,
                hitCount = 1,
                missCount = 0
            ),
            store.stats(session)
        )
        assertEquals(
            TranslationTimelineStats(
                sourceCueCount = 0,
                translatedCueCount = 0,
                pendingBackfillCount = 0,
                hitCount = 0,
                missCount = 0
            ),
            store.stats(otherLanguageSession)
        )
    }

    @Test
    fun missRegistrationStoresSourceCueForBackfillOnce() {
        val store = TranslatedSubtitleTimelineStore()
        val session = session()
        val source = cueGroup(" bonjour ", presentationTimeUs = 1_000L)

        store.beginSession(session)
        store.registerMiss(session, source)
        store.registerMiss(session, source)

        val pendingBackfill = store.pendingBackfill(session)
        assertEquals(1, pendingBackfill.size)
        assertEquals("bonjour", pendingBackfill.single().sourceText)
        assertEquals(
            TranslationTimelineStats(
                sourceCueCount = 1,
                translatedCueCount = 0,
                pendingBackfillCount = 1,
                hitCount = 0,
                missCount = 0
            ),
            store.stats(session)
        )
    }

    @Test
    fun lookupThenRegisterMissCountsAsOneMiss() {
        val store = TranslatedSubtitleTimelineStore()
        val session = session()
        val source = cueGroup("bonjour", presentationTimeUs = 1_000L)

        store.beginSession(session)
        assertNull(store.lookupCueGroup(session, source))
        store.registerMiss(session, source)

        assertEquals(
            TranslationTimelineStats(
                sourceCueCount = 1,
                translatedCueCount = 0,
                pendingBackfillCount = 1,
                hitCount = 0,
                missCount = 1
            ),
            store.stats(session)
        )
    }

    @Test
    fun putTranslatedCueGroupRemovesPendingBackfill() {
        val store = TranslatedSubtitleTimelineStore()
        val session = session()
        val source = cueGroup("bonjour", presentationTimeUs = 1_000L)

        store.beginSession(session)
        store.registerMiss(session, source)
        store.putTranslatedCueGroup(
            sessionKey = session,
            sourceCueGroup = source,
            translatedCueGroup = cueGroup("hallo", presentationTimeUs = 1_000L)
        )

        assertEquals(emptyList<TranslationTimelineSourceCue>(), store.pendingBackfill(session))
        assertEquals(
            TranslationTimelineStats(
                sourceCueCount = 1,
                translatedCueCount = 1,
                pendingBackfillCount = 0,
                hitCount = 0,
                missCount = 0
            ),
            store.stats(session)
        )
    }

    @Test
    fun lateRegisterMissDoesNotRequeueTranslatedCue() {
        val store = TranslatedSubtitleTimelineStore()
        val session = session()
        val source = cueGroup("bonjour", presentationTimeUs = 1_000L)

        store.beginSession(session)
        assertNull(store.lookupCueGroup(session, source))
        store.putTranslatedCueGroup(
            sessionKey = session,
            sourceCueGroup = source,
            translatedCueGroup = cueGroup("hallo", presentationTimeUs = 1_000L)
        )
        store.registerMiss(session, source)

        assertEquals(emptyList<TranslationTimelineSourceCue>(), store.pendingBackfill(session))
        assertEquals(
            TranslationTimelineStats(
                sourceCueCount = 1,
                translatedCueCount = 1,
                pendingBackfillCount = 0,
                hitCount = 0,
                missCount = 1
            ),
            store.stats(session)
        )
    }

    @Test
    fun beginSessionClearsOldRecordsWhenSessionChanges() {
        val store = TranslatedSubtitleTimelineStore()
        val first = session(streamKey = "first")
        val second = session(streamKey = "second")
        val source = cueGroup("bonjour", presentationTimeUs = 1_000L)

        store.beginSession(first)
        store.putTranslatedCueGroup(
            sessionKey = first,
            sourceCueGroup = source,
            translatedCueGroup = cueGroup("hallo", presentationTimeUs = 1_000L)
        )
        store.beginSession(second)

        assertNull(store.lookupCueGroup(first, source))
        assertEquals(emptyList<TranslationTimelineSourceCue>(), store.pendingBackfill(first))
        assertEquals(
            TranslationTimelineStats(
                sourceCueCount = 0,
                translatedCueCount = 0,
                pendingBackfillCount = 0,
                hitCount = 0,
                missCount = 0
            ),
            store.stats(first)
        )
    }

    @Test
    fun translateCueGroupTextsPreservesCueMetadataAndTiming() {
        val sourceCue = Cue.Builder()
            .setText("Bonjour")
            .setPosition(0.25f)
            .build()
        val sourceGroup = CueGroup(listOf(sourceCue), 1_000L)

        val translated = TranslatedSubtitleTimelineStore.translateCueGroupTexts(
            cueGroup = sourceGroup,
            translatedTexts = mapOf("Bonjour" to "Hallo")
        )

        assertEquals(1_000L, translated.presentationTimeUs)
        assertEquals("Hallo", translated.singleCueText())
        assertEquals(sourceCue.position, translated.cues.single().position)
    }

    private fun session(
        streamKey: String = "stream",
        trackKey: String = "track",
        targetLanguage: String = "nl",
        settingsKey: String = "settings"
    ): TranslationTimelineSessionKey {
        return TranslationTimelineSessionKey(
            streamKey = streamKey,
            trackKey = trackKey,
            targetLanguage = targetLanguage,
            settingsKey = settingsKey
        )
    }

    private fun cueGroup(text: String, presentationTimeUs: Long): CueGroup {
        return CueGroup(listOf(Cue.Builder().setText(text).build()), presentationTimeUs)
    }

    private fun CueGroup.singleCueText(): String? {
        return cues.singleOrNull()?.text?.toString()
    }
}
