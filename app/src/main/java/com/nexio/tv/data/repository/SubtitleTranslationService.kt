package com.nexio.tv.data.repository

import android.content.Context
import android.util.Log
import com.nexio.tv.core.logging.sanitizeUrlForLogs
import com.nexio.tv.domain.model.Subtitle
import com.nexio.tv.domain.model.SubtitleTranslationDefaults
import com.nexio.tv.domain.model.SubtitleTranslationProvider
import com.nexio.tv.domain.model.SubtitleTranslationSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SubtitleTranslation"
private const val MAX_TRANSLATION_PROVIDER_ATTEMPTS = 4
private const val DEFAULT_RETRY_DELAY_MS = 1_000L
private const val MAX_RETRY_DELAY_MS = 8_000L

internal fun subtitleTranslationCueCacheKey(
    text: String,
    targetLanguageCode: String,
    settings: SubtitleTranslationSettings,
    sourceLanguageCode: String? = null
): String {
    return sha256("cue|${settings.provider}|${settings.model}|${settings.baseUrl}|${sourceLanguageCode.orEmpty()}|$targetLanguageCode|$text")
}

internal fun translatedSubtitleId(
    sourceSubtitleId: String,
    targetLanguage: String,
    settings: SubtitleTranslationSettings
): String {
    return "ai:${settings.provider.name.lowercase()}:$sourceSubtitleId:$targetLanguage"
}

internal fun subtitleTranslationDiskCacheKey(
    sourceUrl: String,
    targetLanguage: String,
    settings: SubtitleTranslationSettings
): String {
    return sha256("file|$sourceUrl|$targetLanguage|${settings.provider}|${settings.model}|${settings.baseUrl}|v2")
}

internal enum class SubtitleTranslationProviderError {
    InvalidApiKey,
    AccessDenied,
    RateLimited,
    Transient,
    Unknown
}

private class SubtitleTranslationProviderException(
    message: String
) : IllegalStateException(message)

internal fun classifyProviderError(
    provider: SubtitleTranslationProvider,
    statusCode: Int,
    body: String
): SubtitleTranslationProviderError {
    return when (statusCode) {
        401 -> SubtitleTranslationProviderError.InvalidApiKey
        403 -> if (provider == SubtitleTranslationProvider.GEMINI && body.indicatesGeminiProjectDenied()) {
            SubtitleTranslationProviderError.AccessDenied
        } else {
            SubtitleTranslationProviderError.InvalidApiKey
        }
        429 -> SubtitleTranslationProviderError.RateLimited
        in 500..599 -> SubtitleTranslationProviderError.Transient
        else -> SubtitleTranslationProviderError.Unknown
    }
}

private fun String.indicatesGeminiProjectDenied(): Boolean {
    val normalized = lowercase(Locale.US)
    return "permission_denied" in normalized ||
        "project has been denied access" in normalized ||
        "denied access" in normalized
}

private fun sha256(input: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(StandardCharsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) }
}

data class TranslatedSubtitleAsset(
    val sourceSubtitle: Subtitle,
    val translatedSubtitle: Subtitle
)

data class SubtitleTranslationChunkConfig(
    val maxEntries: Int,
    val maxChars: Int,
    val minSplitEntries: Int = 20,
    val maxParallelRequests: Int = 3
)

