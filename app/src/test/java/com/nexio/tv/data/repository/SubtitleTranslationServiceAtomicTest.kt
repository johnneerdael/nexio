package com.nexio.tv.data.repository

import com.nexio.tv.core.integration.IntegrationCallResult
import com.nexio.tv.core.integration.IntegrationCallSpec
import com.nexio.tv.core.integration.IntegrationFetchOptions
import com.nexio.tv.core.integration.IntegrationFetchResult
import com.nexio.tv.core.integration.IntegrationRuntime
import com.nexio.tv.core.integration.IntegrationStreamSpec
import com.nexio.tv.core.integration.IntegrationSpec
import com.nexio.tv.data.integration.subtitles.SubtitleSourceDownloadIntegrationProvider
import com.nexio.tv.data.integration.subtitles.SubtitleTranslationIntegrationProvider
import com.nexio.tv.data.integration.subtitles.transport.SubtitleSourceDownloadTransport
import com.nexio.tv.data.integration.subtitles.transport.SubtitleTranslationTransport
import com.nexio.tv.domain.model.SubtitleTranslationProvider
import com.nexio.tv.domain.model.SubtitleTranslationSettings
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for [SubtitleTranslationService.translateSrtAtomically].
 *
 * The [makeAtomicService] helper constructs a real service instance with
 * no-op integration providers and injects the supplied [rawResponder] lambda
 * into [SubtitleTranslationService.atomicRawResponder] so no network traffic
 * is produced. The atomic path itself is fully exercised.
 */
private fun makeAtomicService(
    rawResponder: (
        systemPrompt: String,
        userPayload: String,
        sourceLanguageName: String,
        targetLanguageName: String,
        settings: SubtitleTranslationSettings
    ) -> String?
): SubtitleTranslationService {
    val noopRuntime = object : IntegrationRuntime {
        override suspend fun <T> get(
            spec: IntegrationSpec<T>,
            options: IntegrationFetchOptions
        ): IntegrationFetchResult<T> = IntegrationFetchResult.Missing

        override suspend fun <T> call(
            spec: IntegrationCallSpec<T>
        ): IntegrationCallResult<T> = spec.call()

        override suspend fun <T> open(
            spec: IntegrationStreamSpec<T>
        ) = null
    }
    val httpClient = OkHttpClient()
    // Use the lambda constructor for OpenRouterReasoningModels so we don't
    // need a real Context. The atomic path never calls isReasoningModel.
    val stubReasoningModels = OpenRouterReasoningModels { emptySet() }
    val instance = SubtitleTranslationService(
        context = mockk(relaxed = true),
        subtitleTranslationIntegrationProvider = SubtitleTranslationIntegrationProvider(
            noopRuntime,
            SubtitleTranslationTransport(httpClient)
        ),
        subtitleSourceDownloadIntegrationProvider = SubtitleSourceDownloadIntegrationProvider(
            noopRuntime,
            SubtitleSourceDownloadTransport(httpClient)
        ),
        reasoningModels = stubReasoningModels
    )
    instance.atomicRawResponder = rawResponder
    return instance
}

class SubtitleTranslationServiceAtomicTest {

    private val sampleSourceSrt = """
        1
        00:00:01,000 --> 00:00:03,000
        Hello world

        2
        00:00:05,000 --> 00:00:07,500
        Second line

    """.trimIndent() + "\n"

    private val sampleTranslatedSrt = """
        1
        00:00:01,000 --> 00:00:03,000
        Hallo wereld

        2
        00:00:05,000 --> 00:00:07,500
        Tweede regel

    """.trimIndent() + "\n"

    private val baseSettings = SubtitleTranslationSettings(
        enabled = true,
        provider = SubtitleTranslationProvider.OPENAI,
        apiKey = "test-key",
        model = "gpt-4o-mini",
        baseUrl = "https://example.test/v1"
    )

    @Test
    fun `returns null when settings enabled is false`() = runBlocking {
        val service = makeAtomicService(
            rawResponder = { _, _, _, _, _ -> sampleTranslatedSrt }
        )
        val settings = baseSettings.copy(enabled = false)
        val result = service.translateSrtAtomically(
            srt = sampleSourceSrt,
            sourceLanguageCode = "en",
            targetLanguageCode = "nl",
            settings = settings
        )
        assertNull(result)
    }

    @Test
    fun `returns null when apiKey is blank`() = runBlocking {
        val service = makeAtomicService(
            rawResponder = { _, _, _, _, _ -> sampleTranslatedSrt }
        )
        val settings = baseSettings.copy(apiKey = "  ")
        val result = service.translateSrtAtomically(
            srt = sampleSourceSrt,
            sourceLanguageCode = "en",
            targetLanguageCode = "nl",
            settings = settings
        )
        assertNull(result)
    }

    @Test
    fun `returns translated SRT when provider response validates`() = runBlocking {
        val service = makeAtomicService(
            rawResponder = { _, _, _, _, _ -> sampleTranslatedSrt }
        )
        val result = service.translateSrtAtomically(
            srt = sampleSourceSrt,
            sourceLanguageCode = "en",
            targetLanguageCode = "nl",
            settings = baseSettings
        )
        assertNotNull(result)
        assertEquals(sampleTranslatedSrt.trim(), result!!.trim())
    }

    @Test
    fun `returns null when provider response has fewer cues than source`() = runBlocking {
        val truncated = """
            1
            00:00:01,000 --> 00:00:03,000
            Hallo wereld

        """.trimIndent() + "\n"
        val service = makeAtomicService(
            rawResponder = { _, _, _, _, _ -> truncated }
        )
        val result = service.translateSrtAtomically(
            srt = sampleSourceSrt,
            sourceLanguageCode = "en",
            targetLanguageCode = "nl",
            settings = baseSettings
        )
        assertNull(result)
    }

    @Test
    fun `returns null when provider response has no timestamp lines`() = runBlocking {
        val garbage = "I'm sorry, I cannot translate that.\n"
        val service = makeAtomicService(
            rawResponder = { _, _, _, _, _ -> garbage }
        )
        val result = service.translateSrtAtomically(
            srt = sampleSourceSrt,
            sourceLanguageCode = "en",
            targetLanguageCode = "nl",
            settings = baseSettings
        )
        assertNull(result)
    }

    @Test
    fun `strips conversational preamble before the first cue index`() = runBlocking {
        val withPreamble = "Sure! Here's the translation:\n\n" + sampleTranslatedSrt
        val service = makeAtomicService(
            rawResponder = { _, _, _, _, _ -> withPreamble }
        )
        val result = service.translateSrtAtomically(
            srt = sampleSourceSrt,
            sourceLanguageCode = "en",
            targetLanguageCode = "nl",
            settings = baseSettings
        )
        assertNotNull(result)
        assertEquals(sampleTranslatedSrt.trim(), result!!.trim())
    }
}
