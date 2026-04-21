package com.nexio.tv.data.repository

import com.nexio.tv.domain.model.SubtitleTranslationProvider
import com.nexio.tv.domain.model.SubtitleTranslationSettings
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleTranslationProviderRequestsTest {
    @Test
    fun openAiEndpointAppendsChatCompletionsToBaseUrl() {
        val endpoint = providerEndpoint(
            SubtitleTranslationSettings(
                provider = SubtitleTranslationProvider.OPENAI,
                model = "openai/gpt-5.2",
                baseUrl = "https://openrouter.ai/api/v1"
            )
        )

        assertEquals("https://openrouter.ai/api/v1/chat/completions", endpoint)
    }

    @Test
    fun anthropicEndpointAppendsMessagesToBaseUrl() {
        val endpoint = providerEndpoint(
            SubtitleTranslationSettings(
                provider = SubtitleTranslationProvider.ANTHROPIC,
                model = "claude-haiku-4-5",
                baseUrl = "https://anthropic-compatible.example/v1/"
            )
        )

        assertEquals("https://anthropic-compatible.example/v1/messages", endpoint)
    }

    @Test
    fun openAiEndpointAddsV1ForBareNativeHost() {
        val endpoint = providerEndpoint(
            SubtitleTranslationSettings(
                provider = SubtitleTranslationProvider.OPENAI,
                model = "gpt-5-nano",
                baseUrl = "https://api.openai.com"
            )
        )

        assertEquals("https://api.openai.com/v1/chat/completions", endpoint)
    }

    @Test
    fun geminiEndpointKeepsFullModelEndpoint() {
        val endpoint = providerEndpoint(
            SubtitleTranslationSettings(
                provider = SubtitleTranslationProvider.GEMINI,
                model = "gemini-2.5-flash",
                baseUrl = "https://my-proxy.com/v1beta/models/custom-model:generateContent"
            )
        )

        assertEquals("https://my-proxy.com/v1beta/models/custom-model:generateContent", endpoint)
    }

    @Test
    fun geminiEndpointAppendsGenerateContentToModelPath() {
        val endpoint = providerEndpoint(
            SubtitleTranslationSettings(
                provider = SubtitleTranslationProvider.GEMINI,
                model = "gemini-2.5-flash",
                baseUrl = "https://my-proxy.com/v1beta/models/custom-model"
            )
        )

        assertEquals("https://my-proxy.com/v1beta/models/custom-model:generateContent", endpoint)
    }

    @Test
    fun dashScopeEndpointAppendsTextGenerationPathToBaseUrl() {
        val endpoint = providerEndpoint(
            SubtitleTranslationSettings(
                provider = SubtitleTranslationProvider.DASHSCOPE,
                model = "qwen-mt-flash",
                baseUrl = "https://dashscope-intl.aliyuncs.com/api/v1/"
            )
        )

        assertEquals(
            "https://dashscope-intl.aliyuncs.com/api/v1/services/aigc/text-generation/generation",
            endpoint
        )
    }

    @Test
    fun openAiRequestUsesChatCompletionMessagesAndJsonObjectFormat() {
        val userPayloadJson = JSONObject()
        userPayloadJson.put("items", emptyList<String>())
        val body = buildOpenAiChatCompletionRequest(
            settings = SubtitleTranslationSettings(
                provider = SubtitleTranslationProvider.OPENAI,
                model = "gpt-5-nano"
            ),
            systemPrompt = "system",
            userPayload = userPayloadJson.toString()
        )

        assertEquals("gpt-5-nano", body.getString("model"))
        assertEquals("json_object", body.getJSONObject("response_format").getString("type"))
        assertEquals("system", body.getJSONArray("messages").getJSONObject(0).getString("content"))
    }

    @Test
    fun openAiRequestUsesStrictJsonSchemaWhenItemCountAndSupportedModel() {
        val body = buildOpenAiChatCompletionRequest(
            settings = SubtitleTranslationSettings(
                provider = SubtitleTranslationProvider.OPENAI,
                model = "gpt-4o-mini"
            ),
            systemPrompt = "system",
            userPayload = """{"items":[{"id":1,"text":"hi"}]}""",
            strictJsonSchemaItemCount = 60
        )

        val responseFormat = body.getJSONObject("response_format")
        assertEquals("json_schema", responseFormat.getString("type"))
        val schemaWrapper = responseFormat.getJSONObject("json_schema")
        assertTrue(schemaWrapper.getBoolean("strict"))
        val schema = schemaWrapper.getJSONObject("schema")
        val itemsSchema = schema.getJSONObject("properties").getJSONObject("items")
        assertEquals(60, itemsSchema.getInt("minItems"))
        assertEquals(60, itemsSchema.getInt("maxItems"))
        val itemSchema = itemsSchema.getJSONObject("items")
        assertFalse(itemSchema.getBoolean("additionalProperties"))
        val required = itemSchema.getJSONArray("required")
        assertEquals(2, required.length())
    }

    @Test
    fun openAiRequestFallsBackToJsonObjectOnUnsupportedModel() {
        val body = buildOpenAiChatCompletionRequest(
            settings = SubtitleTranslationSettings(
                provider = SubtitleTranslationProvider.OPENAI,
                model = "gpt-3.5-turbo"
            ),
            systemPrompt = "system",
            userPayload = """{"items":[]}""",
            strictJsonSchemaItemCount = 10
        )

        assertEquals("json_object", body.getJSONObject("response_format").getString("type"))
    }

    @Test
    fun openAiRequestAcceptsOpenRouterPrefixedModelIds() {
        val body = buildOpenAiChatCompletionRequest(
            settings = SubtitleTranslationSettings(
                provider = SubtitleTranslationProvider.OPENAI,
                model = "openai/gpt-4o",
                baseUrl = "https://openrouter.ai/api/v1"
            ),
            systemPrompt = "system",
            userPayload = """{"items":[]}""",
            strictJsonSchemaItemCount = 40
        )

        assertEquals("json_schema", body.getJSONObject("response_format").getString("type"))
    }

    @Test
    fun anthropicRequestUsesMessagesApiShape() {
        val body = buildAnthropicMessagesRequest(
            settings = SubtitleTranslationSettings(
                provider = SubtitleTranslationProvider.ANTHROPIC,
                model = "claude-haiku-4-5"
            ),
            systemPrompt = "system",
            userPayload = """{"items":[]}"""
        )

        assertEquals("claude-haiku-4-5", body.getString("model"))
        assertEquals("system", body.getString("system"))
        assertTrue(body.getInt("max_tokens") > 0)
        assertEquals("user", body.getJSONArray("messages").getJSONObject(0).getString("role"))
    }

    @Test
    fun dashScopeRequestUsesQwenMtTranslationOptions() {
        val body = buildDashScopeGenerationRequest(
            settings = SubtitleTranslationSettings(
                provider = SubtitleTranslationProvider.DASHSCOPE,
                model = "qwen-mt-flash"
            ),
            markerPayload = "[[0]] Czesc",
            sourceLanguage = "Polish",
            targetLanguage = "Dutch"
        )

        assertEquals("qwen-mt-flash", body.getString("model"))
        val message = body.getJSONObject("input")
            .getJSONArray("messages")
            .getJSONObject(0)
        assertEquals("user", message.getString("role"))
        assertEquals("[[0]] Czesc", message.getString("content"))
        val options = body.getJSONObject("parameters").getJSONObject("translation_options")
        assertEquals("Polish", options.getString("source_lang"))
        assertEquals("Dutch", options.getString("target_lang"))
    }

    @Test
    fun parseDashScopeResponseTextReadsChoiceMessageContent() {
        val parsed = parseDashScopeResponseText(
            """
            {
              "output": {
                "choices": [
                  {
                    "message": {
                      "content": "[[0]] Hallo"
                    }
                  }
                ]
              }
            }
            """.trimIndent()
        )

        assertEquals("[[0]] Hallo", parsed)
    }

    @Test
    fun sanitizeJsonResponseStripsMarkdownFence() {
        val sanitized = sanitizeJsonResponse(
            """
            ```json
            {"items":[{"id":1,"text":"Hallo"}]}
            ```
            """.trimIndent()
        )

        assertEquals("""{"items":[{"id":1,"text":"Hallo"}]}""", sanitized)
    }
}
