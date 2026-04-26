package com.nexio.tv.data.integration.simkl.transport

import com.nexio.tv.data.remote.SimklRequestGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Named
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlin.coroutines.cancellation.CancellationException

data class SimklProgressTransportResult(
    val statusCode: Int,
    val isSuccessful: Boolean,
    val body: String?
)

class SimklProgressTransport @Inject constructor(
    @Named("simkl") private val okHttpClient: OkHttpClient,
    private val requestGate: SimklRequestGate
) {
    suspend fun executeRequest(request: Request): SimklProgressTransportResult = withContext(Dispatchers.IO) {
        requestGate.acquire {
            okHttpClient.newCall(request).execute().use { response ->
                SimklProgressTransportResult(
                    statusCode = response.code,
                    isSuccessful = response.isSuccessful,
                    body = response.body?.string()
                )
            }
        }
    }

    suspend fun executeRequest(
        url: String,
        accessToken: String,
        method: String = "GET"
    ): String? = withContext(Dispatchers.IO) {
        val requestUrl = url.toHttpUrlOrNull() ?: return@withContext null
        val request = Request.Builder()
            .url(requestUrl)
            .header("Authorization", "Bearer $accessToken")
            .run { if (method == "POST") post(ByteArray(0).toRequestBody(null)) else get() }
            .build()

        try {
            val result = executeRequest(request)
            if (!result.isSuccessful) return@withContext null
            result.body?.takeIf { it.isNotBlank() }
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            null
        }
    }
}