@Singleton
class SubtitleTranslationService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val httpClient: OkHttpClient
) {
    companion object {
        val DEFAULT_CUE_CHUNK_CONFIG = SubtitleTranslationChunkConfig(
            maxEntries = 2_000,
            maxChars = 100_000,
            minSplitEntries = 100,
            maxParallelRequests = 1
        )
        val ADDON_OVERLAY_CUE_CHUNK_CONFIG = SubtitleTranslationChunkConfig(
            maxEntries = 2_000,
            maxChars = 100_000,
            minSplitEntries = 100,
            maxParallelRequests = 1
        )
    }

    private val cueTranslationCache = ConcurrentHashMap<String, String>()

    suspend fun translateSubtitle(
        sourceSubtitle: Subtitle,
        targetLanguageCode: String,
        sourceLanguageCode: String? = sourceSubtitle.lang,
        settings: SubtitleTranslationSettings
    ): Result<TranslatedSubtitleAsset> = withContext(Dispatchers.IO) {
        runCatching {
            val normalizedTarget = targetLanguageCode.trim().ifBlank {
                throw IllegalArgumentException("Target language is required.")
            }
            val normalizedSettings = settings.copy(
                apiKey = settings.apiKey.trim()
            )
            if (normalizedSettings.apiKey.isBlank()) {
                throw IllegalArgumentException("Subtitle translation API key is missing.")
            }

            val sourceText = downloadSubtitleText(sourceSubtitle.url)
            val document = TimedTextDocument.parse(sourceText, sourceSubtitle.url)
                ?: throw UnsupportedOperationException("Unsupported subtitle format.")

            val translatedFile = resolveCacheFile(
                sourceUrl = sourceSubtitle.url,
                targetLanguage = normalizedTarget,
                extension = document.extension,
                settings = normalizedSettings
            )

            if (!translatedFile.exists() || translatedFile.length() == 0L) {
                val renderedSubtitle = if (document.format == TimedTextFormat.ASS ||
                    document.format == TimedTextFormat.SSA
                ) {
                    val protectedTranslations = mutableMapOf<String, String>()
                    AssSsaTranslationBatchPlanner.plan(document.assSsaProtectedUnits())
                        .forEach { batch ->
                            protectedTranslations += translateProtectedAssSsaUnits(
                                units = batch.units,
                                targetLanguageCode = normalizedTarget,
                                sourceLanguageCode = sourceLanguageCode,
                                settings = normalizedSettings
                            ).getOrThrow()
                        }
                    document.renderAssSsaProtected(protectedTranslations)
                } else {
                    val translatedBlocks = translateBlocks(
                        blocks = document.translatableBlocks,
                        targetLanguageCode = normalizedTarget,
                        sourceLanguageCode = sourceLanguageCode,
                        settings = normalizedSettings
                    )
                    document.render(translatedBlocks)
                }
                translatedFile.parentFile?.mkdirs()
                // Write + fsync so the file URI we hand back to the player is
                // safe to read immediately. Without this, Media3 can race the
                // pending write and stall on a partially-written file.
                val payload = renderedSubtitle.toByteArray(StandardCharsets.UTF_8)
                java.io.FileOutputStream(translatedFile).use { fos ->
                    fos.write(payload)
                    fos.flush()
                    try {
                        fos.fd.sync()
                    } catch (_: Throwable) {
                        // sync may be unsupported on some filesystems; ignore.
                    }
                }
                if (!translatedFile.exists() || translatedFile.length() == 0L) {
                    throw IllegalStateException("Translated subtitle file write failed.")
                }
            }

            val translatedSubtitle = sourceSubtitle.copy(
                id = translatedSubtitleId(sourceSubtitle.id, normalizedTarget, normalizedSettings),
                url = translatedFile.toURI().toString(),
                lang = normalizedTarget,
                addonName = "${sourceSubtitle.addonName} AI",
                addonLogo = sourceSubtitle.addonLogo
            )

            TranslatedSubtitleAsset(
                sourceSubtitle = sourceSubtitle,
                translatedSubtitle = translatedSubtitle
            )
        }
    }

    suspend fun translateSubtitle(
        sourceSubtitle: Subtitle,
        targetLanguageCode: String,
        apiKey: String
    ): Result<TranslatedSubtitleAsset> {
        return translateSubtitle(
            sourceSubtitle = sourceSubtitle,
            targetLanguageCode = targetLanguageCode,
            sourceLanguageCode = sourceSubtitle.lang,
            settings = geminiCompatibilitySettings(apiKey)
        )
    }

    suspend fun translateCueTexts(
        texts: List<String>,
        targetLanguageCode: String,
        sourceLanguageCode: String? = null,
        settings: SubtitleTranslationSettings,
        chunkConfig: SubtitleTranslationChunkConfig = DEFAULT_CUE_CHUNK_CONFIG
    ): Result<Map<String, String>> = withContext(Dispatchers.IO) {
        runCatching {
            val normalizedTarget = targetLanguageCode.trim().ifBlank {
                throw IllegalArgumentException("Target language is required.")
            }
            val normalizedSettings = settings.copy(
                apiKey = settings.apiKey.trim()
            )
            val normalizedSource = normalizeSourceLanguageCode(sourceLanguageCode)
            if (normalizedSettings.apiKey.isBlank()) {
                throw IllegalArgumentException("Subtitle translation API key is missing.")
            }
            val normalizedTexts = texts
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
            if (normalizedTexts.isEmpty()) {
                return@runCatching emptyMap()
            }

            val resolved = mutableMapOf<String, String>()
            val missing = mutableListOf<String>()
            for (text in normalizedTexts) {
                val cached = cueTranslationCache[subtitleTranslationCueCacheKey(text, normalizedTarget, normalizedSettings, normalizedSource)]
                if (cached != null) {
                    resolved[text] = cached
                } else {
                    missing += text
                }
            }

            if (missing.isNotEmpty()) {
                val translated = translateMissingCueTexts(
                    texts = missing,
                    targetLanguageCode = normalizedTarget,
                    sourceLanguageCode = normalizedSource,
                    settings = normalizedSettings,
                    chunkConfig = chunkConfig
                )
                translated.forEach { (source, value) ->
                    cueTranslationCache[subtitleTranslationCueCacheKey(source, normalizedTarget, normalizedSettings, normalizedSource)] = value
                    resolved[source] = value
                }
            }

            resolved
        }
    }

    suspend fun translateCueTexts(
        texts: List<String>,
        targetLanguageCode: String,
        apiKey: String,
        chunkConfig: SubtitleTranslationChunkConfig = DEFAULT_CUE_CHUNK_CONFIG
    ): Result<Map<String, String>> {
        return translateCueTexts(
            texts = texts,
            targetLanguageCode = targetLanguageCode,
            sourceLanguageCode = null,
            settings = geminiCompatibilitySettings(apiKey),
            chunkConfig = chunkConfig
        )
    }

    internal suspend fun translateProtectedAssSsaUnits(
        units: List<AssSsaProtectedTranslationUnit>,
        targetLanguageCode: String,
        sourceLanguageCode: String?,
        settings: SubtitleTranslationSettings
    ): Result<Map<String, String>> = withContext(Dispatchers.IO) {
        runCatching {
            val normalizedTarget = targetLanguageCode.trim().ifBlank {
                throw IllegalArgumentException("Target language is required.")
            }
            val normalizedSettings = settings.copy(apiKey = settings.apiKey.trim())
            if (normalizedSettings.apiKey.isBlank()) {
                throw IllegalArgumentException("Subtitle translation API key is missing.")
            }
            if (units.isEmpty()) {
                return@runCatching emptyMap()
            }

            val payload = JSONObject()
                .put("source_language", sourceLanguageCode.orEmpty().ifBlank { "auto" })
                .put("target_language", normalizedTarget)
                .put("placeholder_pattern", "⟦[A-Z_]+_[0-9]+⟧")
                .put(
                    "items",
                    JSONArray().apply {
                        units.forEach { unit ->
                            put(
                                JSONObject()
                                    .put("id", unit.id)
                                    .put("text", unit.protectedText)
                                    .put(
                                        "placeholders",
                                        JSONArray().apply {
                                            unit.placeholders.forEach { placeholder ->
                                                put(placeholder.token)
                                            }
                                        }
                                    )
                            )
                        }
                    }
                )

            val response = executeTranslationRequest(
                promptPayload = payload,
                targetLanguageCode = normalizedTarget,
                targetLanguageName = displayLanguage(normalizedTarget),
                sourceLanguageName = displaySourceLanguage(sourceLanguageCode),
                markerPayload = null,
                settings = normalizedSettings,
                includeSchema = true,
                systemPromptOverride = buildProtectedAssSsaSystemPrompt()
            ) ?: throw IllegalStateException("Subtitle translation provider did not return a translation payload.")

            parseProtectedAssSsaResponse(response, units)
        }
    }

    private suspend fun downloadSubtitleText(url: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("Subtitle download failed with HTTP ${response.code}.")
            }
            response.body?.string()?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("Subtitle file is empty.")
        }
    }

    private fun resolveCacheFile(
        sourceUrl: String,
        targetLanguage: String,
        extension: String,
        settings: SubtitleTranslationSettings
    ): File {
        val cacheRoot = File(context.cacheDir, "ai-subtitles")
        val key = subtitleTranslationDiskCacheKey(sourceUrl, targetLanguage, settings)
        return File(cacheRoot, "$key.$extension")
    }

    private suspend fun translateBlocks(
        blocks: List<TranslatableTimedTextBlock>,
        targetLanguageCode: String,
        sourceLanguageCode: String?,
        settings: SubtitleTranslationSettings,
        chunkConfig: SubtitleTranslationChunkConfig = DEFAULT_CUE_CHUNK_CONFIG
    ): Map<Int, String> = coroutineScope {
        val chunks = chunkBlocks(blocks, chunkConfig)
        val targetLanguageName = displayLanguage(targetLanguageCode)
        val sourceLanguageName = displaySourceLanguage(sourceLanguageCode)

        translateChunksFailFast(
            chunks = chunks,
            targetLanguageCode = targetLanguageCode,
            targetLanguageName = targetLanguageName,
            sourceLanguageName = sourceLanguageName,
            settings = settings,
            chunkConfig = chunkConfig
        )
    }

    private suspend fun translateChunksFailFast(
        chunks: List<List<TranslatableTimedTextBlock>>,
        targetLanguageCode: String,
        targetLanguageName: String,
        sourceLanguageName: String,
        settings: SubtitleTranslationSettings,
        chunkConfig: SubtitleTranslationChunkConfig
    ): Map<Int, String> = coroutineScope {
        val parallelism = chunkConfig.maxParallelRequests.coerceIn(1, 1)
        val translatedByIndex = mutableMapOf<Int, String>()

        for (batch in chunks.chunked(parallelism)) {
            val batchResults = batch.map { chunk ->
                async(Dispatchers.IO) {
                    requestChunkTranslationAdaptive(
                        blocks = chunk,
                        targetLanguageCode = targetLanguageCode,
                        targetLanguageName = targetLanguageName,
                        sourceLanguageName = sourceLanguageName,
                        settings = settings,
                        chunkConfig = chunkConfig
                    )
                }
            }.awaitAll()
            batchResults.forEach(translatedByIndex::putAll)
        }

        translatedByIndex
    }

    private fun chunkBlocks(
        blocks: List<TranslatableTimedTextBlock>,
        chunkConfig: SubtitleTranslationChunkConfig
    ): List<List<TranslatableTimedTextBlock>> {
        val result = mutableListOf<List<TranslatableTimedTextBlock>>()
        var current = mutableListOf<TranslatableTimedTextBlock>()
        var currentChars = 0

        for (block in blocks) {
            val nextChars = currentChars + block.text.length
            if (current.isNotEmpty() && (current.size >= chunkConfig.maxEntries || nextChars > chunkConfig.maxChars)) {
                result += current.toList()
                current = mutableListOf()
                currentChars = 0
            }
            current += block
            currentChars += block.text.length
        }

        if (current.isNotEmpty()) {
            result += current.toList()
        }

        return result
    }

    private fun requestChunkTranslationAdaptive(
        blocks: List<TranslatableTimedTextBlock>,
        targetLanguageCode: String,
        targetLanguageName: String,
        sourceLanguageName: String,
        settings: SubtitleTranslationSettings,
        chunkConfig: SubtitleTranslationChunkConfig
    ): Map<Int, String> {
        return runCatching {
            requestChunkTranslation(
                blocks = blocks,
                targetLanguageCode = targetLanguageCode,
                targetLanguageName = targetLanguageName,
                sourceLanguageName = sourceLanguageName,
                settings = settings
            )
        }.getOrElse { error ->
            if (error is SubtitleTranslationProviderException) {
                throw error
            }
            if (blocks.size <= chunkConfig.minSplitEntries || blocks.size <= 1) {
                throw error
            }
            val midpoint = blocks.size / 2
            requestChunkTranslationAdaptive(
                blocks = blocks.take(midpoint),
                targetLanguageCode = targetLanguageCode,
                targetLanguageName = targetLanguageName,
                sourceLanguageName = sourceLanguageName,
                settings = settings,
                chunkConfig = chunkConfig
            ) + requestChunkTranslationAdaptive(
                blocks = blocks.drop(midpoint),
                targetLanguageCode = targetLanguageCode,
                targetLanguageName = targetLanguageName,
                sourceLanguageName = sourceLanguageName,
                settings = settings,
                chunkConfig = chunkConfig
            )
        }
    }

    private fun requestChunkTranslation(
        blocks: List<TranslatableTimedTextBlock>,
        targetLanguageCode: String,
        targetLanguageName: String,
        sourceLanguageName: String,
        settings: SubtitleTranslationSettings
    ): Map<Int, String> {
        if (settings.provider == SubtitleTranslationProvider.DASHSCOPE) {
            val responseText = executeTranslationRequest(
                promptPayload = buildPromptPayload(
                    blocks = blocks,
                    targetLanguageCode = targetLanguageCode,
                    targetLanguageName = targetLanguageName
                ),
                targetLanguageCode = targetLanguageCode,
                targetLanguageName = targetLanguageName,
                sourceLanguageName = sourceLanguageName,
                markerPayload = buildDashScopeMarkerPayload(blocks),
                settings = settings,
                includeSchema = false
            ) ?: throw IllegalStateException("Subtitle translation provider did not return a translation payload.")

            return parseDashScopeMarkerResponse(responseText, blocks)
        }

        val promptPayload = buildPromptPayload(
            blocks = blocks,
            targetLanguageCode = targetLanguageCode,
            targetLanguageName = targetLanguageName
        )

        val responseText = executeTranslationRequest(
            promptPayload = promptPayload,
            targetLanguageCode = targetLanguageCode,
            targetLanguageName = targetLanguageName,
            sourceLanguageName = sourceLanguageName,
            markerPayload = null,
            settings = settings,
            includeSchema = true
        )
            ?: executeTranslationRequest(
                promptPayload = promptPayload,
                targetLanguageCode = targetLanguageCode,
                targetLanguageName = targetLanguageName,
                sourceLanguageName = sourceLanguageName,
                markerPayload = null,
                settings = settings,
                includeSchema = false
            )
            ?: throw IllegalStateException("Subtitle translation provider did not return a translation payload.")

        return parseTranslationResponse(responseText)
    }

    private suspend fun translateMissingCueTexts(
        texts: List<String>,
        targetLanguageCode: String,
        sourceLanguageCode: String?,
        settings: SubtitleTranslationSettings,
        chunkConfig: SubtitleTranslationChunkConfig
    ): Map<String, String> = coroutineScope {
        val targetLanguageName = displayLanguage(targetLanguageCode)
        val sourceLanguageName = displaySourceLanguage(sourceLanguageCode)
        val blocks = texts.mapIndexed { index, text ->
            TranslatableTimedTextBlock(
                blockId = index,
                prefixLines = emptyList(),
                text = text
            )
        }
        val chunks = chunkBlocks(blocks, chunkConfig)
        val translatedByIndex = translateChunksFailFast(
            chunks = chunks,
            targetLanguageCode = targetLanguageCode,
            targetLanguageName = targetLanguageName,
            sourceLanguageName = sourceLanguageName,
            settings = settings,
            chunkConfig = chunkConfig
        )

        buildMap {
            texts.forEachIndexed { index, source ->
                translatedByIndex[index]?.takeIf { it.isNotBlank() }?.let { translated ->
                    put(source, translated)
                }
            }
        }
    }

    private fun buildPromptPayload(
        blocks: List<TranslatableTimedTextBlock>,
        targetLanguageCode: String,
        targetLanguageName: String
    ): JSONObject {
        val items = JSONArray().apply {
            blocks.forEach { block ->
                put(
                    JSONObject()
                        .put("id", block.blockId)
                        .put("text", block.text)
                )
            }
        }

        return JSONObject()
            .put("targetLanguageCode", targetLanguageCode)
            .put("targetLanguageName", targetLanguageName)
            .put("items", items)
    }

    private fun buildTranslationSystemPrompt(
        targetLanguageCode: String,
        targetLanguageName: String
    ): String {
        return buildString {
            append("You are an expert subtitle localization specialist. ")
            append("Translate only the text fields in the provided JSON items into ")
            append(targetLanguageName)
            append(" (")
            append(targetLanguageCode)
            append("). ")
            append("Return JSON only. Keep the same ids. ")
            append("Preserve subtitle brevity, punctuation, markup, speaker labels, and internal line breaks when possible.")
        }
    }

    private fun buildDashScopeMarkerPayload(
        blocks: List<TranslatableTimedTextBlock>
    ): String {
        return blocks.joinToString("\n") { block ->
            "[[${block.blockId}]] ${block.text}"
        }
    }

    private fun buildGeminiGenerationRequest(
        promptPayload: JSONObject,
        systemPrompt: String,
        includeSchema: Boolean
    ): JSONObject {
        val generationConfig = JSONObject()
            .put("temperature", 0.2)
            .put(
                "thinkingConfig",
                JSONObject().put("thinkingBudget", 0)
            )
            .put("responseMimeType", "application/json")

        if (includeSchema) {
            generationConfig.put(
                "responseSchema",
                JSONObject()
                    .put("type", "array")
                    .put(
                        "items",
                        JSONObject()
                            .put("type", "object")
                            .put(
                                "properties",
                                JSONObject()
                                    .put("id", JSONObject().put("type", "integer"))
                                    .put("text", JSONObject().put("type", "string"))
                            )
                            .put("required", JSONArray().put("id").put("text"))
                    )
            )
        }

        return JSONObject()
            .put(
                "systemInstruction",
                JSONObject().put(
                    "parts",
                    JSONArray().put(
                        JSONObject().put(
                            "text",
                            systemPrompt
                        )
                    )
                )
            )
            .put(
                "contents",
                JSONArray().put(
                    JSONObject().put(
                        "parts",
                        JSONArray().put(
                            JSONObject().put("text", promptPayload.toString())
                        )
                    )
                )
            )
            .put("generationConfig", generationConfig)
    }

    private fun executeTranslationRequest(
        promptPayload: JSONObject,
        targetLanguageCode: String,
        targetLanguageName: String,
        sourceLanguageName: String,
        markerPayload: String?,
        settings: SubtitleTranslationSettings,
        includeSchema: Boolean,
        systemPromptOverride: String? = null
    ): String? {
        val systemPrompt = systemPromptOverride
            ?: buildTranslationSystemPrompt(targetLanguageCode, targetLanguageName)
        val endpoint = providerEndpoint(settings)
        val request = when (settings.provider) {
            SubtitleTranslationProvider.OPENAI -> openAiRequest(
                endpoint = endpoint,
                apiKey = settings.apiKey,
                body = buildOpenAiChatCompletionRequest(
                    settings = settings,
                    systemPrompt = systemPrompt,
                    userPayload = promptPayload.toString(),
                    includeJsonMode = includeSchema
                )
            )
            SubtitleTranslationProvider.ANTHROPIC -> anthropicRequest(
                endpoint = endpoint,
                apiKey = settings.apiKey,
                body = buildAnthropicMessagesRequest(
                    settings = settings,
                    systemPrompt = systemPrompt,
                    userPayload = promptPayload.toString()
                )
            )
            SubtitleTranslationProvider.GEMINI -> geminiRequest(
                endpoint = endpoint,
                apiKey = settings.apiKey,
                body = buildGeminiGenerationRequest(
                    promptPayload = promptPayload,
                    systemPrompt = systemPrompt,
                    includeSchema = includeSchema
                )
            )
            SubtitleTranslationProvider.DASHSCOPE -> dashScopeRequest(
                endpoint = endpoint,
                apiKey = settings.apiKey,
                body = buildDashScopeGenerationRequest(
                    settings = settings,
                    markerPayload = markerPayload ?: promptPayload.toString(),
                    sourceLanguage = sourceLanguageName,
                    targetLanguage = targetLanguageName
                )
            )
        }

        var attempt = 1
        while (true) {
            httpClient.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    Log.w(TAG, "Subtitle translation request failed provider=${settings.provider} code=${response.code} attempt=$attempt body=${raw.take(300)}")
                    val providerError = classifyProviderError(settings.provider, response.code, raw)
                    if (includeSchema && isStructuredRequestFallbackEligible(response.code, providerError)) {
                        return null
                    }
                    if (providerError.isRetryable() && attempt < MAX_TRANSLATION_PROVIDER_ATTEMPTS) {
                        val delayMs = retryDelayMs(
                            responseHeader = response.header("Retry-After"),
                            body = raw,
                            attempt = attempt
                        )
                        Log.w(TAG, "Retrying subtitle translation provider=${settings.provider} attempt=${attempt + 1} delayMs=$delayMs")
                        sleepBeforeRetry(delayMs)
                        attempt += 1
                        continue
                    }
                    throw providerException(settings.provider, response.code, raw)
                }
                return when (settings.provider) {
                    SubtitleTranslationProvider.OPENAI -> parseOpenAiResponseText(raw)
                    SubtitleTranslationProvider.ANTHROPIC -> parseAnthropicResponseText(raw)
                    SubtitleTranslationProvider.GEMINI -> parseGeminiResponseText(raw)
                    SubtitleTranslationProvider.DASHSCOPE -> parseDashScopeResponseText(raw)
                }
            }
        }
    }

    private fun geminiRequest(
        endpoint: String,
        apiKey: String,
        body: JSONObject
    ): Request {
        return Request.Builder()
            .url(endpoint)
            .header("x-goog-api-key", apiKey)
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
    }

    private fun parseGeminiResponseText(raw: String): String? {
        val payload = JSONObject(raw)
        val candidates = payload.optJSONArray("candidates") ?: return null
        if (candidates.length() == 0) return null
        val content = candidates.optJSONObject(0)?.optJSONObject("content") ?: return null
        val parts = content.optJSONArray("parts") ?: return null
        for (index in 0 until parts.length()) {
            val text = parts.optJSONObject(index)?.optString("text").orEmpty()
            if (text.isNotBlank()) {
                return text
            }
        }
        return null
    }

    private fun isStructuredRequestFallbackEligible(
        statusCode: Int,
        providerError: SubtitleTranslationProviderError
    ): Boolean {
        return providerError == SubtitleTranslationProviderError.Unknown && statusCode in 400..499
    }

    private fun providerException(
        provider: SubtitleTranslationProvider,
        statusCode: Int,
        body: String
    ): SubtitleTranslationProviderException {
        val providerError = classifyProviderError(provider, statusCode, body)
        return when (providerError) {
            SubtitleTranslationProviderError.InvalidApiKey ->
                SubtitleTranslationProviderException(
                    message = "Subtitle translation API key was rejected."
                )
            SubtitleTranslationProviderError.AccessDenied ->
                SubtitleTranslationProviderException(
                    message = "Subtitle translation provider denied access for this API key or project."
                )
            SubtitleTranslationProviderError.RateLimited ->
                SubtitleTranslationProviderException(
                    message = "Subtitle translation provider rate limit reached. Try again later."
                )
            SubtitleTranslationProviderError.Transient ->
                SubtitleTranslationProviderException(
                    message = "Subtitle translation provider is temporarily unavailable."
                )
            SubtitleTranslationProviderError.Unknown ->
                SubtitleTranslationProviderException(
                    message = "Subtitle translation request failed with HTTP $statusCode."
                )
        }
    }

    private fun SubtitleTranslationProviderError.isRetryable(): Boolean {
        return this == SubtitleTranslationProviderError.RateLimited ||
            this == SubtitleTranslationProviderError.Transient
    }

    private fun retryDelayMs(
        responseHeader: String?,
        body: String,
        attempt: Int
    ): Long {
        return retryAfterHeaderDelayMs(responseHeader)
            ?: retryInfoDelayMs(body)
            ?: (DEFAULT_RETRY_DELAY_MS shl (attempt - 1)).coerceAtMost(MAX_RETRY_DELAY_MS)
    }

    private fun retryAfterHeaderDelayMs(value: String?): Long? {
        val normalized = value?.trim()?.takeIf(String::isNotBlank) ?: return null
        normalized.toLongOrNull()?.let { seconds ->
            return (seconds * 1_000L).coerceAtLeast(0L)
        }
        return runCatching {
            val retryAtMs = ZonedDateTime
                .parse(normalized, DateTimeFormatter.RFC_1123_DATE_TIME)
                .toInstant()
                .toEpochMilli()
            (retryAtMs - System.currentTimeMillis()).coerceAtLeast(0L)
        }.getOrNull()
    }

    private fun retryInfoDelayMs(body: String): Long? {
        val match = Regex(""""retryDelay"\s*:\s*"(\d+(?:\.\d+)?)s"""")
            .find(body)
            ?: return null
        return ((match.groupValues[1].toDoubleOrNull() ?: return null) * 1_000L)
            .toLong()
            .coerceAtLeast(0L)
    }

    private fun sleepBeforeRetry(delayMs: Long) {
        if (delayMs <= 0L) return
        Thread.sleep(delayMs)
    }

    private fun parseTranslationResponse(responseText: String): Map<Int, String> {
        val normalized = sanitizeJsonResponse(responseText)
        val array = when {
            normalized.startsWith("[") -> JSONArray(normalized)
            normalized.startsWith("{") -> JSONObject(normalized).optJSONArray("items") ?: JSONArray()
            else -> JSONArray()
        }
        if (array.length() == 0) {
            throw IllegalStateException("Subtitle translation provider returned an empty translation payload.")
        }

        return buildMap {
            for (index in 0 until array.length()) {
                val entry = array.optJSONObject(index) ?: continue
                val id = entry.optInt("id", Int.MIN_VALUE)
                val text = entry.optString("text").orEmpty()
                if (id != Int.MIN_VALUE && text.isNotBlank()) {
                    put(id, text)
                }
            }
        }
    }

    private fun parseDashScopeMarkerResponse(
        responseText: String,
        blocks: List<TranslatableTimedTextBlock>
    ): Map<Int, String> {
        val trimmed = sanitizeJsonResponse(responseText)
            .replace("\\n", "\n")
            .trim()
        if (trimmed.isBlank()) {
            throw IllegalStateException("Subtitle translation provider returned an empty translation payload.")
        }
        if (blocks.size == 1 && !trimmed.contains("[[")) {
            return mapOf(blocks.first().blockId to trimmed)
        }

        val parsed = Regex("""(?s)\[\[(\d+)]]\s*(.*?)(?=\n\[\[\d+]]|\z)""")
            .findAll(trimmed)
            .mapNotNull { match ->
                val id = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
                val text = match.groupValues[2].trim()
                if (text.isBlank()) null else id to text
            }
            .toMap()
        val expectedIds = blocks.map { it.blockId }.toSet()
        if (!parsed.keys.containsAll(expectedIds)) {
            throw IllegalStateException("Subtitle translation provider returned an incomplete translation payload.")
        }
        return parsed.filterKeys { it in expectedIds }
    }

    private fun buildProtectedAssSsaSystemPrompt(): String {
        return """
            You are a subtitle localization engine.
            Translate subtitle text from the source language to the target language.
            Return JSON only with this exact shape: {"items":[{"id":"same id as input","text":"translated subtitle text"}]}.
            Translate only item.text.
            Do not translate, edit, remove, reorder, duplicate, or normalize placeholders.
            Placeholders match this pattern: ⟦[A-Z_]+_[0-9]+⟧.
            Copy every placeholder from the source item.text into the translated item.text exactly.
            Preserve the semantic role of placeholders. If a placeholder marks styling around a word, place it around the translated equivalent of that word.
            Preserve line-break placeholders unless the input explicitly says line breaks may be adjusted.
            Do not introduce raw ASS/SSA syntax. Do not output "{", "}", "\pos", "\move", "\clip", "\iclip", "\p", "\t", "\fad", "\fade", "\org", color tags, or any backslash ASS override tag.
            Do not add markdown, comments, explanations, code fences, or extra keys.
            Preserve subtitle brevity and natural speech.
            The number of output items must equal the number of input items.
            Every output id must match one input id exactly.
        """.trimIndent()
    }

    private fun parseProtectedAssSsaResponse(
        responseText: String,
        units: List<AssSsaProtectedTranslationUnit>
    ): Map<String, String> {
        val normalized = sanitizeJsonResponse(responseText)
        val array = when {
            normalized.startsWith("[") -> JSONArray(normalized)
            normalized.startsWith("{") -> JSONObject(normalized).optJSONArray("items") ?: JSONArray()
            else -> JSONArray()
        }
        if (array.length() == 0) {
            throw IllegalStateException("Subtitle translation provider returned an empty translation payload.")
        }
        val expected = units.associateBy { it.id }
        val parsed = mutableMapOf<String, String>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val id = item.optString("id")
            val text = item.optString("text")
            val unit = expected[id] ?: continue
            unit.reconstruct(text).getOrThrow()
            parsed[id] = text
        }
        expected.keys.forEach { id ->
            if (!parsed.containsKey(id)) {
                throw IllegalStateException("Subtitle translation provider returned an incomplete translation payload.")
            }
        }
        return parsed
    }

    private fun geminiCompatibilitySettings(apiKey: String): SubtitleTranslationSettings {
        return SubtitleTranslationSettings(
            provider = SubtitleTranslationProvider.GEMINI,
            apiKey = apiKey,
            model = SubtitleTranslationDefaults.GEMINI_MODEL,
            baseUrl = SubtitleTranslationDefaults.GEMINI_BASE_URL
        )
    }

    private fun displayLanguage(code: String): String {
        val locale = Locale.forLanguageTag(code)
        val name = locale.getDisplayLanguage(Locale.ENGLISH)
        return if (name.isNullOrBlank()) code else name
    }

    private fun displaySourceLanguage(code: String?): String {
        val normalized = normalizeSourceLanguageCode(code)
        if (normalized.isBlank() || normalized == "auto" || normalized == "und" || normalized == "unknown") {
            return "auto"
        }
        return displayLanguage(normalized)
    }

    private fun normalizeSourceLanguageCode(code: String?): String {
        return code
            ?.trim()
            ?.lowercase(Locale.US)
            ?.replace('_', '-')
            .orEmpty()
    }

}

typealias GeminiSubtitleTranslationService = SubtitleTranslationService
typealias GeminiTranslatedSubtitleAsset = TranslatedSubtitleAsset
typealias GeminiTranslationChunkConfig = SubtitleTranslationChunkConfig
