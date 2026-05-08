package com.nexio.tv.ui.screens.player

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.exoplayer.text.CueGroupSubtitleTranslator
import com.google.common.collect.ImmutableList
import com.nexio.tv.data.repository.SubtitleTranslationService
import com.nexio.tv.domain.model.SubtitleTranslationSettings
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
    private fun translator(): BuiltInSubtitleCueTranslator {
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
            onTranslationError = {}
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

    private fun cueGroup(text: String, presentationTimeUs: Long): CueGroup {
        val cue = Cue.Builder().setText(text).build()
        return CueGroup(ImmutableList.of(cue), presentationTimeUs)
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
}
