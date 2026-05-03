package com.nexio.tv.data.integration.collector.transport

import javax.inject.Inject
import javax.inject.Named
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class ShadowAutoplayUploadTransportResult(
    val statusCode: Int,
    val isSuccessful: Boolean
)

class ShadowAutoplayUploadTransport @Inject constructor(
    @Named("benchmark") private val okHttpClient: OkHttpClient
) {
    suspend fun uploadEvent(
        baseUrl: String,
        token: String,
        envelopeJson: String
    ): ShadowAutoplayUploadTransportResult {
        val request = Request.Builder()
            .url("$baseUrl/api/v1/shadow-autoplay-events")
            .header("Authorization", "Bearer $token")
            .post(envelopeJson.toRequestBody("application/json".toMediaType()))
            .build()

        return okHttpClient.newCall(request).awaitShadowUploadResponse()
    }

    suspend fun uploadDeviceCapabilityReport(
        baseUrl: String,
        token: String,
        envelopeJson: String
    ): ShadowAutoplayUploadTransportResult {
        val request = Request.Builder()
            .url("$baseUrl/api/v1/device-capability-reports")
            .header("Authorization", "Bearer $token")
            .post(envelopeJson.toRequestBody("application/json".toMediaType()))
            .build()

        return okHttpClient.newCall(request).awaitShadowUploadResponse()
    }
}

private suspend fun Call.awaitShadowUploadResponse(): ShadowAutoplayUploadTransportResult =
    withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { cancel() }

            try {
                execute().use { response ->
                    val result = ShadowAutoplayUploadTransportResult(
                        statusCode = response.code,
                        isSuccessful = response.isSuccessful
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
