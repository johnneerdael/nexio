package com.nexio.tv.data.repository

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.nexio.tv.core.logging.sanitizeUrlForLogs
import com.nexio.tv.core.integration.IntegrationCallResult
import com.nexio.tv.data.integration.subtitles.SubtitleTranslationExecutionResult
import com.nexio.tv.data.integration.subtitles.SubtitleTranslationIntegrationProvider
import com.nexio.tv.data.integration.subtitles.SubtitleSourceDownloadIntegrationProvider
import com.nexio.tv.data.integration.subtitles.transport.SubtitleTranslationTransportResult
import com.nexio.tv.data.integration.subtitles.transport.anthropicRequest
import com.nexio.tv.data.integration.subtitles.transport.dashScopeRequest
import com.nexio.tv.data.integration.subtitles.transport.geminiRequest
import com.nexio.tv.data.integration.subtitles.transport.openAiRequest
import com.nexio.tv.domain.model.Subtitle
import com.nexio.tv.domain.model.SubtitleTranslationDefaults
import com.nexio.tv.domain.model.SubtitleTranslationProvider
import com.nexio.tv.domain.model.SubtitleTranslationSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SubtitleTranslation"
private const val MAX_TRANSLATION_PROVIDER_ATTEMPTS = 4
private const val MAX_TRANSLATION_PARALLELISM = 6
private const val DEFAULT_RETRY_DELAY_MS = 1_000L
private const val MAX_RETRY_DELAY_MS = 8_000L
private val RAW_ASS_TRANSLATION_SYNTAX_PATTERN =
    Regex("""\\(?![Nnh])(?:[A-Za-z]+\d*|\d+[A-Za-z]+)""")
private val RAW_SUBRIP_TAG_PATTERN = Regex("""<[^>\n]+>""")

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
    return sha256(
        "file|$sourceUrl|$targetLanguage|${settings.provider}|${settings.model}|${settings.baseUrl}|" +
            "assRaw=${settings.assSsaSystemPromptEnabled}|srtRaw=${settings.subRipSystemPromptEnabled}|v3"
    )
}

