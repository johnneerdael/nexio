package com.nexio.tv.data.repository

import com.nexio.tv.domain.model.SubtitleTranslationDefaults
import com.nexio.tv.domain.model.SubtitleTranslationProvider
import com.nexio.tv.domain.model.SubtitleTranslationSettings
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

private const val ANTHROPIC_VERSION = "2023-06-01"
private const val OPENAI_NATIVE_BASE_URL = "https://api.openai.com/v1"
private const val DASHSCOPE_GENERATION_PATH = "/services/aigc/text-generation/generation"

internal fun providerEndpoint(settings: SubtitleTranslationSettings): String {
    val rawRoot = settings.baseUrl.trim().trimEnd('/').ifBlank {
        when (settings.provider) {
            SubtitleTranslationProvider.OPENAI -> SubtitleTranslationDefaults.OPENAI_BASE_URL
            SubtitleTranslationProvider.ANTHROPIC -> SubtitleTranslationDefaults.ANTHROPIC_BASE_URL
            SubtitleTranslationProvider.GEMINI -> SubtitleTranslationDefaults.GEMINI_BASE_URL
            SubtitleTranslationProvider.DASHSCOPE -> SubtitleTranslationDefaults.DASHSCOPE_BASE_URL
        }
    }
    return when (settings.provider) {
        SubtitleTranslationProvider.OPENAI -> {
            val root = if (rawRoot == "https://api.openai.com") OPENAI_NATIVE_BASE_URL else rawRoot
            if (root.endsWith("/chat/completions")) root else "$root/chat/completions"
        }
        SubtitleTranslationProvider.ANTHROPIC -> {
            val root = if (rawRoot == "https://api.anthropic.com") SubtitleTranslationDefaults.ANTHROPIC_BASE_URL else rawRoot
            if (root.endsWith("/messages")) root else "$root/messages"
        }
        SubtitleTranslationProvider.GEMINI ->
            if (rawRoot.contains("/models/") || rawRoot.endsWith(":generateContent")) {
                if (rawRoot.endsWith(":generateContent")) rawRoot else "$rawRoot:generateContent"
            } else {
                "$rawRoot/models/${settings.model}:generateContent"
            }
        SubtitleTranslationProvider.DASHSCOPE ->
            if (rawRoot.endsWith(DASHSCOPE_GENERATION_PATH)) rawRoot else "$rawRoot$DASHSCOPE_GENERATION_PATH"
    }
}

internal fun buildOpenAiChatCompletionRequest(
    settings: SubtitleTranslationSettings,
    systemPrompt: String,
    userPayload: String,
    includeJsonMode: Boolean = true,
    strictJsonSchemaItemCount: Int? = null
): JSONObject {
    val systemMessage = JSONObject()
    systemMessage.put("role", "system")
    systemMessage.put("content", systemPrompt)

    val userMessage = JSONObject()
    userMessage.put("role", "user")
    userMessage.put("content", userPayload)

    val messages = JSONArray()
    messages.put(systemMessage)
    messages.put(userMessage)

    val body = JSONObject()
    body.put("model", settings.model)
    body.put("temperature", 0.2)
    body.put("messages", messages)

    if (includeJsonMode) {
        val useStrictSchema = strictJsonSchemaItemCount != null &&
            supportsStrictJsonSchemaResponseFormat(settings)
        val responseFormat = if (useStrictSchema) {
            buildOpenAiStrictJsonSchemaResponseFormat(strictJsonSchemaItemCount!!)
        } else {
            JSONObject().put("type", "json_object")
        }
        body.put("response_format", responseFormat)
    }

    return body
}

internal fun supportsStrictJsonSchemaResponseFormat(
    settings: SubtitleTranslationSettings
): Boolean {
    val isOpenAiProvider = settings.provider == SubtitleTranslationProvider.OPENAI
    if (!isOpenAiProvider) return false
    val normalized = settings.model.trim().lowercase().removePrefix("openai/")
    return normalized.startsWith("gpt-4o") ||
        normalized.startsWith("gpt-4.1") ||
        normalized.startsWith("gpt-5") ||
        normalized.startsWith("chatgpt-4o") ||
        normalized.startsWith("o1") ||
        normalized.startsWith("o3") ||
        normalized.startsWith("o4")
}

