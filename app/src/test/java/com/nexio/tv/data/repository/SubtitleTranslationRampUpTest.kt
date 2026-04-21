package com.nexio.tv.data.repository

import android.content.Context
import com.nexio.tv.domain.model.SubtitleTranslationProvider
import com.nexio.tv.domain.model.SubtitleTranslationSettings
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleTranslationRampUpTest {

    private fun openAiChatResponse(items: List<Pair<Int, String>>): MockResponse {
        val jsonItems = JSONArray().apply {
            items.forEach { (id, text) ->
                put(JSONObject().put("id", id).put("text", text))
            }
        }
        val content = JSONObject().put("items", jsonItems).toString()
        val body = JSONObject()
            .put(
                "choices",
                JSONArray().put(
                    JSONObject().put(
                        "message",
                        JSONObject().put("content", content)
                    )
                )
            )
            .toString()
        return MockResponse().setResponseCode(200).setBody(body)
    }

    private fun sourceTexts(n: Int): List<String> = (0 until n).map { "src_$it" }
    private fun expectedTranslation(index: Int) = "tr_$index"

    @Test
    fun rampUpSchedulingProducesSmallFirstBatch() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            val texts = sourceTexts(15) // ramp-up schedule [5,10,...] → 2 core batches
            // Batch 1 items: [0..4] core (size 5 → overlap 1) + [5] trail overlap = 6 items
            // Batch 2 items: [3,4] lead overlap (size 10 → overlap 2) + [5..14] core = 12 items
            server.enqueue(openAiChatResponse((0..5).map { it to expectedTranslation(it) }))
            server.enqueue(openAiChatResponse((3..14).map { it to expectedTranslation(it) }))

            val service = SubtitleTranslationService(
                context = mockk<Context>(relaxed = true),
                httpClient = OkHttpClient()
            )

            val result = service.translateCueTexts(
                texts = texts,
                targetLanguageCode = "nl",
                settings = SubtitleTranslationSettings(
                    provider = SubtitleTranslationProvider.OPENAI,
                    apiKey = "test-key",
                    model = "gpt-5-nano",
                    baseUrl = server.url("/v1").toString()
                ),
                chunkConfig = SubtitleTranslationService.DEFAULT_CUE_CHUNK_CONFIG
                    .copy(maxParallelRequests = 1)
            ).getOrThrow()

            // All 15 texts translated
            assertEquals(15, result.size)
            texts.forEachIndexed { index, source ->
                assertEquals(expectedTranslation(index), result[source])
            }
            assertEquals(2, server.requestCount)

            // Verify the first request carried only 6 items (5 core + 1 overlap) — the ramp-up effect
            val firstBody = server.takeRequest().body.readUtf8()
            val firstIds = extractRequestedIds(firstBody)
            assertEquals(listOf(0, 1, 2, 3, 4, 5), firstIds)

            val secondBody = server.takeRequest().body.readUtf8()
            val secondIds = extractRequestedIds(secondBody)
            assertEquals((3..14).toList(), secondIds)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun overlapBatchFillsMissingCoreTranslation() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            val texts = sourceTexts(15)
            // Batch 1 response: all 6 items translated (including id 5 as trail overlap)
            server.enqueue(openAiChatResponse((0..5).map { it to expectedTranslation(it) }))
            // Batch 2 response: MISSING id 5 (which is this batch's first core cue)
            // Batch 2 items were [4, 5, 6, 7, ..., 14] — returns id 4 and ids 6..14 but not id 5
            server.enqueue(
                openAiChatResponse(
                    (listOf(4) + (6..14)).map { it to expectedTranslation(it) }
                )
            )

            val service = SubtitleTranslationService(
                context = mockk<Context>(relaxed = true),
                httpClient = OkHttpClient()
            )

            val result = service.translateCueTexts(
                texts = texts,
                targetLanguageCode = "nl",
                settings = SubtitleTranslationSettings(
                    provider = SubtitleTranslationProvider.OPENAI,
                    apiKey = "test-key",
                    model = "gpt-5-nano",
                    baseUrl = server.url("/v1").toString()
                ),
                chunkConfig = SubtitleTranslationService.DEFAULT_CUE_CHUNK_CONFIG
                    .copy(maxParallelRequests = 1)
            ).getOrThrow()

            // id 5 comes from batch 1's trail-overlap translation — no retry needed
            assertEquals(15, result.size)
            assertEquals(expectedTranslation(5), result["src_5"])
            assertEquals(2, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun missingCoreIdTriggersRetryPass() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            val texts = sourceTexts(15)
            // Batch 1 response: covers 0..4 (core) + 5 (overlap) — all returned
            server.enqueue(openAiChatResponse((0..5).map { it to expectedTranslation(it) }))
            // Batch 2 response: items were [4, 5..14]. MISSING id 10 — which is neither
            // core of batch 1 nor overlap of batch 1 (id 10 only appears in batch 2).
            server.enqueue(
                openAiChatResponse(
                    (listOf(4) + (5..9) + (11..14)).map { it to expectedTranslation(it) }
                )
            )
            // Retry response: just id 10
            server.enqueue(openAiChatResponse(listOf(10 to expectedTranslation(10))))

            val service = SubtitleTranslationService(
                context = mockk<Context>(relaxed = true),
                httpClient = OkHttpClient()
            )

            val result = service.translateCueTexts(
                texts = texts,
                targetLanguageCode = "nl",
                settings = SubtitleTranslationSettings(
                    provider = SubtitleTranslationProvider.OPENAI,
                    apiKey = "test-key",
                    model = "gpt-5-nano",
                    baseUrl = server.url("/v1").toString()
                ),
                chunkConfig = SubtitleTranslationService.DEFAULT_CUE_CHUNK_CONFIG
                    .copy(maxParallelRequests = 1)
            ).getOrThrow()

            assertEquals(15, result.size)
            assertEquals(expectedTranslation(10), result["src_10"])
            assertEquals(3, server.requestCount)

            // The 3rd request should be a small retry containing only id 10
            repeat(2) { server.takeRequest() }
            val retryBody = server.takeRequest().body.readUtf8()
            val retryIds = extractRequestedIds(retryBody)
            assertEquals(listOf(10), retryIds)
        } finally {
            server.shutdown()
        }
    }

    private fun extractRequestedIds(requestBody: String): List<Int> {
        // OpenAI chat-completions payload embeds our prompt JSON inside
        // messages[1].content as a JSON string (which contains the items array).
        // Parse the outer, unwrap user content, parse the inner, then collect ids.
        val outer = JSONObject(requestBody)
        val messages = outer.getJSONArray("messages")
        var userPayloadContent: String? = null
        for (i in 0 until messages.length()) {
            val msg = messages.getJSONObject(i)
            if (msg.optString("role") == "user") {
                userPayloadContent = msg.optString("content")
                break
            }
        }
        val content = userPayloadContent ?: error("user message not found in request body")
        val inner = JSONObject(content)
        val items = inner.getJSONArray("items")
        return (0 until items.length()).map { items.getJSONObject(it).getInt("id") }
    }
}