private data class RawSubRipCue(
    val number: String,
    val timestamp: String,
    val textLines: List<String>,
    val raw: String
) {
    val tags: List<String>
        get() = RAW_SUBRIP_TAG_PATTERN.findAll(textLines.joinToString("\n")).map { it.value }.toList()
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

private fun String.utf8Size(): Int = toByteArray(StandardCharsets.UTF_8).size

data class TranslatedSubtitleAsset(
    val sourceSubtitle: Subtitle,
    val translatedSubtitle: Subtitle
)

data class SubtitleTranslationChunkConfig(
    val maxEntries: Int,
    val maxChars: Int,
    val minSplitEntries: Int = 20,
    val maxParallelRequests: Int = 3,
    val rampUpEnabled: Boolean = true
)

@Singleton
class SubtitleTranslationService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val subtitleTranslationIntegrationProvider: SubtitleTranslationIntegrationProvider,
    private val subtitleSourceDownloadIntegrationProvider: SubtitleSourceDownloadIntegrationProvider,
    internal val diagnosticsLogger: AutoTranslateDiagnosticsLogger =
        AutoTranslateDiagnosticsLogger.disabled(),
    private val reasoningModels: OpenRouterReasoningModels =
        OpenRouterReasoningModels(context)
) {
    companion object {
        val DEFAULT_CUE_CHUNK_CONFIG = SubtitleTranslationChunkConfig(
            maxEntries = 2_000,
            maxChars = 100_000,
            minSplitEntries = 100,
            maxParallelRequests = 3
        )
        val ADDON_OVERLAY_CUE_CHUNK_CONFIG = SubtitleTranslationChunkConfig(
            maxEntries = 2_000,
            maxChars = 100_000,
            minSplitEntries = 100,
            maxParallelRequests = 3
        )
        val RAW_SUBRIP_SYSTEM_PROMPT_CHUNK_CONFIG = SubtitleTranslationChunkConfig(
            maxEntries = 50,
            maxChars = 15_000,
            minSplitEntries = 1,
            maxParallelRequests = 1
        )

        @androidx.annotation.VisibleForTesting
        internal fun buildTranslationSystemPromptForTest(
            targetLanguageCode: String,
            targetLanguageName: String,
            sourceLanguageName: String
        ): String {
            // Single source of truth for buildTranslationSystemPrompt body.
            // The instance method delegates here; tests can exercise it
            // without instantiating the full DI graph.
            return buildString {
                append("You are an expert subtitle localization specialist. ")
                append("Translate only the text fields in the provided JSON items into ")
                append(targetLanguageName)
                append(" (")
                append(targetLanguageCode)
                append("). ")
                if (sourceLanguageName.equals("auto", ignoreCase = true)) {
                    append("The source language is unknown — detect it automatically from the cue text. ")
                } else {
                    append("Translate from ")
                    append(sourceLanguageName)
                    append(". ")
                }
                append("Return JSON only. Keep the same ids. ")
                append("Preserve subtitle brevity, punctuation, markup, speaker labels, and internal line breaks when possible.")
            }
        }

        @androidx.annotation.VisibleForTesting
        internal fun buildRawSubRipSystemPromptForTest(
            targetLanguageName: String,
            sourceLanguageName: String
        ): String {
            val sourceClause = rawSubRipSourceClause(targetLanguageName, sourceLanguageName)
            return """
                You are SRT_TRANSLATION_ENGINE.

                $sourceClause

                Your task is to translate raw SRT subtitle content into $targetLanguageName while preserving valid SRT format exactly.
            """.trimIndent()
        }

        @androidx.annotation.VisibleForTesting
        internal fun buildRawAssSsaSystemPromptForTest(
            targetLanguageName: String,
            sourceLanguageName: String
        ): String {
            val sourceClause = rawAssSsaSourceClause(targetLanguageName, sourceLanguageName)
            return """
                You are ASS_SSA_SUBTITLE_TRANSLATOR.

                target_language = $targetLanguageName
                $sourceClause
            """.trimIndent()
        }
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
                val renderedSubtitle = if (document.format == TimedTextFormat.SRT &&
                    normalizedSettings.subRipSystemPromptEnabled
                ) {
                    translateRawSubRipText(
                        text = sourceText,
                        targetLanguageCode = normalizedTarget,
                        sourceLanguageCode = sourceLanguageCode,
                        settings = normalizedSettings
                    ).getOrThrow()
                } else if (document.format == TimedTextFormat.ASS ||
                    document.format == TimedTextFormat.SSA
                ) {
                    if (normalizedSettings.assSsaSystemPromptEnabled) {
                        translateRawAssSsaText(
                            text = sourceText,
                            targetLanguageCode = normalizedTarget,
                            sourceLanguageCode = sourceLanguageCode,
                            settings = normalizedSettings
                        ).getOrThrow()
                    } else {
                        val batches = AssSsaTranslationBatchPlanner.plan(document.assSsaProtectedUnits())
                        val batchResponses = batches.map { batch ->
                            translateProtectedAssSsaUnits(
                                units = batch.units,
                                targetLanguageCode = normalizedTarget,
                                sourceLanguageCode = sourceLanguageCode,
                                settings = normalizedSettings
                            ).getOrThrow()
                        }
                        val protectedTranslations = mutableMapOf<String, String>()
                        batches.forEachIndexed { index, batch ->
                            val response = batchResponses[index]
                            batch.coreUnits.forEach { unit ->
                                response[unit.id]?.takeIf { it.isNotBlank() }?.let {
                                    protectedTranslations[unit.id] = it
                                }
                            }
                        }
                        val allCoreIds = batches.flatMapTo(mutableSetOf()) { batch ->
                            batch.coreUnits.map { it.id }
                        }
                        batches.forEachIndexed { index, batch ->
                            val response = batchResponses[index]
                            val overlapUnits = batch.units.take(batch.leadOverlap) +
                                batch.units.drop(batch.leadOverlap + batch.coreCount)
                            overlapUnits.forEach { unit ->
                                if (unit.id in allCoreIds && unit.id !in protectedTranslations) {
                                    response[unit.id]?.takeIf { it.isNotBlank() }?.let {
                                        protectedTranslations[unit.id] = it
                                    }
                                }
                            }
                        }
                        diagnosticsLogger.log(
                            "ass_translate_merge batches=${batches.size} " +
                                "core_units=${allCoreIds.size} " +
                                "translated=${protectedTranslations.size}"
                        )
                        document.renderAssSsaProtected(protectedTranslations)
                    }
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

            parseProtectedAssSsaResponse(response, units).also { parsed ->
                diagnosticsLogger.log(
                    "protected_ass_parse_success requestedItems=${units.size} parsedItems=${parsed.size} " +
                        "missingItems=${(units.size - parsed.size).coerceAtLeast(0)}"
                )
            }
        }
    }

    internal suspend fun translateRawAssSsaText(
        text: String,
        targetLanguageCode: String,
        sourceLanguageCode: String?,
        settings: SubtitleTranslationSettings
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val normalizedTarget = targetLanguageCode.trim().ifBlank {
                throw IllegalArgumentException("Target language is required.")
            }
            val normalizedSettings = settings.copy(apiKey = settings.apiKey.trim())
            if (normalizedSettings.apiKey.isBlank()) {
                throw IllegalArgumentException("Subtitle translation API key is missing.")
            }
            if (text.isBlank()) {
                return@runCatching text
            }

            val response = executeRawTranslationRequest(
                systemPrompt = buildRawAssSsaSystemPrompt(
                    targetLanguageName = displayLanguage(normalizedTarget),
                    sourceLanguageName = displaySourceLanguage(sourceLanguageCode)
                ),
                userPayload = text,
                sourceLanguageName = displaySourceLanguage(sourceLanguageCode),
                targetLanguageName = displayLanguage(normalizedTarget),
                settings = normalizedSettings
            ) ?: throw IllegalStateException("Subtitle translation provider did not return raw ASS/SSA text.")

            val translated = sanitizeRawAssSsaResponse(response)
            val validation = validateRawAssSsaTranslation(
                source = text,
                translated = translated
            )
            diagnosticsLogger.log(
                "raw_ass_validation result=${if (validation.isSuccess) "success" else "failure"} " +
                    "sourceBytes=${text.utf8Size()} translatedBytes=${translated.utf8Size()} " +
                    "sourceHash=${AutoTranslateDiagnosticsLogger.sha256Short(text)} " +
                    "translatedHash=${AutoTranslateDiagnosticsLogger.sha256Short(translated)} " +
                    "error=${validation.exceptionOrNull()?.message.orEmpty()}"
            )
            validation.getOrThrow()
            translated
        }
    }

    internal suspend fun translateRawSubRipText(
        text: String,
        targetLanguageCode: String,
        sourceLanguageCode: String?,
        settings: SubtitleTranslationSettings,
        chunkConfig: SubtitleTranslationChunkConfig = RAW_SUBRIP_SYSTEM_PROMPT_CHUNK_CONFIG
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val normalizedTarget = targetLanguageCode.trim().ifBlank {
                throw IllegalArgumentException("Target language is required.")
            }
            val normalizedSettings = settings.copy(apiKey = settings.apiKey.trim())
            if (normalizedSettings.apiKey.isBlank()) {
                throw IllegalArgumentException("Subtitle translation API key is missing.")
            }
            if (text.isBlank()) {
                return@runCatching text
            }

            val cues = parseRawSubRipCues(text)
            if (cues.isEmpty()) {
                return@runCatching text
            }
            val targetLanguageName = displayLanguage(normalizedTarget)
            val sourceLanguageName = displaySourceLanguage(sourceLanguageCode)
            val coreTranslatedCues = mutableListOf<RawSubRipCue>()
            chunkRawSubRipCues(cues, chunkConfig).forEach { batch ->
                val translatedText = requestRawSubRipBatchAdaptive(
                    cues = batch.items,
                    targetLanguageName = targetLanguageName,
                    sourceLanguageName = sourceLanguageName,
                    settings = normalizedSettings,
                    chunkConfig = chunkConfig
                ).trim()
                val translatedCues = parseRawSubRipCues(translatedText)
                if (translatedCues.size == batch.items.size) {
                    coreTranslatedCues += translatedCues.subList(
                        batch.leadOverlap,
                        batch.leadOverlap + batch.coreCount
                    )
                } else {
                    // validateRawSubRipTranslation should have caught this, but be defensive:
                    // fall back to the untranslated core cues rather than emit a misaligned batch.
                    coreTranslatedCues += batch.coreItems
                }
            }
            renderRawSubRipCues(coreTranslatedCues)
        }
    }

    private suspend fun downloadSubtitleText(url: String): String = withContext(Dispatchers.IO) {
        when (val result = subtitleSourceDownloadIntegrationProvider.downloadText(url)) {
            is com.nexio.tv.core.integration.IntegrationCallResult.Success -> result.value
            is com.nexio.tv.core.integration.IntegrationCallResult.HttpError -> {
                throw IllegalStateException("Subtitle download failed with HTTP ${result.statusCode}.")
            }
            is com.nexio.tv.core.integration.IntegrationCallResult.NetworkError -> throw result.throwable
            com.nexio.tv.core.integration.IntegrationCallResult.Missing -> {
                throw IllegalStateException("Subtitle file is empty.")
            }
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
        val batches = chunkBlocks(blocks, chunkConfig)
        val targetLanguageName = displayLanguage(targetLanguageCode)
        val sourceLanguageName = displaySourceLanguage(sourceLanguageCode)

        translateChunksFailFast(
            batches = batches,
            targetLanguageCode = targetLanguageCode,
            targetLanguageName = targetLanguageName,
            sourceLanguageName = sourceLanguageName,
            settings = settings,
            chunkConfig = chunkConfig
        )
    }

    private suspend fun translateChunksFailFast(
        batches: List<RampedTranslationBatch<TranslatableTimedTextBlock>>,
        targetLanguageCode: String,
        targetLanguageName: String,
        sourceLanguageName: String,
        settings: SubtitleTranslationSettings,
        chunkConfig: SubtitleTranslationChunkConfig
    ): Map<Int, String> = coroutineScope {
        if (batches.isEmpty()) return@coroutineScope emptyMap()

        var parallelism = chunkConfig.maxParallelRequests.coerceIn(1, MAX_TRANSLATION_PARALLELISM)
        val batchResponses = ArrayList<Map<Int, String>>(batches.size)

        var index = 0
        while (index < batches.size) {
            val windowEnd = (index + parallelism).coerceAtMost(batches.size)
            val window = batches.subList(index, windowEnd)
            val sawRateLimited = AtomicBoolean(false)
            val observer: () -> Unit = { sawRateLimited.set(true) }
            val windowResults = window.map { batch ->
                async(Dispatchers.IO) {
                    requestChunkTranslationAdaptive(
                        blocks = batch.items,
                        targetLanguageCode = targetLanguageCode,
                        targetLanguageName = targetLanguageName,
                        sourceLanguageName = sourceLanguageName,
                        settings = settings,
                        chunkConfig = chunkConfig,
                        onRateLimited = observer
                    )
                }
            }.awaitAll()
            batchResponses += windowResults
            index = windowEnd
            if (sawRateLimited.get() && parallelism > 1) {
                val pulled = (parallelism / 2).coerceAtLeast(1)
                diagnosticsLogger.log(
                    "translate_concurrency_pullback from=$parallelism to=$pulled reason=429"
                )
                parallelism = pulled
            }
        }

        val merged = mutableMapOf<Int, String>()

        batches.forEachIndexed { batchIndex, batch ->
            val response = batchResponses[batchIndex]
            batch.coreItems.forEach { block ->
                response[block.blockId]?.takeIf { it.isNotBlank() }?.let { text ->
                    merged[block.blockId] = text
                }
            }
        }

        val allCoreIds = batches.asSequence()
            .flatMap { batch -> batch.coreItems.asSequence().map { it.blockId } }
            .toSet()
        val missingAfterPrimary = allCoreIds.asSequence().filter { it !in merged }.toList()

        if (missingAfterPrimary.isNotEmpty()) {
            batches.forEachIndexed { batchIndex, batch ->
                val response = batchResponses[batchIndex]
                val overlapItems = batch.items.take(batch.leadOverlap) +
                    batch.items.drop(batch.leadOverlap + batch.coreCount)
                overlapItems.forEach { block ->
                    if (block.blockId in allCoreIds && block.blockId !in merged) {
                        response[block.blockId]?.takeIf { it.isNotBlank() }?.let { text ->
                            merged[block.blockId] = text
                        }
                    }
                }
            }
        }

        val missingAfterOverlap = allCoreIds.asSequence().filter { it !in merged }.toList()
        diagnosticsLogger.log(
            "translate_chunks batches=${batches.size} " +
                "core_cues=${allCoreIds.size} " +
                "missing_after_primary=${missingAfterPrimary.size} " +
                "missing_after_overlap=${missingAfterOverlap.size}"
        )

        if (missingAfterOverlap.isNotEmpty()) {
            val missingSet = missingAfterOverlap.toSet()
            val missingBlocks = batches.asSequence()
                .flatMap { it.coreItems.asSequence() }
                .filter { it.blockId in missingSet }
                .distinctBy { it.blockId }
                .toList()
            if (missingBlocks.isNotEmpty()) {
                val retryResult = runCatching {
                    requestChunkTranslationAdaptive(
                        blocks = missingBlocks,
                        targetLanguageCode = targetLanguageCode,
                        targetLanguageName = targetLanguageName,
                        sourceLanguageName = sourceLanguageName,
                        settings = settings,
                        chunkConfig = chunkConfig
                    )
                }.getOrElse { emptyMap() }
                retryResult.forEach { (id, text) ->
                    if (text.isNotBlank() && id in allCoreIds) {
                        merged[id] = text
                    }
                }
                val missingAfterRetry = allCoreIds.count { it !in merged }
                diagnosticsLogger.log(
                    "translate_chunks_retry requested=${missingBlocks.size} " +
                        "missing_after_retry=$missingAfterRetry"
                )
            }
        }

        merged
    }

    private fun chunkBlocks(
        blocks: List<TranslatableTimedTextBlock>,
        chunkConfig: SubtitleTranslationChunkConfig
    ): List<RampedTranslationBatch<TranslatableTimedTextBlock>> {
        return planRampedTranslationBatches(
            items = blocks,
            maxCoreEntries = chunkConfig.maxEntries,
            maxCoreChars = chunkConfig.maxChars,
            sizeOf = { block -> block.text.length },
            rampUpEnabled = chunkConfig.rampUpEnabled
        )
    }

    private fun parseRawSubRipCues(text: String): List<RawSubRipCue> {
        val normalized = text
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .trim()
        if (normalized.isBlank()) return emptyList()

        return normalized
            .split(Regex("\n{2,}"))
            .mapNotNull { block ->
                val lines = block.lines()
                val timestampIndex = lines.indexOfFirst { line -> "-->" in line }
                if (timestampIndex <= 0 || timestampIndex >= lines.lastIndex) {
                    null
                } else {
                    RawSubRipCue(
                        number = lines.first(),
                        timestamp = lines[timestampIndex],
                        textLines = lines.drop(timestampIndex + 1),
                        raw = block
                    )
                }
            }
    }

    private fun renderRawSubRipCues(cues: List<RawSubRipCue>): String {
        return cues.joinToString("\n\n") { cue -> cue.raw }.trim() + "\n"
    }

    private fun chunkRawSubRipCues(
        cues: List<RawSubRipCue>,
        chunkConfig: SubtitleTranslationChunkConfig
    ): List<RampedTranslationBatch<RawSubRipCue>> {
        return planRampedTranslationBatches(
            items = cues,
            maxCoreEntries = chunkConfig.maxEntries,
            maxCoreChars = chunkConfig.maxChars,
            sizeOf = { cue -> cue.raw.length },
            rampUpEnabled = chunkConfig.rampUpEnabled
        )
    }

    private fun requestRawSubRipBatchAdaptive(
        cues: List<RawSubRipCue>,
        targetLanguageName: String,
        sourceLanguageName: String,
        settings: SubtitleTranslationSettings,
        chunkConfig: SubtitleTranslationChunkConfig
    ): String {
        return runCatching {
            requestRawSubRipBatch(
                cues = cues,
                targetLanguageName = targetLanguageName,
                sourceLanguageName = sourceLanguageName,
                settings = settings
            )
        }.getOrElse { error ->
            if (error is SubtitleTranslationProviderException) {
                throw error
            }
            if (cues.size <= chunkConfig.minSplitEntries || cues.size <= 1) {
                throw error
            }
            val midpoint = cues.size / 2
            listOf(
                requestRawSubRipBatchAdaptive(
                    cues = cues.take(midpoint),
                    targetLanguageName = targetLanguageName,
                    sourceLanguageName = sourceLanguageName,
                    settings = settings,
                    chunkConfig = chunkConfig
                ),
                requestRawSubRipBatchAdaptive(
                    cues = cues.drop(midpoint),
                    targetLanguageName = targetLanguageName,
                    sourceLanguageName = sourceLanguageName,
                    settings = settings,
                    chunkConfig = chunkConfig
                )
            ).joinToString("\n\n") { it.trim() }
        }
    }

    private fun requestRawSubRipBatch(
        cues: List<RawSubRipCue>,
        targetLanguageName: String,
        sourceLanguageName: String,
        settings: SubtitleTranslationSettings
    ): String {
        val source = renderRawSubRipCues(cues)
        val userPayload = buildRawSubRipUserPayload(source, targetLanguageName)
        val response = executeRawTranslationRequest(
            systemPrompt = buildRawSubRipSystemPrompt(targetLanguageName, sourceLanguageName),
            userPayload = userPayload,
            sourceLanguageName = sourceLanguageName,
            targetLanguageName = targetLanguageName,
            settings = settings
        ) ?: throw IllegalStateException("Subtitle translation provider did not return raw SRT text.")
        val translated = sanitizeRawSubtitleResponse(response)
        validateRawSubRipTranslation(
            source = cues,
            translated = translated
        ).getOrThrow()
        return translated
    }

    private fun requestChunkTranslationAdaptive(
        blocks: List<TranslatableTimedTextBlock>,
        targetLanguageCode: String,
        targetLanguageName: String,
        sourceLanguageName: String,
        settings: SubtitleTranslationSettings,
        chunkConfig: SubtitleTranslationChunkConfig,
        onRateLimited: () -> Unit = {}
    ): Map<Int, String> {
        return runCatching {
            requestChunkTranslation(
                blocks = blocks,
                targetLanguageCode = targetLanguageCode,
                targetLanguageName = targetLanguageName,
                sourceLanguageName = sourceLanguageName,
                settings = settings,
                onRateLimited = onRateLimited
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
                chunkConfig = chunkConfig,
                onRateLimited = onRateLimited
            ) + requestChunkTranslationAdaptive(
                blocks = blocks.drop(midpoint),
                targetLanguageCode = targetLanguageCode,
                targetLanguageName = targetLanguageName,
                sourceLanguageName = sourceLanguageName,
                settings = settings,
                chunkConfig = chunkConfig,
                onRateLimited = onRateLimited
            )
        }
    }

    private fun requestChunkTranslation(
        blocks: List<TranslatableTimedTextBlock>,
        targetLanguageCode: String,
        targetLanguageName: String,
        sourceLanguageName: String,
        settings: SubtitleTranslationSettings,
        onRateLimited: () -> Unit = {}
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
                includeSchema = false,
                onRateLimited = onRateLimited
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
            includeSchema = true,
            onRateLimited = onRateLimited
        )
            ?: executeTranslationRequest(
                promptPayload = promptPayload,
                targetLanguageCode = targetLanguageCode,
                targetLanguageName = targetLanguageName,
                sourceLanguageName = sourceLanguageName,
                markerPayload = null,
                settings = settings,
                includeSchema = false,
                onRateLimited = onRateLimited
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
        val batches = chunkBlocks(blocks, chunkConfig)
        val translatedByIndex = translateChunksFailFast(
            batches = batches,
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
        targetLanguageName: String,
        sourceLanguageName: String
    ): String = buildTranslationSystemPromptForTest(
        targetLanguageCode = targetLanguageCode,
        targetLanguageName = targetLanguageName,
        sourceLanguageName = sourceLanguageName
    )

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
            .put("temperature", 0)
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

    private fun buildGeminiRawGenerationRequest(
        userPayload: String,
        systemPrompt: String
    ): JSONObject {
        val generationConfig = JSONObject()
            .put("temperature", 0)
            .put(
                "thinkingConfig",
                JSONObject().put("thinkingBudget", 0)
            )

        return JSONObject()
            .put(
                "systemInstruction",
                JSONObject().put(
                    "parts",
                    JSONArray().put(
                        JSONObject().put("text", systemPrompt)
                    )
                )
            )
            .put(
                "contents",
                JSONArray().put(
                    JSONObject().put(
                        "parts",
                        JSONArray().put(
                            JSONObject().put("text", userPayload)
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
        systemPromptOverride: String? = null,
        onRateLimited: () -> Unit = {}
    ): String? {
        val systemPrompt = systemPromptOverride
            ?: buildTranslationSystemPrompt(targetLanguageCode, targetLanguageName, sourceLanguageName)
        val endpoint = providerEndpoint(settings)
        val userPayload = promptPayload.toString()
        val (request, requestBodyText) = when (settings.provider) {
            SubtitleTranslationProvider.OPENAI -> {
                val itemCount = if (includeSchema) {
                    promptPayload.optJSONArray("items")?.length()
                } else {
                    null
                }
                val body = buildOpenAiChatCompletionRequest(
                    settings = settings,
                    systemPrompt = systemPrompt,
                    userPayload = userPayload,
                    includeJsonMode = includeSchema,
                    strictJsonSchemaItemCount = itemCount,
                    isReasoningModel = reasoningModels.isReasoningModel(settings.model)
                )
                openAiRequest(endpoint = endpoint, apiKey = settings.apiKey, body = body) to body.toString()
            }
            SubtitleTranslationProvider.ANTHROPIC -> {
                val body = buildAnthropicMessagesRequest(
                    settings = settings,
                    systemPrompt = systemPrompt,
                    userPayload = userPayload
                )
                anthropicRequest(endpoint = endpoint, apiKey = settings.apiKey, body = body) to body.toString()
            }
            SubtitleTranslationProvider.GEMINI -> {
                val body = buildGeminiGenerationRequest(
                    promptPayload = promptPayload,
                    systemPrompt = systemPrompt,
                    includeSchema = includeSchema
                )
                geminiRequest(endpoint = endpoint, apiKey = settings.apiKey, body = body) to body.toString()
            }
            SubtitleTranslationProvider.DASHSCOPE -> {
                val body = buildDashScopeGenerationRequest(
                    settings = settings,
                    markerPayload = markerPayload ?: userPayload,
                    sourceLanguage = sourceLanguageName,
                    targetLanguage = targetLanguageName
                )
                dashScopeRequest(endpoint = endpoint, apiKey = settings.apiKey, body = body) to body.toString()
            }
        }
        logPreparedTranslationRequest(
            mode = "structured",
            provider = settings.provider,
            model = settings.model,
            request = request,
            requestBodyText = requestBodyText,
            systemPrompt = systemPrompt,
            userPayload = userPayload,
            targetLanguageCode = targetLanguageCode,
            sourceLanguageName = sourceLanguageName,
            includeSchema = includeSchema
        )

        var attempt = 1
        while (true) {
            val startedAtMs = SystemClock.elapsedRealtime()
            val response = executeTranslationProviderRequest(request)
            val raw = response.body.orEmpty()
            val elapsedMs = SystemClock.elapsedRealtime() - startedAtMs
            logTranslationResponse(
                mode = "structured",
                provider = settings.provider,
                model = settings.model,
                responseCode = response.statusCode,
                successful = response.isSuccessful,
                attempt = attempt,
                elapsedMs = elapsedMs,
                raw = raw
            )
            if (!response.isSuccessful) {
                Log.w(
                    TAG,
                    "Subtitle translation request failed provider=${settings.provider} " +
                        "code=${response.statusCode} attempt=$attempt body=${raw.take(300)}"
                )
                val providerError = classifyProviderError(settings.provider, response.statusCode, raw)
                if (includeSchema && isStructuredRequestFallbackEligible(response.statusCode, providerError)) {
                    diagnosticsLogger.log(
                        "structured_fallback_eligible provider=${settings.provider} " +
                            "code=${response.statusCode} error=$providerError"
                    )
                    return null
                }
                if (providerError.isRetryable() && attempt < MAX_TRANSLATION_PROVIDER_ATTEMPTS) {
                    if (providerError == SubtitleTranslationProviderError.RateLimited) {
                        onRateLimited()
                    }
                    val delayMs = retryDelayMs(
                        responseHeader = response.retryAfterHeader,
                        responseHeaderMs = response.retryAfterHeaderMs,
                        body = raw,
                        attempt = attempt
                    )
                    Log.w(
                        TAG,
                        "Retrying subtitle translation provider=${settings.provider} " +
                            "attempt=${attempt + 1} delayMs=$delayMs"
                    )
                    diagnosticsLogger.log(
                        "request_retry mode=structured provider=${settings.provider} " +
                            "nextAttempt=${attempt + 1} delayMs=$delayMs error=$providerError"
                    )
                    sleepBeforeRetry(delayMs)
                    attempt += 1
                    continue
                }
                if (providerError == SubtitleTranslationProviderError.RateLimited) {
                    onRateLimited()
                }
                throw providerException(settings.provider, response.statusCode, raw)
            }
            val parsed = when (settings.provider) {
                SubtitleTranslationProvider.OPENAI -> parseOpenAiResponseText(raw)
                SubtitleTranslationProvider.ANTHROPIC -> parseAnthropicResponseText(raw)
                SubtitleTranslationProvider.GEMINI -> parseGeminiResponseText(raw)
                SubtitleTranslationProvider.DASHSCOPE -> parseDashScopeResponseText(raw)
            }
            logParsedTranslationResponse(
                mode = "structured",
                provider = settings.provider,
                parsed = parsed
            )
            return parsed
        }
    }

    private fun executeRawTranslationRequest(
        systemPrompt: String,
        userPayload: String,
        sourceLanguageName: String,
        targetLanguageName: String,
        settings: SubtitleTranslationSettings
    ): String? {
        val endpoint = providerEndpoint(settings)
        val (request, requestBodyText) = when (settings.provider) {
            SubtitleTranslationProvider.OPENAI -> {
                val body = buildOpenAiChatCompletionRequest(
                    settings = settings,
                    systemPrompt = systemPrompt,
                    userPayload = userPayload,
                    includeJsonMode = false,
                    isReasoningModel = reasoningModels.isReasoningModel(settings.model)
                )
                openAiRequest(endpoint = endpoint, apiKey = settings.apiKey, body = body) to body.toString()
            }
            SubtitleTranslationProvider.ANTHROPIC -> {
                val body = buildAnthropicMessagesRequest(
                    settings = settings,
                    systemPrompt = systemPrompt,
                    userPayload = userPayload
                )
                anthropicRequest(endpoint = endpoint, apiKey = settings.apiKey, body = body) to body.toString()
            }
            SubtitleTranslationProvider.GEMINI -> {
                val body = buildGeminiRawGenerationRequest(
                    userPayload = userPayload,
                    systemPrompt = systemPrompt
                )
                geminiRequest(endpoint = endpoint, apiKey = settings.apiKey, body = body) to body.toString()
            }
            SubtitleTranslationProvider.DASHSCOPE -> {
                val body = buildDashScopeGenerationRequest(
                    settings = settings,
                    markerPayload = userPayload,
                    sourceLanguage = sourceLanguageName,
                    targetLanguage = targetLanguageName
                )
                dashScopeRequest(endpoint = endpoint, apiKey = settings.apiKey, body = body) to body.toString()
            }
        }
        logPreparedTranslationRequest(
            mode = "raw_ass",
            provider = settings.provider,
            model = settings.model,
            request = request,
            requestBodyText = requestBodyText,
            systemPrompt = systemPrompt,
            userPayload = userPayload,
            targetLanguageCode = targetLanguageName,
            sourceLanguageName = sourceLanguageName,
            includeSchema = false
        )

        var attempt = 1
        while (true) {
            val startedAtMs = SystemClock.elapsedRealtime()
            val response = executeTranslationProviderRequest(request)
            val raw = response.body.orEmpty()
            val elapsedMs = SystemClock.elapsedRealtime() - startedAtMs
            logTranslationResponse(
                mode = "raw_ass",
                provider = settings.provider,
                model = settings.model,
                responseCode = response.statusCode,
                successful = response.isSuccessful,
                attempt = attempt,
                elapsedMs = elapsedMs,
                raw = raw
            )
            if (!response.isSuccessful) {
                Log.w(
                    TAG,
                    "Raw ASS/SSA translation request failed provider=${settings.provider} " +
                        "code=${response.statusCode} attempt=$attempt body=${raw.take(300)}"
                )
                val providerError = classifyProviderError(settings.provider, response.statusCode, raw)
                if (providerError.isRetryable() && attempt < MAX_TRANSLATION_PROVIDER_ATTEMPTS) {
                    val delayMs = retryDelayMs(
                        responseHeader = response.retryAfterHeader,
                        responseHeaderMs = response.retryAfterHeaderMs,
                        body = raw,
                        attempt = attempt
                    )
                    Log.w(
                        TAG,
                        "Retrying raw ASS/SSA translation provider=${settings.provider} " +
                            "attempt=${attempt + 1} delayMs=$delayMs"
                    )
                    diagnosticsLogger.log(
                        "request_retry mode=raw_ass provider=${settings.provider} " +
                            "nextAttempt=${attempt + 1} delayMs=$delayMs error=$providerError"
                    )
                    sleepBeforeRetry(delayMs)
                    attempt += 1
                    continue
                }
                throw providerException(settings.provider, response.statusCode, raw)
            }
            val parsed = when (settings.provider) {
                SubtitleTranslationProvider.OPENAI -> parseOpenAiResponseText(raw)
                SubtitleTranslationProvider.ANTHROPIC -> parseAnthropicResponseText(raw)
                SubtitleTranslationProvider.GEMINI -> parseGeminiResponseText(raw)
                SubtitleTranslationProvider.DASHSCOPE -> parseDashScopeResponseText(raw)
            }
            logParsedTranslationResponse(
                mode = "raw_ass",
                provider = settings.provider,
                parsed = parsed
            )
            return parsed
        }
    }

    private fun logPreparedTranslationRequest(
        mode: String,
        provider: SubtitleTranslationProvider,
        model: String,
        request: Request,
        requestBodyText: String,
        systemPrompt: String,
        userPayload: String,
        targetLanguageCode: String,
        sourceLanguageName: String,
        includeSchema: Boolean
    ) {
        diagnosticsLogger.log(
            "request_prepared mode=$mode provider=$provider model=$model " +
                "host=${request.url.host} path=${request.url.encodedPath} target=$targetLanguageCode " +
                "source=$sourceLanguageName includeSchema=$includeSchema " +
                "requestBytes=${requestBodyText.utf8Size()} promptBytes=${systemPrompt.utf8Size()} " +
                "userBytes=${userPayload.utf8Size()} requestHash=${AutoTranslateDiagnosticsLogger.sha256Short(requestBodyText)}"
        )
        diagnosticsLogger.logUnsafe("request_body mode=$mode provider=$provider", requestBodyText)
        diagnosticsLogger.logUnsafe("request_system_prompt mode=$mode provider=$provider", systemPrompt)
        diagnosticsLogger.logUnsafe("request_user_payload mode=$mode provider=$provider", userPayload)
    }

    private fun executeTranslationProviderRequest(request: Request): SubtitleTranslationTransportResult {
        return runBlocking {
            when (val result = subtitleTranslationIntegrationProvider.execute(request)) {
            is IntegrationCallResult.Success -> {
                val response = result.value as? SubtitleTranslationExecutionResult.Response
                    ?: throw IllegalStateException("Subtitle translation provider returned an unexpected response.")
                SubtitleTranslationTransportResult(
                    statusCode = response.statusCode,
                    isSuccessful = response.isSuccessful,
                    body = response.body,
                    retryAfterHeader = response.retryAfterHeader,
                    retryAfterHeaderMs = response.retryAfterHeaderMs
                )
            }
            is IntegrationCallResult.HttpError -> SubtitleTranslationTransportResult(
                statusCode = result.statusCode,
                isSuccessful = false,
                body = result.reason,
                retryAfterHeader = null,
                retryAfterHeaderMs = result.retryAfterMs
            )
            is IntegrationCallResult.NetworkError -> throw result.throwable
                IntegrationCallResult.Missing -> throw IllegalStateException(
                    "Subtitle translation request returned empty response."
                )
            }
        }
    }

    private fun logTranslationResponse(
        mode: String,
        provider: SubtitleTranslationProvider,
        model: String,
        responseCode: Int,
        successful: Boolean,
        attempt: Int,
        elapsedMs: Long,
        raw: String
    ) {
        diagnosticsLogger.log(
            "response_received mode=$mode provider=$provider model=$model code=$responseCode " +
                "successful=$successful attempt=$attempt elapsedMs=$elapsedMs " +
                "responseBytes=${raw.utf8Size()} responseHash=${AutoTranslateDiagnosticsLogger.sha256Short(raw)}"
        )
        diagnosticsLogger.logUnsafe("response_body mode=$mode provider=$provider", raw)
    }

    private fun logParsedTranslationResponse(
        mode: String,
        provider: SubtitleTranslationProvider,
        parsed: String?
    ) {
        diagnosticsLogger.log(
            "response_parsed mode=$mode provider=$provider parsed=${parsed != null} " +
                "parsedBytes=${parsed.orEmpty().utf8Size()} parsedHash=${AutoTranslateDiagnosticsLogger.sha256Short(parsed.orEmpty())}"
        )
        diagnosticsLogger.logUnsafe("parsed_text mode=$mode provider=$provider", parsed.orEmpty())
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
        responseHeaderMs: Long?,
        body: String,
        attempt: Int
    ): Long {
        return responseHeaderMs
            ?: retryAfterHeaderDelayMs(responseHeader)
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
            normalized.startsWith("{") -> {
                val obj = JSONObject(normalized)
                obj.optJSONArray("items")
                    ?: if (obj.has("id") && obj.has("text")) JSONArray().put(obj) else JSONArray()
            }
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

    private fun buildRawAssSsaSystemPrompt(
        targetLanguageName: String,
        sourceLanguageName: String
    ): String {
        val sourceClause = rawAssSsaSourceClause(targetLanguageName, sourceLanguageName)
        return """
            You are ASS_SSA_SUBTITLE_TRANSLATOR.

            target_language = $targetLanguageName
            $sourceClause

            TASK
            Translate only visible natural-language subtitle text into target_language. Preserve all ASS/SSA syntax byte-exact. Output only the translated result in the same container/shape as the input. No explanations, notes, Markdown, or extra text.

            INPUT
            Input may be raw ASS/SSA Text fields, raw ASS/SSA Dialogue/Comment event lines, or multi-line ASS/SSA chunks.

            If input is raw, output only the translated raw string.

            TRANSLATION STYLE
            Translate naturally and concisely for subtitles. Preserve meaning, tone, names, speaker intent, punctuation where natural, and line rhythm. Do not summarize, explain, add notes, censor, or expand unnecessarily.

            PROTECTED ASS/SSA SYNTAX — COPY EXACTLY
            Translate only human-language text outside protected syntax and outside drawing mode.

            1. Special text escapes outside override blocks are protected:
            \N \n \h
            Copy them exactly. Do not change case. Do not replace with real newlines or spaces.

            2. Override blocks are protected:
            Any substring from { to } is an ASS/SSA override block. Copy complete override blocks exactly unless moving a whole inline block is clearly necessary to keep styling on the equivalent translated word/phrase. Never edit characters inside an override block. Never translate anything inside braces, including comments or unknown text.

            3. Known ASS/SSA override tags to protect inside braces:
            \i \b \u \s
            \fn \fs \fe
            \bord \xbord \ybord
            \shad \xshad \yshad
            \be \blur
            \fscx \fscy \fsp
            \frx \fry \frz \fr
            \fax \fay
            \c \1c \2c \3c \4c
            \alpha \1a \2a \3a \4a
            \an \a \q \r
            \k \K \kf \ko \kt
            \pos \move \org
            \fad \fade \t
            \clip \iclip
            \p \pbo

            4. Protect all tag parameters:
            Numbers, signs, decimals, commas, parentheses, font names, style names, charset/encoding values, &H...& color/alpha values, rectangular coordinates, vector drawing arguments, and nested style modifiers inside \t(...). Do not normalize or reinterpret them.

            5. Drawing mode:
            If an override block contains \p followed by an integer greater than 0, drawing mode is ON. All following non-brace text is vector drawing data, not language, until a later override block containing \p0 turns drawing mode OFF. In drawing mode, copy all non-brace text exactly. Protect drawing commands:
            m n l b s p c
            and all coordinates, spaces, signs, and decimals.

            6. Vector clips:
            Everything inside vector \clip(...) or \iclip(...) is protected, including drawing commands m n l b s p c and coordinates. Rectangular \clip/\iclip coordinates are also protected.

            7. Full ASS/SSA event lines:
            If input is a full Dialogue event line, keep every non-Text field byte-exact: event type, Layer/Marked, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, commas, timing, spacing. Translate only the Text field. If you cannot confidently identify the Text field, return the input unchanged.
            If input is a non-dialogue ASS/SSA line such as Format, Style, Comment, Picture, Sound, Movie, Command, header, section, font, or graphics data, copy it unchanged.

            PLACEMENT RULES
            Preserve every protected item exactly once and in the same relative order. You may move whole inline formatting blocks only when needed to preserve equivalent emphasis/styling after translation. Never move or modify line-level positioning/effect blocks such as \pos, \move, \org, \clip, \iclip, \fad, \fade, \an, \a, or \q except by copying them exactly as originally placed.

            FORBIDDEN
            Do not add new ASS/SSA tags.
            Do not remove tags.
            Do not rewrite tags.
            Do not convert ASS/SSA to HTML, XML, Markdown, SRT, or plain text.
            Do not alter braces, backslashes, tag case, field commas, timing, colors, alpha, font names, style names, drawing commands, or protected whitespace.
            Do not translate text inside {...}.
            Do not translate drawing data.
            Do not output explanations.

            FAIL-SAFE
            If preserving ASS/SSA syntax conflicts with translation, preserve syntax first.
            If unsure whether something is text or syntax, copy it unchanged.
            If the input is malformed or cannot be safely translated, return the original text unchanged.
        """.trimIndent()
    }

    private fun buildRawSubRipUserPayload(
        rawSubRip: String,
        targetLanguageName: String
    ): String {
        val firstLine = rawSubRip.lineSequence().firstOrNull().orEmpty()
        return """
            TARGET LANGUAGE: $targetLanguageName
            FIRST OUTPUT LINE MUST BE EXACTLY: $firstLine
            Translate the raw SRT batch below. Return translated SRT only.
            Do not add any line before the first cue number.

            ${rawSubRip.trim()}
        """.trimIndent()
    }

    private fun buildRawSubRipSystemPrompt(
        targetLanguageName: String,
        sourceLanguageName: String
    ): String {
        val sourceClause = rawSubRipSourceClause(targetLanguageName, sourceLanguageName)
        return """
            You are SRT_TRANSLATION_ENGINE.

            $sourceClause

            Your task is to translate raw SRT subtitle content into $targetLanguageName while preserving valid SRT format exactly.

            OUTPUT RULE
            Return only the translated SRT content.
            Do not return explanations, comments, summaries, Markdown, code fences, JSON, warnings, or notes.
            Do not write any preface such as "Here is", "Here's", "Translated SRT", or "Below".
            The first output line must be the first cue number from the input batch.
            The first character of the response must be that cue number's first digit.

            CORE RULE
            Translate only human-readable subtitle text.
            Preserve all SRT structure and formatting exactly.

            SRT STRUCTURE TO PRESERVE
            Each SRT cue may contain:
            1. cue number
            2. timestamp line
            3. one or more subtitle text lines
            4. blank line separator

            You must preserve:
            - every cue number exactly
            - cue order exactly
            - every timestamp line exactly
            - all blank lines between cues
            - all cue separators
            - all non-dialogue structure
            - all formatting tags
            - all metadata or cue settings
            - all URLs, codes, filenames, handles, hashtags, and IDs

            Never add, remove, merge, split, renumber, or reorder cues.
            Never change timestamps.
            Never change the number of cues.
            Never change a cue number.
            Never remove a blank line between cues.

            CUE FRAGMENT RULE
            Some sentences are intentionally split across multiple cue blocks.
            Translate each cue block independently and keep its translated fragment inside the same cue.
            Do not repair a sentence by moving words into an earlier or later cue.
            Do not combine adjacent sentence fragments into one cue.

            TIMESTAMP LINES
            Timestamp lines contain "-->" and must be copied exactly.
            Never alter start time, end time, milliseconds, commas or periods, the --> arrow, spaces around the arrow, coordinates, cue settings, or trailing timestamp metadata.

            FORMATTING TAGS
            Preserve all tags exactly.
            Preserve common SRT tags such as <i>, </i>, <b>, </b>, <u>, </u>, <s>, </s>, <font color="...">, </font>, <br>, and <br/>.
            Preserve any unknown HTML/XML-like tag exactly.
            Never translate, rewrite, remove, duplicate, normalize, or invent tags.
            Never change tag names, attributes, colors, quotes, angle brackets, slashes, or equals signs.
            Color names inside tag attributes are code, not language. Never translate attribute values such as "yellow", "blue", "red", "green", "white", or "black".
            Translate only visible human text outside tags.

            TAG PLACEMENT
            Keep tags in the same relative order.
            Preserve tag multiplicity.
            If the same tags appear on two subtitle text lines, keep both tagged lines.
            Do not collapse two tagged lines into one tagged line.
            For each subtitle text line, preserve that line's tag sequence on that same line.

            DIALOGUE MARKERS AND SPEAKERS
            Preserve dialogue dashes and speaker labels.
            Dialogue dashes are punctuation, not protected text.
            For lines that start with "-", "–", or "—", preserve the dash and translate the dialogue after the dash.
            For two-speaker lines, translate both speakers independently.
            Synthetic speaker labels are structure, not dialogue.
            Preserve labels like "- Speaker 1:" and "- Speaker 2:" exactly, including the English word "Speaker".
            Translate only the dialogue after those labels.
            Never output localized forms such as "- Spreker 1:" or "- Spreker 2:".
            Preserve every speaker name or label exactly.

            TRANSLATION STYLE
            Translate naturally for subtitles.
            Use concise spoken language.
            Preserve meaning, tone, emotion, register, humor, intent, and character voice.
            Do not translate word-for-word when a natural subtitle translation is better.
            Translate idioms and common subtitle phrases into idiomatic target-language subtitles.
            For Dutch, use natural Netherlands Dutch such as "Kom op" for "Come on" and "Kijk uit" for "Watch it" when context fits.
            Do not summarize, add information, omit meaning, censor, or explain jokes.

            LINE BREAKS
            Preserve the existing number of subtitle text lines in every cue exactly.
            If a cue has two subtitle text lines, output two subtitle text lines.
            If a cue has three subtitle text lines, output three subtitle text lines.
            Never collapse multiple subtitle text lines into one line.
            Never split one subtitle text line into multiple lines.
            Do not move text between cues.
            Do not split one cue into multiple cues.
            Do not merge multiple cues.
            Structure preservation has priority over natural line wrapping.
            Translate line-by-line.
            Each source subtitle text line maps to exactly one output subtitle text line.
            If a source text line is an unnatural fragment, output an unnatural translated fragment rather than combining it with the next line.

            MUSIC, SOUNDS, AND CAPTIONS
            Translate meaningful sound or caption descriptions while preserving brackets, parentheses, music symbols, names inside descriptions, and formatting around descriptions.

            FAIL-SAFE
            If a line is clearly SRT structure, copy it exactly.
            If unsure whether something is subtitle text or structure, preserve it unchanged.
            If a cue is malformed, translate only obvious subtitle text and preserve everything else.
            If safe translation is impossible, return the original cue unchanged.

            VALIDATION TARGET
            Your output is automatically rejected unless all of these are true:
            1. Cue count equals input cue count.
            2. Cue numbers equal input cue numbers in the same order.
            3. Timestamp lines equal input timestamp lines exactly.
            4. Each cue has the same number of subtitle text lines as the input cue.
            5. Every HTML/XML-like tag token appears exactly as in the input cue.
            6. Speaker labels such as "NARRATOR:", "[JAKE]:", "- Speaker 1:", and "- Speaker 2:" remain unchanged.

            Return only the translated SRT.
        """.trimIndent()
    }

    private fun parseProtectedAssSsaResponse(
        responseText: String,
        units: List<AssSsaProtectedTranslationUnit>
    ): Map<String, String> {
        val normalized = sanitizeJsonResponse(responseText)
        val array = when {
            normalized.startsWith("[") -> JSONArray(normalized)
            normalized.startsWith("{") -> {
                val obj = JSONObject(normalized)
                obj.optJSONArray("items")
                    ?: if (obj.has("id") && obj.has("text")) JSONArray().put(obj) else JSONArray()
            }
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

    private fun sanitizeRawAssSsaResponse(responseText: String): String {
        return sanitizeRawSubtitleResponse(responseText)
    }

    private fun sanitizeRawSubtitleResponse(responseText: String): String {
        val trimmed = responseText.trim()
        val unfenced = if (trimmed.startsWith("```")) {
            trimmed
                .replaceFirst(Regex("""^```[A-Za-z0-9_-]*\s*"""), "")
                .replace(Regex("""\s*```\s*$"""), "")
                .trim()
        } else {
            trimmed
        }
        return unfenced
            .replace("\r\n", "\n")
            .replace('\r', '\n')
    }

    private fun validateRawSubRipTranslation(
        source: List<RawSubRipCue>,
        translated: String
    ): Result<Unit> = runCatching {
        val translatedCues = parseRawSubRipCues(translated)
        if (translatedCues.size != source.size) {
            throw IllegalStateException("Raw SRT translation changed cue count.")
        }
        source.zip(translatedCues).forEach { (sourceCue, translatedCue) ->
            if (translatedCue.number != sourceCue.number) {
                throw IllegalStateException("Raw SRT translation changed cue number ${sourceCue.number}.")
            }
            if (translatedCue.timestamp != sourceCue.timestamp) {
                throw IllegalStateException("Raw SRT translation changed timestamp for cue ${sourceCue.number}.")
            }
            if (translatedCue.textLines.size != sourceCue.textLines.size) {
                throw IllegalStateException("Raw SRT translation changed text line count for cue ${sourceCue.number}.")
            }
            if (translatedCue.tags != sourceCue.tags) {
                throw IllegalStateException("Raw SRT translation changed tag sequence for cue ${sourceCue.number}.")
            }
        }
    }

    private fun validateRawAssSsaTranslation(
        source: String,
        translated: String
    ): Result<Unit> = runCatching {
        val sourceLines = source.lines()
        val translatedLines = translated.lines()
        if (sourceLines.size != translatedLines.size) {
            throw IllegalStateException("Raw ASS/SSA translation changed line count.")
        }
        val format = AssSsaEventFormat.standardDialogue()
        sourceLines.zip(translatedLines).forEachIndexed { index, (sourceLine, translatedLine) ->
            val sourceRecord = AssSsaEventRecord.parseDialogueLine(sourceLine, format)
            if (sourceRecord == null) {
                if (sourceLine != translatedLine) {
                    throw IllegalStateException("Raw ASS/SSA translation changed non-event line ${index + 1}.")
                }
                return@forEachIndexed
            }
            val translatedRecord = AssSsaEventRecord.parseDialogueLine(translatedLine, format)
                ?: throw IllegalStateException("Raw ASS/SSA translation broke event line ${index + 1}.")
            if (sourceRecord.prefix != translatedRecord.prefix) {
                throw IllegalStateException("Raw ASS/SSA translation changed event prefix on line ${index + 1}.")
            }
            sourceRecord.values.forEachIndexed { fieldIndex, sourceValue ->
                if (fieldIndex != format.textIndex && sourceValue != translatedRecord.values.getOrNull(fieldIndex)) {
                    val fieldName = format.fields.getOrNull(fieldIndex).orEmpty()
                    throw IllegalStateException("Raw ASS/SSA translation changed $fieldName on line ${index + 1}.")
                }
            }
            validateRawAssSsaTextSyntax(
                source = sourceRecord.text,
                translated = translatedRecord.text,
                lineNumber = index + 1
            )
        }
    }

    private fun validateRawAssSsaTextSyntax(
        source: String,
        translated: String,
        lineNumber: Int
    ) {
        val sourceTokens = AssSsaTextTokenizer.tokenize(source)
        val translatedTokens = AssSsaTextTokenizer.tokenize(translated)
        if (sourceTokens.protectedRawSequence() != translatedTokens.protectedRawSequence()) {
            throw IllegalStateException("Raw ASS/SSA translation changed protected syntax on line $lineNumber.")
        }
        val introducedRawSyntax = RAW_ASS_TRANSLATION_SYNTAX_PATTERN.findAll(translated)
            .map { it.value }
            .filterNot { syntax -> RAW_ASS_TRANSLATION_SYNTAX_PATTERN.findAll(source).any { it.value == syntax } }
            .toList()
        if (introducedRawSyntax.isNotEmpty()) {
            throw IllegalStateException("Raw ASS/SSA translation introduced ASS syntax on line $lineNumber.")
        }
    }

    private fun List<AssSsaTextToken>.protectedRawSequence(): List<String> {
        return mapNotNull { token ->
            when (token) {
                is AssSsaTextToken.OverrideBlock,
                is AssSsaTextToken.LineBreak,
                is AssSsaTextToken.HardSpace,
                is AssSsaTextToken.Drawing,
                is AssSsaTextToken.Malformed -> token.raw
                is AssSsaTextToken.Text -> null
            }
        }
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

private fun rawSubRipSourceClause(
    targetLanguageName: String,
    sourceLanguageName: String
): String {
    return if (sourceLanguageName.equals("auto", ignoreCase = true)) {
        "The source language is unknown — detect it automatically from the cue text and translate to $targetLanguageName."
    } else {
        "Translate from $sourceLanguageName to $targetLanguageName."
    }
}

private fun rawAssSsaSourceClause(
    targetLanguageName: String,
    sourceLanguageName: String
): String {
    return if (sourceLanguageName.equals("auto", ignoreCase = true)) {
        "source_language = unknown — detect it automatically from the cue text and translate to $targetLanguageName."
    } else {
        "source_language = $sourceLanguageName — translate to $targetLanguageName."
    }
}
