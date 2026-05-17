package com.nexio.tv.ui.screens.player.translation

import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import com.nexio.tv.data.repository.SubtitleTranslationService
import com.nexio.tv.domain.model.SubtitleTranslationSettings
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SubtitleTimelineTranslationPipelineTest {

    @Test
    fun translatesPendingBackfillAndPublishesTimelineHits() = runTest {
        val service = mockk<SubtitleTranslationService>(relaxed = true)
        val settings = settings()
        val store = TranslatedSubtitleTimelineStore()
        val session = session()
        val source = cueGroup("bonjour", 1_000L)

        coEvery {
            service.translateCueTexts(
                texts = listOf("bonjour"),
                targetLanguageCode = "nl",
                sourceLanguageCode = "fr",
                settings = settings
            )
        } returns Result.success(mapOf("bonjour" to "hallo"))

        store.beginSession(session)
        store.registerMiss(session, source)

        SubtitleTimelineTranslationPipeline(service).translatePending(
            session = session,
            store = store,
            sourceLanguageCode = "fr",
            targetLanguageCode = "nl",
            settings = settings
        )

        assertEquals("hallo", store.lookupCueGroup(session, source)?.singleCueText())
        coVerify(exactly = 1) {
            service.translateCueTexts(
                texts = listOf("bonjour"),
                targetLanguageCode = "nl",
                sourceLanguageCode = "fr",
                settings = settings
            )
        }
    }

    @Test
    fun emptyPendingBackfillDoesNotCallService() = runTest {
        val service = mockk<SubtitleTranslationService>(relaxed = true)
        val store = TranslatedSubtitleTimelineStore()
        val session = session()

        store.beginSession(session)

        SubtitleTimelineTranslationPipeline(service).translatePending(
            session = session,
            store = store,
            sourceLanguageCode = "fr",
            targetLanguageCode = "nl",
            settings = settings()
        )

        coVerify(exactly = 0) {
            service.translateCueTexts(
                texts = any(),
                targetLanguageCode = any(),
                sourceLanguageCode = any(),
                settings = any()
            )
        }
    }

    @Test
    fun failureLeavesPendingBackfillIntact() = runTest {
        val service = mockk<SubtitleTranslationService>(relaxed = true)
        val settings = settings()
        val store = TranslatedSubtitleTimelineStore()
        val session = session()
        val source = cueGroup("bonjour", 1_000L)

        coEvery {
            service.translateCueTexts(
                texts = any(),
                targetLanguageCode = any(),
                sourceLanguageCode = any(),
                settings = any()
            )
        } returns Result.failure(IllegalStateException("provider failed"))

        store.beginSession(session)
        store.registerMiss(session, source)

        SubtitleTimelineTranslationPipeline(service).translatePending(
            session = session,
            store = store,
            sourceLanguageCode = "fr",
            targetLanguageCode = "nl",
            settings = settings
        )

        assertEquals("bonjour", store.pendingBackfill(session).single().sourceText)
        assertNull(store.lookupCueGroup(session, source))
    }

    @Test
    fun batchingCapsMaxBatchSize() = runTest {
        val service = mockk<SubtitleTranslationService>(relaxed = true)
        val capturedBatches = mutableListOf<List<String>>()
        val settings = settings()
        val store = TranslatedSubtitleTimelineStore()
        val session = session()

        coEvery {
            service.translateCueTexts(
                texts = any(),
                targetLanguageCode = "nl",
                sourceLanguageCode = "fr",
                settings = settings
            )
        } answers {
            @Suppress("UNCHECKED_CAST")
            val texts = arg<List<String>>(0)
            capturedBatches += texts
            Result.success(texts.associateWith { text -> "translated-$text" })
        }

        store.beginSession(session)
        for (index in 0 until 3) {
            store.registerMiss(session, cueGroup("text$index", index * 1_000L))
        }

        SubtitleTimelineTranslationPipeline(
            translationService = service,
            maxBatchSize = 2
        ).translatePending(
            session = session,
            store = store,
            sourceLanguageCode = "fr",
            targetLanguageCode = "nl",
            settings = settings
        )

        assertEquals(listOf(listOf("text0", "text1"), listOf("text2")), capturedBatches)
    }

    private fun settings(): SubtitleTranslationSettings {
        return SubtitleTranslationSettings(
            enabled = true,
            apiKey = "test-key",
            model = "test-model"
        )
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
