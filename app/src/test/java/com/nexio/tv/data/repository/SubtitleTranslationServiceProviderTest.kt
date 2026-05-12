package com.nexio.tv.data.repository

import android.content.Context
import com.nexio.tv.core.integration.IntegrationCallResult
import com.nexio.tv.core.integration.IntegrationCallSpec
import com.nexio.tv.core.integration.IntegrationFetchOptions
import com.nexio.tv.core.integration.IntegrationFetchResult
import com.nexio.tv.core.integration.IntegrationRuntime
import com.nexio.tv.core.integration.IntegrationStreamSpec
import com.nexio.tv.data.integration.subtitles.SubtitleSourceDownloadIntegrationProvider
import com.nexio.tv.data.integration.subtitles.SubtitleTranslationIntegrationProvider
import com.nexio.tv.data.integration.subtitles.transport.SubtitleSourceDownloadTransport
import com.nexio.tv.data.integration.subtitles.transport.SubtitleTranslationTransport
import com.nexio.tv.domain.model.SubtitleTranslationProvider
import com.nexio.tv.domain.model.SubtitleTranslationSettings
import com.nexio.tv.domain.model.Subtitle
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

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
    fun defaultChunkConfigsFavorLargeParallelProviderRequests() {
        assertEquals(2_000, SubtitleTranslationService.DEFAULT_CUE_CHUNK_CONFIG.maxEntries)
        assertEquals(100_000, SubtitleTranslationService.DEFAULT_CUE_CHUNK_CONFIG.maxChars)
        assertEquals(3, SubtitleTranslationService.DEFAULT_CUE_CHUNK_CONFIG.maxParallelRequests)
        assertEquals(2_000, SubtitleTranslationService.ADDON_OVERLAY_CUE_CHUNK_CONFIG.maxEntries)
        assertEquals(100_000, SubtitleTranslationService.ADDON_OVERLAY_CUE_CHUNK_CONFIG.maxChars)
        assertEquals(3, SubtitleTranslationService.ADDON_OVERLAY_CUE_CHUNK_CONFIG.maxParallelRequests)
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
                subtitleTranslationIntegrationProvider = subtitleTranslationIntegrationProvider(OkHttpClient()),
                subtitleSourceDownloadIntegrationProvider = subtitleSourceDownloadIntegrationProvider(OkHttpClient())
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
                subtitleTranslationIntegrationProvider = subtitleTranslationIntegrationProvider(OkHttpClient()),
                subtitleSourceDownloadIntegrationProvider = subtitleSourceDownloadIntegrationProvider(OkHttpClient())
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
                subtitleTranslationIntegrationProvider = subtitleTranslationIntegrationProvider(OkHttpClient()),
                subtitleSourceDownloadIntegrationProvider = subtitleSourceDownloadIntegrationProvider(OkHttpClient())
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
    fun bareSingleItemObjectResponseIsAcceptedAsSingleCueTranslation() = runTest {
        // Regression: when a single cue is sent, some OpenAI-compatible providers
        // (observed with deepseek/deepseek-v4-flash on OpenRouter) return the JSON
        // content as a bare object {"id":0,"text":"…"} instead of [...] or
        // {"items":[...]}. The parser previously threw "empty translation payload",
        // which the BuiltInSubtitleCueTranslator caches as a 60-second cooldown
        // and surfaces as a flood of TextRenderer "Cue group translation failed"
        // warnings during playback.
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
                            "content": "{\"id\":0,\"text\":\"Hallo\"}"
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
                subtitleTranslationIntegrationProvider = subtitleTranslationIntegrationProvider(OkHttpClient()),
                subtitleSourceDownloadIntegrationProvider = subtitleSourceDownloadIntegrationProvider(OkHttpClient())
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
                subtitleTranslationIntegrationProvider = subtitleTranslationIntegrationProvider(OkHttpClient()),
                subtitleSourceDownloadIntegrationProvider = subtitleSourceDownloadIntegrationProvider(OkHttpClient())
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
                subtitleTranslationIntegrationProvider = subtitleTranslationIntegrationProvider(OkHttpClient()),
                subtitleSourceDownloadIntegrationProvider = subtitleSourceDownloadIntegrationProvider(OkHttpClient())
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
                "Expected terminal failure to stop after retrying active parallel chunks (bounded by " +
                    "parallelism*retries, not full queue drain), but saw ${server.requestCount} requests",
                server.requestCount <= 12
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
                subtitleTranslationIntegrationProvider = subtitleTranslationIntegrationProvider(OkHttpClient()),
                subtitleSourceDownloadIntegrationProvider = subtitleSourceDownloadIntegrationProvider(OkHttpClient())
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

    @Test
    fun assSsaSegmentTranslationSendsSegmentArraysAndRecomposesValidItems() = runTest {
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
                            "content": "{\"items\":[{\"id\":\"ass_0\",\"segments\":[\"Hallo\",\"wereld\"]}]}"
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
                subtitleTranslationIntegrationProvider = subtitleTranslationIntegrationProvider(OkHttpClient()),
                subtitleSourceDownloadIntegrationProvider = subtitleSourceDownloadIntegrationProvider(OkHttpClient())
            )
            val surface = (AssSsaSegmentSurfaceParser.parse("ass_0", "Hello {\\i1}world{\\i0}") as AssSsaSurfaceParseResult.Translatable).surface

            val result = service.translateAssSsaSegmentSurfaces(
                surfaces = listOf(surface),
                targetLanguageCode = "nl",
                sourceLanguageCode = "en",
                settings = SubtitleTranslationSettings(
                    provider = SubtitleTranslationProvider.OPENAI,
                    apiKey = "test-key",
                    model = "gpt-5-nano",
                    baseUrl = server.url("/v1").toString()
                )
            ).getOrThrow()

            assertEquals(mapOf("ass_0" to listOf("Hallo", "wereld")), result)
            val requestBody = server.takeRequest().body.readUtf8()
            assertTrue(
                requestBody.contains(""""segments":["Hello","world"]""") ||
                    requestBody.contains("""\"segments\":[\"Hello\",\"world\"]""")
            )
            assertTrue(
                requestBody.contains(""""context":"Hello world"""") ||
                    requestBody.contains("""\"context\":\"Hello world\"""")
            )
            assertFalse(requestBody.contains("⟦ASS_"))
            assertFalse(requestBody.contains("{\\i1}"))
            val itemProperties = JSONObject(requestBody)
                .getJSONObject("response_format")
                .getJSONObject("json_schema")
                .getJSONObject("schema")
                .getJSONObject("properties")
                .getJSONObject("items")
                .getJSONObject("items")
                .getJSONObject("properties")
            assertEquals("string", itemProperties.getJSONObject("id").getString("type"))
            val segmentsSchema = itemProperties.getJSONObject("segments")
            assertEquals("array", segmentsSchema.getString("type"))
            assertEquals("string", segmentsSchema.getJSONObject("items").getString("type"))
            assertFalse(itemProperties.has("text"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun assSsaSegmentTranslationSendsGeminiSegmentResponseSchema() = runTest {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "candidates": [
                        {
                          "content": {
                            "parts": [
                              {
                                "text": "{\"items\":[{\"id\":\"ass_0\",\"segments\":[\"Hallo\"]}]}"
                              }
                            ]
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
                subtitleTranslationIntegrationProvider = subtitleTranslationIntegrationProvider(OkHttpClient()),
                subtitleSourceDownloadIntegrationProvider = subtitleSourceDownloadIntegrationProvider(OkHttpClient())
            )
            val surface = (AssSsaSegmentSurfaceParser.parse("ass_0", "Hello") as AssSsaSurfaceParseResult.Translatable).surface

            val result = service.translateAssSsaSegmentSurfaces(
                surfaces = listOf(surface),
                targetLanguageCode = "nl",
                sourceLanguageCode = "en",
                settings = SubtitleTranslationSettings(
                    provider = SubtitleTranslationProvider.GEMINI,
                    apiKey = "test-key",
                    model = "gemini-2.5-flash",
                    baseUrl = server.url("/v1beta").toString()
                )
            ).getOrThrow()

            assertEquals(mapOf("ass_0" to listOf("Hallo")), result)
            val itemProperties = JSONObject(server.takeRequest().body.readUtf8())
                .getJSONObject("generationConfig")
                .getJSONObject("responseSchema")
                .getJSONObject("properties")
                .getJSONObject("items")
                .getJSONObject("items")
                .getJSONObject("properties")
            assertEquals("string", itemProperties.getJSONObject("id").getString("type"))
            val segmentsSchema = itemProperties.getJSONObject("segments")
            assertEquals("array", segmentsSchema.getString("type"))
            assertEquals("string", segmentsSchema.getJSONObject("items").getString("type"))
            assertFalse(itemProperties.has("text"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun assSsaSegmentTranslationFallsBackWhenOpenAiSegmentSchemaIsRejected() = runTest {
        val server = MockWebServer()
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
                            "content": "{\"items\":[{\"id\":\"ass_0\",\"segments\":[\"Hallo\"]}]}"
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
                subtitleTranslationIntegrationProvider = subtitleTranslationIntegrationProvider(OkHttpClient()),
                subtitleSourceDownloadIntegrationProvider = subtitleSourceDownloadIntegrationProvider(OkHttpClient())
            )
            val surface = (AssSsaSegmentSurfaceParser.parse("ass_0", "Hello") as AssSsaSurfaceParseResult.Translatable).surface

            val result = service.translateAssSsaSegmentSurfaces(
                surfaces = listOf(surface),
                targetLanguageCode = "nl",
                sourceLanguageCode = "en",
                settings = SubtitleTranslationSettings(
                    provider = SubtitleTranslationProvider.OPENAI,
                    apiKey = "test-key",
                    model = "gpt-5-nano",
                    baseUrl = server.url("/v1").toString()
                )
            ).getOrThrow()

            assertEquals(mapOf("ass_0" to listOf("Hallo")), result)
            assertEquals(2, server.requestCount)
            val structuredRequestBody = server.takeRequest().body.readUtf8()
            val fallbackRequestBody = server.takeRequest().body.readUtf8()
            assertTrue(structuredRequestBody.contains("response_format"))
            assertFalse(fallbackRequestBody.contains("response_format"))
            assertTrue(fallbackRequestBody.contains("ass_0"))
            assertTrue(fallbackRequestBody.contains("segments"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun assSsaSegmentTranslationUsesDashScopeMarkerPayloadForSegments() = runTest {
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
                              "content": "[[0]] Hallo\\n[[1]] wereld"
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
                subtitleTranslationIntegrationProvider = subtitleTranslationIntegrationProvider(OkHttpClient()),
                subtitleSourceDownloadIntegrationProvider = subtitleSourceDownloadIntegrationProvider(OkHttpClient())
            )
            val surface = (AssSsaSegmentSurfaceParser.parse("ass_0", "Hello {\\i1}world{\\i0}") as AssSsaSurfaceParseResult.Translatable).surface

            val result = service.translateAssSsaSegmentSurfaces(
                surfaces = listOf(surface),
                targetLanguageCode = "nl",
                sourceLanguageCode = "en",
                settings = SubtitleTranslationSettings(
                    provider = SubtitleTranslationProvider.DASHSCOPE,
                    apiKey = "dashscope-key",
                    model = "qwen-mt-flash",
                    baseUrl = server.url("/api/v1").toString()
                )
            ).getOrThrow()

            assertEquals(mapOf("ass_0" to listOf("Hallo", "wereld")), result)
            val body = server.takeRequest().body.readUtf8()
            assertTrue(body.contains(""""source_lang":"English""""))
            assertTrue(body.contains(""""target_lang":"Dutch""""))
            assertTrue(body.contains("[[0]] Hello"))
            assertTrue(body.contains("[[1]] world"))
            assertFalse(body.contains("{\\i1}"))
            assertFalse(body.contains("response_format"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun assSsaSegmentTranslationDropsInvalidItemButKeepsValidItem() = runTest {
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
                            "content": "{\"items\":[{\"id\":\"ass_0\",\"segments\":[\"Hallo\"]},{\"id\":\"ass_1\",\"segments\":[\"{\\\\i1}Wereld\"]}]}"
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
                subtitleTranslationIntegrationProvider = subtitleTranslationIntegrationProvider(OkHttpClient()),
                subtitleSourceDownloadIntegrationProvider = subtitleSourceDownloadIntegrationProvider(OkHttpClient())
            )
            val surfaces = listOf(
                (AssSsaSegmentSurfaceParser.parse("ass_0", "Hello") as AssSsaSurfaceParseResult.Translatable).surface,
                (AssSsaSegmentSurfaceParser.parse("ass_1", "World") as AssSsaSurfaceParseResult.Translatable).surface
            )

            val result = service.translateAssSsaSegmentSurfaces(
                surfaces = surfaces,
                targetLanguageCode = "nl",
                sourceLanguageCode = "en",
                settings = SubtitleTranslationSettings(
                    provider = SubtitleTranslationProvider.OPENAI,
                    apiKey = "test-key",
                    model = "gpt-5-nano",
                    baseUrl = server.url("/v1").toString()
                )
            ).getOrThrow()

            assertEquals(mapOf("ass_0" to listOf("Hallo")), result)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun subRipSystemPromptModeSendsRawSrtBatchAndWritesTranslatedSrt() = runTest {
        val server = MockWebServer()
        val sourceSrt = """
            1
            00:00:01,000 --> 00:00:02,000
            <i>Hello there.</i>

            2
            00:00:02,500 --> 00:00:03,500
            - Speaker 1: Come on.
            - Speaker 2: Watch it.
        """.trimIndent()
        val translatedSrt = """
            1
            00:00:01,000 --> 00:00:02,000
            <i>Hallo daar.</i>

            2
            00:00:02,500 --> 00:00:03,500
            - Speaker 1: Kom op.
            - Speaker 2: Kijk uit.
        """.trimIndent()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return if (request.path == "/subtitle.srt") {
                    MockResponse()
                        .setResponseCode(200)
                        .setBody(sourceSrt)
                } else {
                    MockResponse()
                        .setResponseCode(200)
                        .setBody(
                            """
                            {
                              "choices": [
                                {
                                  "message": {
                                    "content": ${JSONObject.quote(translatedSrt)}
                                  }
                                }
                              ]
                            }
                            """.trimIndent()
                        )
                }
            }
        }
        server.start()
        val cacheDir = createTempDirectory("subrip-system-prompt-test").toFile()
        try {
            val context = mockk<Context>()
            every { context.cacheDir } returns cacheDir
            val service = SubtitleTranslationService(
                context = context,
                subtitleTranslationIntegrationProvider = subtitleTranslationIntegrationProvider(OkHttpClient()),
                subtitleSourceDownloadIntegrationProvider = subtitleSourceDownloadIntegrationProvider(OkHttpClient())
            )

            val result = service.translateSubtitle(
                sourceSubtitle = Subtitle(
                    id = "sub-1",
                    url = server.url("/subtitle.srt").toString(),
                    lang = "en",
                    addonName = "Addon",
                    addonLogo = null
                ),
                targetLanguageCode = "nl",
                sourceLanguageCode = "en",
                settings = SubtitleTranslationSettings(
                    provider = SubtitleTranslationProvider.OPENAI,
                    apiKey = "test-key",
                    model = "deepseek/deepseek-v3.2-exp:nitro",
                    baseUrl = server.url("/v1").toString(),
                    subRipSystemPromptEnabled = true
                )
            ).getOrThrow()

            val translatedFile = File(result.translatedSubtitle.url.removePrefix("file:"))
            assertEquals(translatedSrt + "\n", translatedFile.readText())

            val translationRequest = server.takeRequest()
            assertEquals("/subtitle.srt", translationRequest.path)
            val providerRequest = server.takeRequest()
            val body = providerRequest.body.readUtf8()
            assertTrue(body.contains("SRT_TRANSLATION_ENGINE"))
            assertTrue(body.contains("TARGET LANGUAGE: Dutch"))
            assertTrue(body.contains("00:00:01,000 --> 00:00:02,000"))
            assertTrue(body.contains("Hello there."))
            assertTrue(body.contains("- Speaker 1: Come on."))
            assertFalse(body.contains(""""items""""))
            assertFalse(body.contains("response_format"))
        } finally {
            server.shutdown()
            cacheDir.deleteRecursively()
        }
    }

    @Test
    fun assSsaFileTranslationSendsSegmentSurfacesAndWritesTranslatedAss() = runTest {
        val server = MockWebServer()
        val sourceAss = """
            [Script Info]
            ScriptType: v4.00+

            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 0,0:00:01.00,0:00:02.00,Default,SIGN,0,0,0,,{\pos(475.43,40)}Sign text
            Dialogue: 0,0:00:03.00,0:00:04.00,Default,,0,0,0,,Hello there.
        """.trimIndent()
        val translatedAss = """
            [Script Info]
            ScriptType: v4.00+

            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 0,0:00:01.00,0:00:02.00,Default,SIGN,0,0,0,,{\pos(475.43,40)}Bordtekst
            Dialogue: 0,0:00:03.00,0:00:04.00,Default,,0,0,0,,Hallo daar.
        """.trimIndent()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return if (request.path == "/subtitle.ass") {
                    MockResponse()
                        .setResponseCode(200)
                        .setBody(sourceAss)
                } else {
                    MockResponse()
                        .setResponseCode(200)
                        .setBody(
                            """
                            {
                              "choices": [
                                {
                                  "message": {
                                    "content": "{\"items\":[{\"id\":\"ass_0\",\"segments\":[\"Bordtekst\"]},{\"id\":\"ass_1\",\"segments\":[\"Hallo daar.\"]}]}"
                                  }
                                }
                              ]
                            }
                            """.trimIndent()
                        )
                }
            }
        }
        server.start()
        val cacheDir = createTempDirectory("ass-system-prompt-test").toFile()
        try {
            val context = mockk<Context>()
            every { context.cacheDir } returns cacheDir
            val service = SubtitleTranslationService(
                context = context,
                subtitleTranslationIntegrationProvider = subtitleTranslationIntegrationProvider(OkHttpClient()),
                subtitleSourceDownloadIntegrationProvider = subtitleSourceDownloadIntegrationProvider(OkHttpClient())
            )

            val result = service.translateSubtitle(
                sourceSubtitle = Subtitle(
                    id = "ass-1",
                    url = server.url("/subtitle.ass").toString(),
                    lang = "en",
                    addonName = "Addon",
                    addonLogo = null
                ),
                targetLanguageCode = "nl",
                sourceLanguageCode = "en",
                settings = SubtitleTranslationSettings(
                    provider = SubtitleTranslationProvider.OPENAI,
                    apiKey = "test-key",
                    model = "gpt-5-nano",
                    baseUrl = server.url("/v1").toString(),
                    assSsaSystemPromptEnabled = true
                )
            ).getOrThrow()

            val translatedFile = File(result.translatedSubtitle.url.removePrefix("file:"))
            assertEquals(translatedAss + "\n", translatedFile.readText())

            val downloadRequest = server.takeRequest()
            assertEquals("/subtitle.ass", downloadRequest.path)
            val providerRequest = server.takeRequest()
            val body = providerRequest.body.readUtf8()
            assertTrue(body.contains("Translate subtitle segments to the target language."))
            assertTrue(body.contains(""""items""""))
            assertTrue(body.contains(""""segments":["Sign text"]""") || body.contains("""\"segments\":[\"Sign text\"]"""))
            assertTrue(body.contains("Hello there."))
            assertTrue(body.contains("response_format"))
            assertFalse(body.contains("ASS_SSA_SUBTITLE_TRANSLATOR"))
            assertFalse(body.contains("""{\\pos(475.43,40)}Sign text"""))
        } finally {
            server.shutdown()
            cacheDir.deleteRecursively()
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
                subtitleTranslationIntegrationProvider = subtitleTranslationIntegrationProvider(OkHttpClient()),
                subtitleSourceDownloadIntegrationProvider = subtitleSourceDownloadIntegrationProvider(OkHttpClient())
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

    private fun subtitleTranslationIntegrationProvider(httpClient: OkHttpClient): SubtitleTranslationIntegrationProvider {
        val runtime = object : IntegrationRuntime {
            override suspend fun <T> get(
                spec: com.nexio.tv.core.integration.IntegrationSpec<T>,
                options: IntegrationFetchOptions
            ): IntegrationFetchResult<T> = IntegrationFetchResult.Missing

            override suspend fun <T> call(spec: IntegrationCallSpec<T>): IntegrationCallResult<T> =
                spec.call()

            override suspend fun <T> open(spec: IntegrationStreamSpec<T>) = null
        }

        return SubtitleTranslationIntegrationProvider(runtime, SubtitleTranslationTransport(httpClient))
    }

    private fun subtitleSourceDownloadIntegrationProvider(
        httpClient: OkHttpClient
    ): SubtitleSourceDownloadIntegrationProvider {
        val runtime = object : IntegrationRuntime {
            override suspend fun <T> get(
                spec: com.nexio.tv.core.integration.IntegrationSpec<T>,
                options: IntegrationFetchOptions
            ): IntegrationFetchResult<T> = IntegrationFetchResult.Missing

            override suspend fun <T> call(spec: IntegrationCallSpec<T>): IntegrationCallResult<T> =
                spec.call()

            override suspend fun <T> open(spec: IntegrationStreamSpec<T>) = null
        }

        return SubtitleSourceDownloadIntegrationProvider(
            runtime,
            SubtitleSourceDownloadTransport(httpClient)
        )
    }
}
