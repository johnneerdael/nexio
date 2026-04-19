package com.nexio.tv.data.repository

import android.content.Context
import com.nexio.tv.domain.model.SubtitleTranslationProvider
import com.nexio.tv.domain.model.SubtitleTranslationSettings
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleTranslationServiceProviderTest {
    @Test
    fun cueCacheKeyIncludesProviderModelAndEndpoint() {
        val baseline = SubtitleTranslationSettings(
            provider = SubtitleTranslationProvider.OPENAI,
            model = "gpt-5-nano",
            baseUrl = "https://api.openai.com/v1"
        )

        assertNotEquals(
            subtitleTranslationCueCacheKey("Hello", "nl", baseline),
            subtitleTranslationCueCacheKey("Hello", "nl", baseline.copy(provider = SubtitleTranslationProvider.ANTHROPIC))
        )
        assertNotEquals(
            subtitleTranslationCueCacheKey("Hello", "nl", baseline),
            subtitleTranslationCueCacheKey("Hello", "nl", baseline.copy(model = "gpt-5-mini"))
        )
        assertNotEquals(
            subtitleTranslationCueCacheKey("Hello", "nl", baseline),
            subtitleTranslationCueCacheKey("Hello", "nl", baseline.copy(baseUrl = "https://openrouter.ai/api/v1"))
        )
    }

    @Test
    fun translatedSubtitleIdIncludesProviderAndTargetLanguage() {
        val id = translatedSubtitleId(
            sourceSubtitleId = "addon-sub-1",
            targetLanguage = "nl",
            settings = SubtitleTranslationSettings(provider = SubtitleTranslationProvider.ANTHROPIC)
        )

        assertEquals("ai:anthropic:addon-sub-1:nl", id)
    }

    @Test
    fun diskCacheKeyIncludesProviderModelAndEndpoint() {
        val baseline = SubtitleTranslationSettings(
            provider = SubtitleTranslationProvider.OPENAI,
            model = "gpt-5-nano",
            baseUrl = "https://api.openai.com/v1"
        )

        assertNotEquals(
            subtitleTranslationDiskCacheKey("https://subs.example/movie.srt", "nl", baseline),
            subtitleTranslationDiskCacheKey("https://subs.example/movie.srt", "nl", baseline.copy(provider = SubtitleTranslationProvider.ANTHROPIC))
        )
        assertNotEquals(
            subtitleTranslationDiskCacheKey("https://subs.example/movie.srt", "nl", baseline),
            subtitleTranslationDiskCacheKey("https://subs.example/movie.srt", "nl", baseline.copy(model = "gpt-5-mini"))
        )
        assertNotEquals(
            subtitleTranslationDiskCacheKey("https://subs.example/movie.srt", "nl", baseline),
            subtitleTranslationDiskCacheKey("https://subs.example/movie.srt", "nl", baseline.copy(baseUrl = "https://openrouter.ai/api/v1"))
        )
    }

    @Test
    fun providerErrorClassificationDistinguishesAuthAndRateLimit() {
        assertEquals(
            SubtitleTranslationProviderError.InvalidApiKey,
            classifyProviderError(SubtitleTranslationProvider.OPENAI, 401, """{"error":{"message":"bad key"}}""")
        )
        assertEquals(
            SubtitleTranslationProviderError.InvalidApiKey,
            classifyProviderError(SubtitleTranslationProvider.ANTHROPIC, 403, """{"error":{"message":"forbidden"}}""")
        )
        assertEquals(
            SubtitleTranslationProviderError.RateLimited,
            classifyProviderError(SubtitleTranslationProvider.ANTHROPIC, 429, """{"error":{"message":"rate limited"}}""")
        )
        assertEquals(
            SubtitleTranslationProviderError.AccessDenied,
            classifyProviderError(
                SubtitleTranslationProvider.GEMINI,
                403,
                """
                {
                  "error": {
                    "code": 403,
                    "message": "Your project has been denied access. Please contact support.",
                    "status": "PERMISSION_DENIED"
                  }
                }
                """.trimIndent()
            )
        )
    }

    @Test
    fun defaultChunkConfigsFavorLargeSerialProviderRequests() {
        assertEquals(2_000, SubtitleTranslationService.DEFAULT_CUE_CHUNK_CONFIG.maxEntries)
        assertEquals(100_000, SubtitleTranslationService.DEFAULT_CUE_CHUNK_CONFIG.maxChars)
        assertEquals(1, SubtitleTranslationService.DEFAULT_CUE_CHUNK_CONFIG.maxParallelRequests)
        assertEquals(2_000, SubtitleTranslationService.ADDON_OVERLAY_CUE_CHUNK_CONFIG.maxEntries)
        assertEquals(100_000, SubtitleTranslationService.ADDON_OVERLAY_CUE_CHUNK_CONFIG.maxChars)
        assertEquals(1, SubtitleTranslationService.ADDON_OVERLAY_CUE_CHUNK_CONFIG.maxParallelRequests)
    }

    @Test
    fun dashScopeCueTranslationSendsSourceLanguageAndMapsMarkerOutput() = runTest {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "output": {
                        "choices": [
                          {
                            "message": {
                              "content": "[[0]] Hallo\\n[[1]] Tot ziens"
                            }
                          }
                        ]
                      }
                    }
                    """.trimIndent()
                )
        )
        server.start()
        try {
            val service = SubtitleTranslationService(
                context = mockk<Context>(relaxed = true),
                httpClient = OkHttpClient()
            )

            val result = service.translateCueTexts(
                texts = listOf("Czesc", "Do widzenia"),
                targetLanguageCode = "nl",
                sourceLanguageCode = "pl",
                settings = SubtitleTranslationSettings(
                    provider = SubtitleTranslationProvider.DASHSCOPE,
                    apiKey = "dashscope-key",
                    model = "qwen-mt-flash",
                    baseUrl = server.url("/api/v1").toString()
                )
            )

            assertEquals(mapOf("Czesc" to "Hallo", "Do widzenia" to "Tot ziens"), result.getOrThrow())

            val request = server.takeRequest()
            assertEquals("Bearer dashscope-key", request.getHeader("Authorization"))
            val body = request.body.readUtf8()
            assertTrue(body.contains(""""source_lang":"Polish""""))
            assertTrue(body.contains(""""target_lang":"Dutch""""))
            assertTrue(body.contains("[[0]] Czesc"))
            assertTrue(body.contains("[[1]] Do widzenia"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun structuredRequestProviderRejectionFallsBackToPlainRequest() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse()
                    .setResponseCode(400)
                    .setBody("""{"error":{"message":"response_format unsupported"}}""")
            )
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody(
                        """
                        {
                          "choices": [
                            {
                              "message": {
                                "content": "{\"items\":[{\"id\":0,\"text\":\"Hallo\"}]}"
                              }
                            }
                          ]
                        }
                        """.trimIndent()
                    )
            )
            val service = SubtitleTranslationService(
                context = mockk<Context>(relaxed = true),
                httpClient = OkHttpClient()
            )

            val result = service.translateCueTexts(
                texts = listOf("Hello"),
                targetLanguageCode = "nl",
                settings = SubtitleTranslationSettings(
                    provider = SubtitleTranslationProvider.OPENAI,
                    apiKey = "test-key",
                    model = "gpt-5-nano",
                    baseUrl = server.url("/v1").toString()
                )
            )

            assertEquals(mapOf("Hello" to "Hallo"), result.getOrThrow())
            assertEquals(2, server.requestCount)
            val structuredRequestBody = server.takeRequest().body.readUtf8()
            val fallbackRequestBody = server.takeRequest().body.readUtf8()
            assertTrue(structuredRequestBody.contains("response_format"))
            assertFalse(fallbackRequestBody.contains("response_format"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun rateLimitProviderErrorRetriesWithBackoffBeforeSucceeding() = runTest {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(429)
                .setHeader("Retry-After", "0")
                .setBody("""{"error":{"message":"quota exceeded"}}""")
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(429)
                .setHeader("Retry-After", "0")
                .setBody("""{"error":{"message":"quota exceeded"}}""")
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "choices": [
                        {
                          "message": {
                            "content": "{\"items\":[{\"id\":0,\"text\":\"Hallo\"}]}"
                          }
                        }
                      ]
                    }
                    """.trimIndent()
                )
        )
        server.start()
        try {
            val service = SubtitleTranslationService(
                context = mockk<Context>(relaxed = true),
                httpClient = OkHttpClient()
            )

            val result = service.translateCueTexts(
                texts = listOf("Hello"),
                targetLanguageCode = "nl",
                settings = SubtitleTranslationSettings(
                    provider = SubtitleTranslationProvider.OPENAI,
                    apiKey = "test-key",
                    model = "gpt-5-nano",
                    baseUrl = server.url("/v1").toString()
                )
            )

            assertEquals(mapOf("Hello" to "Hallo"), result.getOrThrow())
            assertEquals(3, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun authProviderErrorDoesNotSplitAdaptiveChunks() = runTest {
        assertProviderErrorDoesNotSplitAdaptiveChunks(statusCode = 401)
    }

    @Test
    fun rateLimitProviderErrorDoesNotSplitAdaptiveChunks() = runTest {
        assertProviderErrorDoesNotSplitAdaptiveChunks(
            statusCode = 429,
            expectedRequestCount = 4
        )
    }

    @Test
    fun geminiPermissionDeniedReportsProjectAccessFailure() = runTest {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(403)
                .setBody(
                    """
                    {
                      "error": {
                        "code": 403,
                        "message": "Your project has been denied access. Please contact support.",
                        "status": "PERMISSION_DENIED"
                      }
                    }
                    """.trimIndent()
                )
        )
        server.start()
        try {
            val service = SubtitleTranslationService(
                context = mockk<Context>(relaxed = true),
                httpClient = OkHttpClient()
            )

            val result = service.translateCueTexts(
                texts = listOf("Hello"),
                targetLanguageCode = "nl",
                settings = SubtitleTranslationSettings(
                    provider = SubtitleTranslationProvider.GEMINI,
                    apiKey = "test-key",
                    model = "gemini-2.5-flash",
                    baseUrl = server.url("/v1beta").toString()
                )
            )

            assertTrue(result.isFailure)
            assertEquals(
                "Subtitle translation provider denied access for this API key or project.",
                result.exceptionOrNull()?.message
            )
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun terminalProviderErrorDoesNotDrainQueuedChunks() = runTest {
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

            val result = service.translateCueTexts(
                texts = (1..20).map { index -> "Line $index" },
                targetLanguageCode = "nl",
                settings = SubtitleTranslationSettings(
                    provider = SubtitleTranslationProvider.GEMINI,
                    apiKey = "test-key",
                    model = "gemini-2.5-flash",
                    baseUrl = server.url("/v1beta").toString()
                ),
                chunkConfig = SubtitleTranslationChunkConfig(
                    maxEntries = 1,
                    maxChars = 10_000,
                    minSplitEntries = 1,
                    maxParallelRequests = 3
                )
            )

            assertTrue(result.isFailure)
            assertTrue(
                "Expected terminal failure to stop after retrying one active chunk, but saw ${server.requestCount} requests",
                server.requestCount <= 4
            )
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun protectedAssBatchTranslationSendsPlaceholderContractAndMapsItems() = runTest {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "choices": [
                        {
                          "message": {
                            "content": "{\"items\":[{\"id\":\"evt_1\",\"text\":\"Ik ben ⟦ASS_000⟧niet⟦ASS_001⟧ boos\"}]}"
                          }
                        }
                      ]
                    }
                    """.trimIndent()
                )
        )
        server.start()
        try {
            val service = SubtitleTranslationService(
                context = mockk<Context>(relaxed = true),
                httpClient = OkHttpClient()
            )
            val unit = AssSsaProtectedTranslationUnit.fromTokens(
                id = "evt_1",
                tokens = AssSsaTextTokenizer.tokenize("""I am {\i1}not{\i0} angry""")
            )

            val result = service.translateProtectedAssSsaUnits(
                units = listOf(unit),
                targetLanguageCode = "nl",
                sourceLanguageCode = "en",
                settings = SubtitleTranslationSettings(
                    provider = SubtitleTranslationProvider.OPENAI,
                    apiKey = "test-key",
                    model = "gpt-5-nano",
                    baseUrl = server.url("/v1").toString()
                )
            )

            assertEquals(
                mapOf("evt_1" to "Ik ben ⟦ASS_000⟧niet⟦ASS_001⟧ boos"),
                result.getOrThrow()
            )
            val body = server.takeRequest().body.readUtf8()
            assertTrue(body.contains("Translate subtitle text from the source language to the target language."))
            assertTrue(body.contains("⟦[A-Z_]+_[0-9]+⟧"))
            assertTrue(body.contains("I am ⟦ASS_000⟧not⟦ASS_001⟧ angry"))
        } finally {
            server.shutdown()
        }
    }

    private suspend fun assertProviderErrorDoesNotSplitAdaptiveChunks(
        statusCode: Int,
        expectedRequestCount: Int = 1
    ) {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return MockResponse()
                    .setResponseCode(statusCode)
                    .setHeader("Retry-After", "0")
                    .setBody("""{"error":{"message":"provider rejected request"}}""")
            }
        }
        server.start()
        try {
            val service = SubtitleTranslationService(
                context = mockk<Context>(relaxed = true),
                httpClient = OkHttpClient()
            )

            val result = service.translateCueTexts(
                texts = listOf("One", "Two", "Three", "Four"),
                targetLanguageCode = "nl",
                settings = SubtitleTranslationSettings(
                    provider = SubtitleTranslationProvider.OPENAI,
                    apiKey = "test-key",
                    model = "gpt-5-nano",
                    baseUrl = server.url("/v1").toString()
                ),
                chunkConfig = SubtitleTranslationChunkConfig(
                    maxEntries = 10,
                    maxChars = 10_000,
                    minSplitEntries = 1,
                    maxParallelRequests = 1
                )
            )

            assertTrue(result.isFailure)
            assertEquals(expectedRequestCount, server.requestCount)
        } finally {
            server.shutdown()
        }
    }
}
