package com.nexio.tv.data.integration.subtitles.transport

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val ANTHROPIC_VERSION = "2023-06-01"

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

internal fun geminiRequest(endpoint: String, apiKey: String, body: JSONObject): Request {
    return Request.Builder()
        .url(endpoint)
        .header("x-goog-api-key", apiKey)
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

data class SubtitleTranslationTransportResult(
    val statusCode: Int,
    val isSuccessful: Boolean,
    val body: String?,
    val retryAfterHeader: String?,
    val retryAfterHeaderMs: Long?
)

class SubtitleTranslationTransport @Inject constructor(
    private val okHttpClient: OkHttpClient
) {
    suspend fun execute(request: Request): SubtitleTranslationTransportResult =
        okHttpClient.newCall(request).awaitSubtitleTranslationResponse()
}

private suspend fun Call.awaitSubtitleTranslationResponse(): SubtitleTranslationTransportResult =
    withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { cancel() }

            try {
                execute().use { response ->
                    val result = SubtitleTranslationTransportResult(
                        statusCode = response.code,
                        isSuccessful = response.isSuccessful,
                        body = response.body?.string()?.ifEmpty { null },
                        retryAfterHeader = response.header("Retry-After"),
                        retryAfterHeaderMs = response.parseRetryAfterHeaderMs()
                    )
                    if (continuation.isActive) {
                        continuation.resume(result)
                    }
                }
            } catch (exception: Exception) {
                if (continuation.isActive) {
                    continuation.resumeWithException(exception)
                }
            }
        }
    }

private fun Response.parseRetryAfterHeaderMs(): Long? {
    val header = header("Retry-After") ?: return null

    val parsedSeconds = header.trim().toLongOrNull()
    if (parsedSeconds != null) {
        return (parsedSeconds * 1_000L).coerceAtLeast(0L)
    }

    val parsedDateMs = runCatching {
        val retryAtMs = ZonedDateTime.parse(header.trim(), DateTimeFormatter.RFC_1123_DATE_TIME)
            .toInstant()
            .toEpochMilli()
        retryAtMs - System.currentTimeMillis()
    }.getOrNull() ?: return null

    return parsedDateMs.coerceAtLeast(0L)
}
