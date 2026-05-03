package com.nexio.tv.data.integration.supabase.transport

import com.nexio.tv.BuildConfig
import com.nexio.tv.data.remote.supabase.DurableDeviceCredentialSelfServiceResult
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

class DurableDeviceCredentialSelfServiceTransport private constructor(
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

    suspend fun status(
        devicePublicId: String,
        deviceSecret: String
    ): DurableDeviceCredentialSelfServiceResult = execute(
        devicePublicId = devicePublicId,
        deviceSecret = deviceSecret,
        action = "status"
    )

    suspend fun revoke(
        devicePublicId: String,
        deviceSecret: String
    ): DurableDeviceCredentialSelfServiceResult = execute(
        devicePublicId = devicePublicId,
        deviceSecret = deviceSecret,
        action = "revoke"
    )

    private suspend fun execute(
        devicePublicId: String,
        deviceSecret: String,
        action: String
    ): DurableDeviceCredentialSelfServiceResult = withContext(Dispatchers.IO) {
        val payload = buildJsonObject {
            put("device_public_id", devicePublicId)
            put("device_secret", deviceSecret)
            put("action", action)
        }.toString()

        val request = Request.Builder()
            .url("${supabaseUrl.trimEnd('/')}/functions/v1/device-credential-self-service")
            .header("apikey", supabaseAnonKey)
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw DurableDeviceCredentialSelfServiceException(
                    statusCode = response.code,
                    responseBody = responseBody
                )
            }
            json.decodeFromString<DurableDeviceCredentialSelfServiceResult>(responseBody)
        }
    }

    internal companion object {
        internal fun forTest(
            okHttpClient: OkHttpClient,
            supabaseUrl: String,
            supabaseAnonKey: String,
            json: Json = Json { ignoreUnknownKeys = true }
        ): DurableDeviceCredentialSelfServiceTransport = DurableDeviceCredentialSelfServiceTransport(
            okHttpClient = okHttpClient,
            supabaseUrl = supabaseUrl,
            supabaseAnonKey = supabaseAnonKey,
            json = json
        )
    }
}

class DurableDeviceCredentialSelfServiceException(
    val statusCode: Int,
    val responseBody: String
) : IllegalStateException("Durable credential self-service failed ($statusCode): $responseBody")
