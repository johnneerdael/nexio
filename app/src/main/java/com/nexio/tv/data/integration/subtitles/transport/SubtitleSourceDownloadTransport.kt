package com.nexio.tv.data.integration.subtitles.transport

import okhttp3.OkHttpClient
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class SubtitleSourceDownloadTransportResult(
    val statusCode: Int,
    val isSuccessful: Boolean,
    val body: String?,
    val bytes: ByteArray?
)

class SubtitleSourceDownloadTransport @Inject constructor(
    private val okHttpClient: OkHttpClient
) {
    suspend fun executeUrl(
        url: String,
        headers: Map<String, String> = emptyMap()
    ): SubtitleSourceDownloadTransportResult {
        val requestBuilder = Request.Builder()
            .url(url)
            .get()
        headers.forEach { (name, value) ->
            if (name.isNotBlank() && value.isNotBlank()) {
                requestBuilder.header(name, value)
            }
        }
        return execute(requestBuilder.build())
    }

    suspend fun execute(request: Request): SubtitleSourceDownloadTransportResult {
        return okHttpClient.newCall(request).awaitSubtitleResponse()
    }
}

private suspend fun Call.awaitSubtitleResponse(): SubtitleSourceDownloadTransportResult =
    suspendCancellableCoroutine { continuation ->
        val completed = AtomicBoolean(false)

        continuation.invokeOnCancellation {
            completed.compareAndSet(false, true)
            cancel()
        }

        enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) {
                if (completed.compareAndSet(false, true)) {
                    continuation.resumeWithException(e)
                }
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    try {
                        val bytes = response.body?.bytes()
                        if (completed.compareAndSet(false, true)) {
                            continuation.resume(
                                SubtitleSourceDownloadTransportResult(
                                    statusCode = response.code,
                                    isSuccessful = response.isSuccessful,
                                    body = bytes?.toString(StandardCharsets.UTF_8),
                                    bytes = bytes
                                )
                            )
                        }
                    } catch (exception: Exception) {
                        if (completed.compareAndSet(false, true)) {
                            continuation.resumeWithException(exception)
                        }
                    }
                }
            }
        })
    }