private fun buildOpenAiStrictJsonSchemaResponseFormat(itemCount: Int): JSONObject {
    val idSchema = JSONObject().put("type", "integer")
    val textSchema = JSONObject().put("type", "string")

    val itemProperties = JSONObject()
        .put("id", idSchema)
        .put("text", textSchema)

    val itemSchema = JSONObject()
        .put("type", "object")
        .put("additionalProperties", false)
        .put("required", JSONArray().put("id").put("text"))
        .put("properties", itemProperties)

    val arraySchema = JSONObject()
        .put("type", "array")
        .put("items", itemSchema)
        .put("minItems", itemCount)
        .put("maxItems", itemCount)

    val rootSchema = JSONObject()
        .put("type", "object")
        .put("additionalProperties", false)
        .put("required", JSONArray().put("items"))
        .put("properties", JSONObject().put("items", arraySchema))

    val schemaWrapper = JSONObject()
        .put("name", "subtitle_translation")
        .put("strict", true)
        .put("schema", rootSchema)

    return JSONObject()
        .put("type", "json_schema")
        .put("json_schema", schemaWrapper)
}

internal fun buildAnthropicMessagesRequest(
    settings: SubtitleTranslationSettings,
    systemPrompt: String,
    userPayload: String
): JSONObject {
    val contentItem = JSONObject()
    contentItem.put("type", "text")
    contentItem.put("text", userPayload)

    val content = JSONArray()
    content.put(contentItem)

    val userMessage = JSONObject()
    userMessage.put("role", "user")
    userMessage.put("content", content)

    val messages = JSONArray()
    messages.put(userMessage)

    val body = JSONObject()
    body.put("model", settings.model)
    body.put("max_tokens", 8192)
    body.put("temperature", 0.2)
    body.put("system", systemPrompt)
    body.put("messages", messages)
    return body
}

internal fun buildDashScopeGenerationRequest(
    settings: SubtitleTranslationSettings,
    markerPayload: String,
    sourceLanguage: String,
    targetLanguage: String
): JSONObject {
    val message = JSONObject()
    message.put("role", "user")
    message.put("content", markerPayload)

    val messages = JSONArray()
    messages.put(message)

    val translationOptions = JSONObject()
    translationOptions.put("source_lang", sourceLanguage)
    translationOptions.put("target_lang", targetLanguage)

    val parameters = JSONObject()
    parameters.put("result_format", "message")
    parameters.put("translation_options", translationOptions)

    return JSONObject()
        .put("model", settings.model)
        .put("input", JSONObject().put("messages", messages))
        .put("parameters", parameters)
}

internal fun openAiRequest(endpoint: String, apiKey: String, body: JSONObject): Request {
    return Request.Builder()
        .url(endpoint)
        .header("Authorization", "Bearer ${apiKey.trim()}")
        .header("Content-Type", "application/json")
        .post(body.toString().toRequestBody("application/json".toMediaType()))
        .build()
}

internal fun anthropicRequest(endpoint: String, apiKey: String, body: JSONObject): Request {
    return Request.Builder()
        .url(endpoint)
        .header("x-api-key", apiKey.trim())
        .header("anthropic-version", ANTHROPIC_VERSION)
        .header("Content-Type", "application/json")
        .post(body.toString().toRequestBody("application/json".toMediaType()))
        .build()
}

internal fun dashScopeRequest(endpoint: String, apiKey: String, body: JSONObject): Request {
    return Request.Builder()
        .url(endpoint)
        .header("Authorization", "Bearer ${apiKey.trim()}")
        .header("Content-Type", "application/json")
        .post(body.toString().toRequestBody("application/json".toMediaType()))
        .build()
}

internal fun parseOpenAiResponseText(raw: String): String? {
    val payload = JSONObject(raw)
    return payload.optJSONArray("choices")
        ?.optJSONObject(0)
        ?.optJSONObject("message")
        ?.optString("content")
        ?.let(::sanitizeJsonResponse)
        ?.takeIf(String::isNotBlank)
}

internal fun parseAnthropicResponseText(raw: String): String? {
    val content = JSONObject(raw).optJSONArray("content") ?: return null
    for (index in 0 until content.length()) {
        val item = content.optJSONObject(index) ?: continue
        val text = item.optString("text").takeIf(String::isNotBlank)?.let(::sanitizeJsonResponse)
        if (text != null) return text
    }
    return null
}

internal fun parseDashScopeResponseText(raw: String): String? {
    val payload = JSONObject(raw)
    return payload.optJSONObject("output")
        ?.optJSONArray("choices")
        ?.optJSONObject(0)
        ?.optJSONObject("message")
        ?.optString("content")
        ?.let(::sanitizeJsonResponse)
        ?.takeIf(String::isNotBlank)
}

internal fun sanitizeJsonResponse(text: String): String {
    val trimmed = text.trim()
    if (!trimmed.startsWith("```")) return trimmed
    val withoutOpeningFence = trimmed
        .removePrefix("```json")
        .removePrefix("```JSON")
        .removePrefix("```")
        .trimStart()
    return withoutOpeningFence
        .removeSuffix("```")
        .trim()
}
