package com.nexio.tv.data.integration.supabase.transport

import com.nexio.tv.BuildConfig
import com.nexio.tv.data.remote.supabase.DurableDeviceCredentialActivationResult
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

class DurableDeviceCredentialActivationTransport private constructor(
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

    suspend fun activate(
        token: String,
        devicePublicId: String,
        deviceSecret: String
    ): DurableDeviceCredentialActivationResult = withContext(Dispatchers.IO) {
        val payload = buildJsonObject {
            put("device_public_id", devicePublicId)
            put("device_secret", deviceSecret)
        }.toString()

        val request = Request.Builder()
            .url("${supabaseUrl.trimEnd('/')}/functions/v1/device-credential-activate")
            .header("apikey", supabaseAnonKey)
            .header("Authorization", "Bearer $token")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException("Device credential activation failed (${response.code}): $responseBody")
            }
            val result = json.decodeFromString<DurableDeviceCredentialActivationResult>(responseBody)
            check(result.activated) { "Device credential activation did not succeed" }
            result
        }
    }

    internal companion object {
        internal fun forTest(
            okHttpClient: OkHttpClient,
            supabaseUrl: String,
            supabaseAnonKey: String,
            json: Json = Json { ignoreUnknownKeys = true }
        ): DurableDeviceCredentialActivationTransport = DurableDeviceCredentialActivationTransport(
            okHttpClient = okHttpClient,
            supabaseUrl = supabaseUrl,
            supabaseAnonKey = supabaseAnonKey,
            json = json
        )
    }
}
