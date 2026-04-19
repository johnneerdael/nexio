package com.nexio.tv.ui.screens.player

import android.content.Context
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.exoplayer.text.CueGroupSubtitleTranslator
import com.nexio.tv.data.repository.SubtitleTranslationService
import com.nexio.tv.domain.model.SubtitleTranslationDefaults
import com.nexio.tv.domain.model.SubtitleTranslationProvider
import com.nexio.tv.domain.model.SubtitleTranslationSettings
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.EmptyCoroutineContext

class PlayerRuntimeControllerBuiltInAiGroundworkTest {
    @Test
    fun `built in bitmap cues block ai subtitle enablement`() {
        assertEquals(
            false,
            shouldAllowBuiltInAiSubtitleEnable(
                selectedAddonSubtitlePresent = false,
                selectedSubtitleTrackIndex = 0,
                currentCueGroupHasCues = true,
                currentCueGroupIsTextOnly = false
            )
        )
    }

    @Test
    fun `built in ai subtitle enablement is allowed before cue shape is known`() {
        assertEquals(
            true,
            shouldAllowBuiltInAiSubtitleEnable(
                selectedAddonSubtitlePresent = false,
                selectedSubtitleTrackIndex = 0,
                currentCueGroupHasCues = false,
                currentCueGroupIsTextOnly = false
            )
        )
    }

    @Test
    fun `built in translator configuration token changes when provider configuration changes`() {
        val format = Format.Builder()
            .setSampleMimeType(MimeTypes.APPLICATION_SUBRIP)
            .build()
        var settings = SubtitleTranslationSettings(
            enabled = true,
            provider = SubtitleTranslationProvider.OPENAI,
            apiKey = "same-key",
            model = SubtitleTranslationDefaults.OPENAI_MODEL,
            baseUrl = SubtitleTranslationDefaults.OPENAI_BASE_URL
        )
        val translator = BuiltInSubtitleCueTranslator(
            scope = CoroutineScope(EmptyCoroutineContext),
            translationService = mockk<SubtitleTranslationService>(relaxed = true),
            isEnabledProvider = { true },
            settingsProvider = { settings },
            targetLanguageProvider = { "nl" },
            onTranslatingChanged = {},
            onTranslationError = {}
        )

        val openAiToken = translator.getConfigurationToken(format)

        settings = settings.copy(
            provider = SubtitleTranslationProvider.ANTHROPIC,
            model = SubtitleTranslationDefaults.ANTHROPIC_MODEL,
            baseUrl = SubtitleTranslationDefaults.ANTHROPIC_BASE_URL
        )

        assertNotEquals(openAiToken, translator.getConfigurationToken(format))

        settings = settings.copy(apiKey = "")

        assertNull(translator.getConfigurationToken(format))
    }

    @Test
    fun `built in translator configuration token is null when native translator is disabled`() {
        val format = Format.Builder()
            .setSampleMimeType(MimeTypes.APPLICATION_SUBRIP)
            .build()
        val translator = BuiltInSubtitleCueTranslator(
            scope = CoroutineScope(EmptyCoroutineContext),
            translationService = mockk<SubtitleTranslationService>(relaxed = true),
            isEnabledProvider = { false },
            settingsProvider = {
                SubtitleTranslationSettings(
                    enabled = true,
                    apiKey = "configured-key"
                )
            },
            targetLanguageProvider = { "nl" },
            onTranslatingChanged = {},
            onTranslationError = {}
        )

        assertNull(translator.getConfigurationToken(format))
    }

    @Test
    fun `built in translator suppresses repeated provider requests after failure`() = runBlocking {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return MockResponse()
                    .setResponseCode(429)
                    .setBody("""{"error":{"message":"quota exceeded"}}""")
            }
        }
        server.start()
        try {
            val service = SubtitleTranslationService(
                context = mockk<Context>(relaxed = true),
                httpClient = OkHttpClient()
            )
            val translator = BuiltInSubtitleCueTranslator(
                scope = CoroutineScope(EmptyCoroutineContext),
                translationService = service,
                isEnabledProvider = { true },
                settingsProvider = {
                    SubtitleTranslationSettings(
                        enabled = true,
                        provider = SubtitleTranslationProvider.OPENAI,
                        apiKey = "configured-key",
                        model = "test-model",
                        baseUrl = server.url("/v1").toString()
                    )
                },
                targetLanguageProvider = { "nl" },
                onTranslatingChanged = {},
                onTranslationError = {}
            )
            val format = Format.Builder()
                .setSampleMimeType(MimeTypes.APPLICATION_SUBRIP)
                .build()
            val cueGroups = listOf(
                CueGroup(
                    listOf(Cue.Builder().setText("Hello").build()),
                    0L
                )
            )

            val first = CompletableDeferred<Exception>()
            val second = CompletableDeferred<Exception>()

            translator.translate(format, cueGroups, failureCallback(first))
            withTimeout(10_000) { first.await() }
            val requestCountAfterFirstFailure = server.requestCount

            translator.translate(format, cueGroups, failureCallback(second))
            withTimeout(10_000) { second.await() }

            assertEquals(
                "Expected second failure to be suppressed without another provider request",
                requestCountAfterFirstFailure,
                server.requestCount
            )
        } finally {
            server.shutdown()
        }
    }

    private fun failureCallback(
        failure: CompletableDeferred<Exception>
    ): CueGroupSubtitleTranslator.TranslationCallback {
        return object : CueGroupSubtitleTranslator.TranslationCallback {
            override fun onSuccess(translatedCueGroups: List<CueGroup>) {
                failure.complete(IllegalStateException("Expected translation failure"))
            }

            override fun onFailure(error: Exception) {
                failure.complete(error)
            }
        }
    }
}
