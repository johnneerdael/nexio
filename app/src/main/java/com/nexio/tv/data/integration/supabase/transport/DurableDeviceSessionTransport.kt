package com.nexio.tv.data.integration.supabase.transport

import com.nexio.tv.BuildConfig
import com.nexio.tv.data.remote.supabase.DurableDeviceSessionExchangeResult
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class DurableDeviceSessionTransport private constructor(
    private val okHttpClient: OkHttpClient,
    private val supabaseUrl: String,
    private val supabaseAnonKey: String,
    private val json: Json
) {
    @Inject
    constructor(
        okHttpClient: OkHttpClient
    ) : this(
        okHttpClient = okHttpClient,
        supabaseUrl = BuildConfig.SUPABASE_URL,
        supabaseAnonKey = BuildConfig.SUPABASE_ANON_KEY,
        json = Json { ignoreUnknownKeys = true }
    )

    suspend fun exchangeSession(
        devicePublicId: String,
        deviceSecret: String
    ): DurableDeviceSessionExchangeResult = withContext(Dispatchers.IO) {
        val payload = buildJsonObject {
            put("device_public_id", devicePublicId)
            put("device_secret", deviceSecret)
        }.toString()

        val request = Request.Builder()
            .url("${supabaseUrl.trimEnd('/')}/functions/v1/device-session-exchange")
            .header("apikey", supabaseAnonKey)
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw DurableDeviceSessionExchangeException(
                    statusCode = response.code,
                    responseBody = responseBody
                )
            }
            json.decodeFromString<DurableDeviceSessionExchangeResult>(responseBody)
        }
    }

    internal companion object {
        internal fun forTest(
            okHttpClient: OkHttpClient,
            supabaseUrl: String,
            supabaseAnonKey: String,
            json: Json = Json { ignoreUnknownKeys = true }
        ): DurableDeviceSessionTransport = DurableDeviceSessionTransport(
            okHttpClient = okHttpClient,
            supabaseUrl = supabaseUrl,
            supabaseAnonKey = supabaseAnonKey,
            json = json
        )
    }
}

class DurableDeviceSessionExchangeException(
    val statusCode: Int,
    val responseBody: String
) : IllegalStateException("Durable device session exchange failed ($statusCode): $responseBody")
