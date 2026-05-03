package com.nexio.tv.data.integration.supabase.transport

import com.nexio.tv.BuildConfig
import com.nexio.tv.data.remote.supabase.DurableDeviceCredentialIssueResult
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

class TvLoginExchangeTransport private constructor(
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

    internal suspend fun exchange(
        token: String,
        code: String,
        deviceNonce: String
    ): DurableDeviceCredentialIssueResult = withContext(Dispatchers.IO) {
        val payload = buildJsonObject {
            put("code", code)
            put("device_nonce", deviceNonce)
        }.toString()

        val request = Request.Builder()
            .url("${supabaseUrl.trimEnd('/')}/functions/v1/tv-logins-exchange")
            .header("apikey", supabaseAnonKey)
            .header("Authorization", "Bearer $token")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException("TV login exchange failed (${response.code}): $responseBody")
            }
            json.decodeFromString<DurableDeviceCredentialIssueResult>(responseBody)
        }
    }

    internal companion object {
        internal fun forTest(
            okHttpClient: OkHttpClient,
            supabaseUrl: String,
            supabaseAnonKey: String,
            json: Json = Json { ignoreUnknownKeys = true }
        ): TvLoginExchangeTransport = TvLoginExchangeTransport(
            okHttpClient = okHttpClient,
            supabaseUrl = supabaseUrl,
            supabaseAnonKey = supabaseAnonKey,
            json = json
        )
    }
}

internal suspend fun TvLoginExchangeTransport.exchange(
    code: String,
    deviceNonce: String,
    currentAccessToken: String
): DurableDeviceCredentialIssueResult = exchange(
    token = currentAccessToken,
    code = code,
    deviceNonce = deviceNonce
)
