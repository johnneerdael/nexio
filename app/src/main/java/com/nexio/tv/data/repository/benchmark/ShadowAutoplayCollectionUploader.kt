package com.nexio.tv.data.repository.benchmark

import android.os.Build
import android.util.Log
import com.google.gson.JsonObject
import com.nexio.tv.BuildConfig
import com.nexio.tv.data.local.PlayerSettingsDataStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

private const val TAG = "ShadowAutoplayUpload"

@Singleton
class ShadowAutoplayCollectionUploader @Inject constructor(
    private val playerSettingsDataStore: PlayerSettingsDataStore,
    private val okHttpClient: OkHttpClient,
    private val logger: ShadowAutoPlayDecisionLogger
) {
    suspend fun submitIfEnabled(event: ShadowAutoPlayDecisionEvent) {
        val settings = playerSettingsDataStore.playerSettings.first()
        if (!settings.shadowAutoplayDataCollectionEnabled) return
        val baseUrl = BuildConfig.SHADOW_DATA_COLLECTION_BASE_URL.trim().trimEnd('/')
        val token = BuildConfig.SHADOW_DATA_COLLECTION_WRITE_TOKEN.trim()
        if (baseUrl.isBlank() || token.isBlank()) return

        val payloadJson = logger.encode(event)
        val envelope = JsonObject().apply {
            addProperty("sentAtMs", System.currentTimeMillis())
            add("client", JsonObject().apply {
                addProperty("appVersion", BuildConfig.VERSION_NAME)
                addProperty("buildType", if (BuildConfig.IS_DEBUG_BUILD) "debug" else "release")
                addProperty("deviceModel", Build.MODEL)
                addProperty("sdkInt", Build.VERSION.SDK_INT)
            })
            add("payload", com.google.gson.JsonParser.parseString(payloadJson))
        }.toString()

        withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder()
                    .url("$baseUrl/api/v1/shadow-autoplay-events")
                    .header("Authorization", "Bearer $token")
                    .post(envelope.toRequestBody("application/json".toMediaType()))
                    .build()
                okHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.w(TAG, "Upload failed code=${response.code}")
                    }
                }
            }.onFailure { error ->
                Log.w(TAG, "Upload failed: ${error.message}")
            }
        }
    }
}
