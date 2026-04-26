package com.nexio.tv.data.integration.imdb.transport

import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object CustomImdbRatingsRequests {
    fun validateStats(baseUrl: String, apiKey: String): Request {
        return Request.Builder()
            .url(buildCustomImdbUrl(baseUrl, "meta/stats"))
            .header("X-API-Key", apiKey.trim())
            .get()
            .build()
    }

    fun episodeRatings(baseUrl: String, apiKey: String, tconst: String): Request {
        val endpoint = buildCustomImdbUrl(baseUrl, "ratings/${tconst.trim()}")
            .toHttpUrl()
            .newBuilder()
            .addQueryParameter("episodes", "true")
            .build()

        return Request.Builder()
            .url(endpoint)
            .header("X-API-Key", apiKey.trim())
            .get()
            .build()
    }

    fun bulkTitleRatings(baseUrl: String, apiKey: String, bodyJson: String): Request {
        return Request.Builder()
            .url(buildCustomImdbUrl(baseUrl, "ratings/bulk"))
            .header("X-API-Key", apiKey.trim())
            .post(bodyJson.toRequestBody("application/json".toMediaType()))
            .build()
    }

    private fun buildCustomImdbUrl(baseUrl: String, pathAfterVersion: String): String {
        val normalizedBaseUrl = baseUrl.trim().trimEnd('/')
        val normalizedPath = pathAfterVersion.trimStart('/')
        val hasVersionPath = normalizedBaseUrl.lowercase().endsWith("/v1")

        return if (hasVersionPath) {
            "$normalizedBaseUrl/$normalizedPath"
        } else {
            "$normalizedBaseUrl/v1/$normalizedPath"
        }
    }
}

data class CustomImdbTransportResult(
    val statusCode: Int,
    val isSuccessful: Boolean,
    val body: String = "",
    val retryAfterMs: Long? = null
)

class CustomImdbRatingsTransport @Inject constructor(
    private val okHttpClient: OkHttpClient
) {
    suspend fun execute(request: Request): CustomImdbTransportResult =
        okHttpClient.newCall(request).awaitCustomImdbResponse()
}

private suspend fun Call.awaitCustomImdbResponse(): CustomImdbTransportResult =
    withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { cancel() }

            try {
                execute().use { response ->
                    val result = CustomImdbTransportResult(
                        statusCode = response.code,
                        isSuccessful = response.isSuccessful,
                        body = response.body?.string().orEmpty(),
                        retryAfterMs = response.retryAfterDelayMs()
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

private fun okhttp3.Response.retryAfterDelayMs(): Long? {
    if (code != 429) return null

    val seconds = header("Retry-After")?.trim()?.toLongOrNull()
    return if (seconds != null && seconds >= 0L) {
        seconds * 1_000L
    } else {
        1_000L
    }
}
