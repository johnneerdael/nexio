package com.nexio.tv.data.repository

import android.content.Context
import android.util.Log
import com.nexio.tv.core.logging.sanitizeUrlForLogs
import com.nexio.tv.domain.model.Subtitle
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
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "GeminiSubtitleTx"
private const val GEMINI_MODEL = "gemini-2.5-flash"
private const val MAX_CHUNK_ENTRIES = 40
private const val MAX_CHUNK_CHARS = 3500

data class GeminiTranslatedSubtitleAsset(
    val sourceSubtitle: Subtitle,
    val translatedSubtitle: Subtitle
)

@Singleton
class GeminiSubtitleTranslationService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val httpClient: OkHttpClient
) {
    private val cueTranslationCache = ConcurrentHashMap<String, String>()

    suspend fun translateSubtitle(
        sourceSubtitle: Subtitle,
        targetLanguageCode: String,
        apiKey: String
    ): Result<GeminiTranslatedSubtitleAsset> = withContext(Dispatchers.IO) {
        runCatching {
            val normalizedTarget = targetLanguageCode.trim().ifBlank {
                throw IllegalArgumentException("Target language is required.")
            }
            val trimmedKey = apiKey.trim().ifBlank {
                throw IllegalArgumentException("Gemini API key is missing.")
            }

            val sourceText = downloadSubtitleText(sourceSubtitle.url)
            val document = TimedTextDocument.parse(sourceText, sourceSubtitle.url)
                ?: throw UnsupportedOperationException("Unsupported subtitle format.")

            val translatedFile = resolveCacheFile(
                sourceUrl = sourceSubtitle.url,
                targetLanguage = normalizedTarget,
                extension = document.extension
            )

            if (!translatedFile.exists() || translatedFile.length() == 0L) {
                val translatedBlocks = translateBlocks(
                    blocks = document.translatableBlocks,
                    targetLanguageCode = normalizedTarget,
                    apiKey = trimmedKey
                )
                translatedFile.parentFile?.mkdirs()
                translatedFile.writeText(document.render(translatedBlocks), StandardCharsets.UTF_8)
            }

            val translatedSubtitle = sourceSubtitle.copy(
                id = "gemini:${sourceSubtitle.id}:${normalizedTarget}",
                url = translatedFile.toURI().toString(),
                lang = normalizedTarget,
                addonName = "${sourceSubtitle.addonName} AI",
                addonLogo = sourceSubtitle.addonLogo
            )

            GeminiTranslatedSubtitleAsset(
                sourceSubtitle = sourceSubtitle,
                translatedSubtitle = translatedSubtitle
            )
        }
    }

    suspend fun translateCueTexts(
        texts: List<String>,
        targetLanguageCode: String,
        apiKey: String
    ): Result<Map<String, String>> = withContext(Dispatchers.IO) {
        runCatching {
            val normalizedTarget = targetLanguageCode.trim().ifBlank {
                throw IllegalArgumentException("Target language is required.")
            }
            val trimmedKey = apiKey.trim().ifBlank {
                throw IllegalArgumentException("Gemini API key is missing.")
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
                val cached = cueTranslationCache[cueCacheKey(text, normalizedTarget)]
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
                    apiKey = trimmedKey
                )
                translated.forEach { (source, value) ->
                    cueTranslationCache[cueCacheKey(source, normalizedTarget)] = value
                    resolved[source] = value
                }
            }

            resolved
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
        extension: String
    ): File {
        val cacheRoot = File(context.cacheDir, "gemini-subtitles")
        val key = sha256("$sourceUrl|$targetLanguage|v1")
        return File(cacheRoot, "$key.$extension")
    }

    private suspend fun translateBlocks(
        blocks: List<TranslatableTimedTextBlock>,
        targetLanguageCode: String,
        apiKey: String
    ): Map<Int, String> = coroutineScope {
        val chunks = chunkBlocks(blocks)
        val targetLanguageName = displayLanguage(targetLanguageCode)

        chunks.map { chunk ->
            async(Dispatchers.IO) {
                requestChunkTranslation(
                    blocks = chunk,
                    targetLanguageCode = targetLanguageCode,
                    targetLanguageName = targetLanguageName,
                    apiKey = apiKey
                )
            }
        }.awaitAll()
            .fold(mutableMapOf<Int, String>()) { acc, entries ->
                acc.apply { putAll(entries) }
            }
    }

    private fun chunkBlocks(blocks: List<TranslatableTimedTextBlock>): List<List<TranslatableTimedTextBlock>> {
        val result = mutableListOf<List<TranslatableTimedTextBlock>>()
        var current = mutableListOf<TranslatableTimedTextBlock>()
        var currentChars = 0

        for (block in blocks) {
            val nextChars = currentChars + block.text.length
            if (current.isNotEmpty() && (current.size >= MAX_CHUNK_ENTRIES || nextChars > MAX_CHUNK_CHARS)) {
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

    private fun requestChunkTranslation(
        blocks: List<TranslatableTimedTextBlock>,
        targetLanguageCode: String,
        targetLanguageName: String,
        apiKey: String
    ): Map<Int, String> {
        val requestBody = buildGenerationRequest(
            blocks = blocks,
            targetLanguageCode = targetLanguageCode,
            targetLanguageName = targetLanguageName,
            includeSchema = true
        )

        val responseText = executeGenerationRequest(requestBody, apiKey)
            ?: executeGenerationRequest(
                buildGenerationRequest(
                    blocks = blocks,
                    targetLanguageCode = targetLanguageCode,
                    targetLanguageName = targetLanguageName,
                    includeSchema = false
                ),
                apiKey = apiKey
            )
            ?: throw IllegalStateException("Gemini did not return a translation payload.")

        return parseTranslationResponse(responseText)
    }

    private suspend fun translateMissingCueTexts(
        texts: List<String>,
        targetLanguageCode: String,
        apiKey: String
    ): Map<String, String> = coroutineScope {
        val targetLanguageName = displayLanguage(targetLanguageCode)
        val blocks = texts.mapIndexed { index, text ->
            TranslatableTimedTextBlock(
                blockId = index,
                prefixLines = emptyList(),
                text = text
            )
        }
        val chunks = chunkBlocks(blocks)
        val translatedByIndex = chunks.map { chunk ->
            async(Dispatchers.IO) {
                requestChunkTranslation(
                    blocks = chunk,
                    targetLanguageCode = targetLanguageCode,
                    targetLanguageName = targetLanguageName,
                    apiKey = apiKey
                )
            }
        }.awaitAll().fold(mutableMapOf<Int, String>()) { acc, entries ->
            acc.apply { putAll(entries) }
        }

        buildMap {
            texts.forEachIndexed { index, source ->
                translatedByIndex[index]?.takeIf { it.isNotBlank() }?.let { translated ->
                    put(source, translated)
                }
            }
        }
    }

    private fun buildGenerationRequest(
        blocks: List<TranslatableTimedTextBlock>,
        targetLanguageCode: String,
        targetLanguageName: String,
        includeSchema: Boolean
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

        val promptPayload = JSONObject()
            .put("targetLanguageCode", targetLanguageCode)
            .put("targetLanguageName", targetLanguageName)
            .put("items", items)

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
                            buildString {
                                append("You are an expert subtitle localization specialist. ")
                                append("Translate only the text fields in the provided JSON items into ")
                                append(targetLanguageName)
                                append(" (")
                                append(targetLanguageCode)
                                append("). ")
                                append("Return JSON only. Keep the same ids. ")
                                append("Preserve subtitle brevity, punctuation, markup, speaker labels, and internal line breaks when possible.")
                            }
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

    private fun executeGenerationRequest(
        requestBody: JSONObject,
        apiKey: String
    ): String? {
        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$GEMINI_MODEL:generateContent")
            .header("x-goog-api-key", apiKey)
            .header("Content-Type", "application/json")
            .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        httpClient.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                Log.w(TAG, "Gemini request failed code=${response.code} body=${raw.take(300)}")
                return null
            }
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
    }

    private fun parseTranslationResponse(responseText: String): Map<Int, String> {
        val normalized = responseText.trim()
        val array = when {
            normalized.startsWith("[") -> JSONArray(normalized)
            normalized.startsWith("{") -> JSONObject(normalized).optJSONArray("items") ?: JSONArray()
            else -> JSONArray()
        }
        if (array.length() == 0) {
            throw IllegalStateException("Gemini returned an empty translation payload.")
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

    private fun displayLanguage(code: String): String {
        val locale = Locale.forLanguageTag(code)
        val name = locale.getDisplayLanguage(Locale.ENGLISH)
        return if (name.isNullOrBlank()) code else name
    }

    private fun cueCacheKey(text: String, targetLanguageCode: String): String {
        return sha256("cue|$targetLanguageCode|$text")
    }

    private fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}

private enum class TimedTextFormat(val extension: String) {
    SRT("srt"),
    VTT("vtt")
}

private sealed class TimedTextBlock {
    abstract fun render(translations: Map<Int, String>): String
}

private data class PassthroughTimedTextBlock(
    private val lines: List<String>
) : TimedTextBlock() {
    override fun render(translations: Map<Int, String>): String = lines.joinToString("\n")
}

private data class TranslatableTimedTextBlock(
    val blockId: Int,
    val prefixLines: List<String>,
    val text: String
) : TimedTextBlock() {
    override fun render(translations: Map<Int, String>): String {
        val translatedText = translations[blockId]?.trim().takeUnless { it.isNullOrBlank() } ?: text
        return (prefixLines + translatedText.split('\n')).joinToString("\n")
    }
}

private data class TimedTextDocument(
    val format: TimedTextFormat,
    val blocks: List<TimedTextBlock>
) {
    val extension: String
        get() = format.extension

    val translatableBlocks: List<TranslatableTimedTextBlock>
        get() = blocks.filterIsInstance<TranslatableTimedTextBlock>()

    fun render(translations: Map<Int, String>): String {
        return blocks.joinToString("\n\n") { it.render(translations) }.trim() + "\n"
    }

    companion object {
        fun parse(raw: String, url: String): TimedTextDocument? {
            val normalized = raw
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .trim()
            if (normalized.isBlank()) return null

            return when {
                normalized.startsWith("WEBVTT", ignoreCase = true) ||
                    url.substringBefore('?').substringBefore('#').lowercase().endsWith(".vtt") ->
                    parseVtt(normalized)
                url.substringBefore('?').substringBefore('#').lowercase().endsWith(".srt") ->
                    parseSrt(normalized)
                else -> {
                    val looksLikeSrt = normalized.lineSequence().any { it.contains("-->") }
                    if (looksLikeSrt) parseSrt(normalized) else null
                }
            }
        }

        private fun parseSrt(raw: String): TimedTextDocument {
            val blocks = raw.split(Regex("\n{2,}"))
            val parsed = mutableListOf<TimedTextBlock>()
            var nextBlockId = 0
            for (block in blocks) {
                val lines = block.lines().filterNot { it.isEmpty() }
                val timestampIndex = lines.indexOfFirst { it.contains("-->") }
                if (timestampIndex >= 0 && timestampIndex < lines.lastIndex) {
                    parsed += TranslatableTimedTextBlock(
                        blockId = nextBlockId++,
                        prefixLines = lines.take(timestampIndex + 1),
                        text = lines.drop(timestampIndex + 1).joinToString("\n")
                    )
                } else {
                    parsed += PassthroughTimedTextBlock(lines)
                }
            }
            return TimedTextDocument(TimedTextFormat.SRT, parsed)
        }

        private fun parseVtt(raw: String): TimedTextDocument {
            val blocks = raw.split(Regex("\n{2,}"))
            val parsed = mutableListOf<TimedTextBlock>()
            var nextBlockId = 0
            for (block in blocks) {
                val lines = block.lines().filterNot { it.isEmpty() }
                val timestampIndex = lines.indexOfFirst { it.contains("-->") }
                if (timestampIndex >= 0 && timestampIndex < lines.lastIndex) {
                    parsed += TranslatableTimedTextBlock(
                        blockId = nextBlockId++,
                        prefixLines = lines.take(timestampIndex + 1),
                        text = lines.drop(timestampIndex + 1).joinToString("\n")
                    )
                } else {
                    parsed += PassthroughTimedTextBlock(lines)
                }
            }
            return TimedTextDocument(TimedTextFormat.VTT, parsed)
        }
    }
}
