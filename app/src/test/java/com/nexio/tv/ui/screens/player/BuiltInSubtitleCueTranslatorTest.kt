package com.nexio.tv.ui.screens.player

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.exoplayer.text.CueGroupSubtitleTranslator
import com.google.common.collect.ImmutableList
import com.nexio.tv.data.repository.SubtitleTranslationService
import com.nexio.tv.domain.model.SubtitleTranslationSettings
import com.nexio.tv.ui.screens.player.translation.TranslatedSubtitleTimelineStore
import com.nexio.tv.ui.screens.player.translation.TranslationTimelineSessionKey
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class BuiltInSubtitleCueTranslatorTest {
    private fun translator(
        timelineStoreProvider: () -> TranslatedSubtitleTimelineStore? = { null },
        timelineSessionProvider: () -> TranslationTimelineSessionKey? = { null }
    ): BuiltInSubtitleCueTranslator {
        return BuiltInSubtitleCueTranslator(
            scope = TestScope(StandardTestDispatcher()),
            translationService = mockk<SubtitleTranslationService>(relaxed = true),
            isEnabledProvider = { true },
            settingsProvider = {
                SubtitleTranslationSettings(
                    enabled = true,
                    apiKey = "test-key",
                    model = "test-model"
                )
            },
            targetLanguageProvider = { "nl" },
            onTranslatingChanged = {},
            onTranslationError = {},
            timelineStoreProvider = timelineStoreProvider,
            timelineSessionProvider = timelineSessionProvider
        )
    }

    @Test
    fun configurationTokenIsDisabledForTranscodedAssSsaTracks() {
        val transcodedAssFormat = Format.Builder()
            .setSampleMimeType(MimeTypes.APPLICATION_MEDIA3_CUES)
            .setCodecs(MimeTypes.TEXT_SSA)
            .build()

        assertNull(translator().getConfigurationToken(transcodedAssFormat))
    }

    @Test
    fun configurationTokenIsStillEnabledForNonAssTextTracks() {
        val format = Format.Builder()
            .setSampleMimeType(MimeTypes.APPLICATION_MEDIA3_CUES)
            .setCodecs(MimeTypes.TEXT_VTT)
            .setLanguage("en")
            .build()

        assertNotNull(translator().getConfigurationToken(format))
    }

    @Test
    fun getTranslatedCueGroupReturnsTimelineHit() {
        val store = TranslatedSubtitleTimelineStore()
        val session = timelineSession()
        val source = cueGroup("bonjour", 1_000L)

        store.beginSession(session)
        store.putTranslatedCueGroup(
            sessionKey = session,
            sourceCueGroup = source,
            translatedCueGroup = cueGroup("hallo", 1_000L)
        )

        val translated = translator(
            timelineStoreProvider = { store },
            timelineSessionProvider = { session }
        ).getTranslatedCueGroup(format(), source)

        assertEquals("hallo", translated?.cues?.firstOrNull()?.text?.toString())
    }

    @Test
    fun renderedMissIsRegisteredForBackfill() {
        val store = TranslatedSubtitleTimelineStore()
        val session = timelineSession()

        store.beginSession(session)

        translator(
            timelineStoreProvider = { store },
            timelineSessionProvider = { session }
        ).onCueGroupRenderedWithoutTranslation(format(), cueGroup("bonjour", 1_000L))

        assertEquals(1, store.pendingBackfill(session).size)
    }

    @Test
    fun translateCallsArrivingWithinDebounceWindowAreCoalescedIntoSingleProviderRequest() = runTest {
        val service = mockk<SubtitleTranslationService>(relaxed = true)
        val capturedTexts = slot<List<String>>()
        coEvery {
            service.translateCueTexts(
                texts = capture(capturedTexts),
                targetLanguageCode = any(),
                sourceLanguageCode = any(),
                settings = any()
            )
        } answers {
            Result.success(capturedTexts.captured.associateWith { it.uppercase() })
        }

        val translator = BuiltInSubtitleCueTranslator(
            scope = this,
            translationService = service,
            isEnabledProvider = { true },
            settingsProvider = {
                SubtitleTranslationSettings(
                    enabled = true,
                    apiKey = "test-key",
                    model = "test-model"
                )
            },
            targetLanguageProvider = { "nl" },
            onTranslatingChanged = {},
            onTranslationError = {},
            dispatchDebounceMs = 250L,
            maxBatchCueGroups = 60
        )

        val format = Format.Builder()
            .setSampleMimeType(MimeTypes.APPLICATION_MEDIA3_CUES)
            .setCodecs(MimeTypes.TEXT_VTT)
            .setLanguage("en")
            .build()

        val callback1 = RecordingCallback()
        val callback2 = RecordingCallback()
        val callback3 = RecordingCallback()

        translator.translate(format, listOf(cueGroup("hello", 0L)), callback1)
        translator.translate(format, listOf(cueGroup("world", 1_000L)), callback2)
        advanceTimeBy(50L)
        translator.translate(format, listOf(cueGroup("hello", 2_000L)), callback3)

        advanceTimeBy(300L)
        advanceUntilIdle()

        coVerify(exactly = 1) {
            service.translateCueTexts(
                texts = any(),
                targetLanguageCode = "nl",
                sourceLanguageCode = "en",
                settings = any()
            )
        }
        // Source texts deduped across all three pending calls.
        assertEquals(setOf("hello", "world"), capturedTexts.captured.toSet())
        assertEquals("HELLO", callback1.successCueText())
        assertEquals("WORLD", callback2.successCueText())
        assertEquals("HELLO", callback3.successCueText())
    }

    @Test
    fun translateExceedingMaxBatchSizeFlushesImmediatelyWithoutDebounce() = runTest {
        val service = mockk<SubtitleTranslationService>(relaxed = true)
        coEvery {
            service.translateCueTexts(
                texts = any(),
                targetLanguageCode = any(),
                sourceLanguageCode = any(),
                settings = any()
            )
        } answers {
            @Suppress("UNCHECKED_CAST")
            val texts = arg<List<String>>(0)
            Result.success(texts.associateWith { it.uppercase() })
        }

        val translator = BuiltInSubtitleCueTranslator(
            scope = this,
            translationService = service,
            isEnabledProvider = { true },
            settingsProvider = {
                SubtitleTranslationSettings(
                    enabled = true,
                    apiKey = "test-key",
                    model = "test-model"
                )
            },
            targetLanguageProvider = { "nl" },
            onTranslatingChanged = {},
            onTranslationError = {},
            dispatchDebounceMs = 10_000L, // very long debounce — would block test if size flush didn't fire
            maxBatchCueGroups = 3
        )

        val format = Format.Builder()
            .setSampleMimeType(MimeTypes.APPLICATION_MEDIA3_CUES)
            .setCodecs(MimeTypes.TEXT_VTT)
            .setLanguage("en")
            .build()

        val callbacks = List(3) { RecordingCallback() }
        repeat(3) { i ->
            translator.translate(format, listOf(cueGroup("text$i", i * 1_000L)), callbacks[i])
        }
        advanceUntilIdle()

        coVerify(exactly = 1) {
            service.translateCueTexts(
                texts = any(),
                targetLanguageCode = any(),
                sourceLanguageCode = any(),
                settings = any()
            )
        }
        callbacks.forEachIndexed { i, callback ->
            assertEquals("TEXT$i", callback.successCueText())
        }
    }

    @Test
    fun largePrefetchRequestsPublishTranslatedCueGroupsProgressively() = runTest {
        val service = mockk<SubtitleTranslationService>(relaxed = true)
        val requestedSizes = mutableListOf<Int>()
        coEvery {
            service.translateCueTexts(
                texts = any(),
                targetLanguageCode = any(),
                sourceLanguageCode = any(),
                settings = any()
            )
        } answers {
            @Suppress("UNCHECKED_CAST")
            val texts = arg<List<String>>(0)
            requestedSizes += texts.size
            Result.success(texts.associateWith { it.uppercase() })
        }

        val translator = BuiltInSubtitleCueTranslator(
            scope = this,
            translationService = service,
            isEnabledProvider = { true },
            settingsProvider = {
                SubtitleTranslationSettings(
                    enabled = true,
                    apiKey = "test-key",
                    model = "test-model"
                )
            },
            targetLanguageProvider = { "nl" },
            onTranslatingChanged = {},
            onTranslationError = {},
            dispatchDebounceMs = 0L,
            maxBatchCueGroups = 20
        )

        val format = Format.Builder()
            .setSampleMimeType(MimeTypes.APPLICATION_MEDIA3_CUES)
            .setCodecs(MimeTypes.TEXT_VTT)
            .setLanguage("en")
            .build()
        val callback = ProgressiveRecordingCallback()
        val cueGroups = List(45) { i -> cueGroup("text$i", i * 1_000L) }

        translator.translate(format, cueGroups, callback)
        advanceUntilIdle()

        assertEquals(listOf(5, 10, 20, 10), requestedSizes)
        assertEquals(listOf(5, 10, 20, 10), callback.successSizes())
        assertEquals("TEXT0", callback.successes.first().first().cues.first().text.toString())
        assertEquals("TEXT44", callback.successes.last().last().cues.first().text.toString())
    }

    private fun cueGroup(text: String, presentationTimeUs: Long): CueGroup {
        val cue = Cue.Builder().setText(text).build()
        return CueGroup(ImmutableList.of(cue), presentationTimeUs)
    }

    private fun format(): Format {
        return Format.Builder()
            .setSampleMimeType(MimeTypes.APPLICATION_MEDIA3_CUES)
            .setCodecs(MimeTypes.TEXT_VTT)
            .setLanguage("en")
            .build()
    }

    private fun timelineSession(): TranslationTimelineSessionKey {
        return TranslationTimelineSessionKey(
            streamKey = "stream",
            trackKey = "track",
            targetLanguage = "nl",
            settingsKey = "settings"
        )
    }

    private class RecordingCallback : CueGroupSubtitleTranslator.TranslationCallback {
        private val latch = CountDownLatch(1)
        private var success: List<CueGroup>? = null
        private var failure: Exception? = null

        override fun onSuccess(translatedCueGroups: List<CueGroup>) {
            success = translatedCueGroups
            latch.countDown()
        }

        override fun onFailure(exception: Exception) {
            failure = exception
            latch.countDown()
        }

        fun successCueText(): String? {
            assertTrue("callback never fired", latch.await(2, TimeUnit.SECONDS))
            return success?.firstOrNull()?.cues?.firstOrNull()?.text?.toString()
        }
    }

    private class ProgressiveRecordingCallback : CueGroupSubtitleTranslator.TranslationCallback {
        val successes = mutableListOf<List<CueGroup>>()
        val failures = mutableListOf<Exception>()

        override fun onSuccess(translatedCueGroups: List<CueGroup>) {
            successes += translatedCueGroups
        }

        override fun onFailure(exception: Exception) {
            failures += exception
        }

        fun successSizes(): List<Int> = successes.map { it.size }
    }
}
