package com.nexio.tv.data.repository.benchmark

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.google.gson.JsonObject
import com.nexio.tv.BuildConfig
import com.nexio.tv.data.local.PlayerSettingsDataStore
import com.nexio.tv.data.repository.device.DeviceCapabilityRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

private const val TAG = "DeviceCapabilityUpload"

@Singleton
class DeviceCapabilityReportUploader internal constructor(
    private val playerSettingsDataStore: PlayerSettingsDataStore,
    private val deviceCapabilityRepository: DeviceCapabilityRepository,
    private val okHttpClient: OkHttpClient,
    private val baseUrlProvider: () -> String,
    private val tokenProvider: () -> String,
    private val clientInfoProvider: () -> JsonObject
) {
    private val submitted = AtomicBoolean(false)

    @Inject
    constructor(
        @ApplicationContext context: Context,
        playerSettingsDataStore: PlayerSettingsDataStore,
        deviceCapabilityRepository: DeviceCapabilityRepository,
        okHttpClient: OkHttpClient
    ) : this(
        playerSettingsDataStore = playerSettingsDataStore,
        deviceCapabilityRepository = deviceCapabilityRepository,
        okHttpClient = okHttpClient,
        baseUrlProvider = { BuildConfig.SHADOW_DATA_COLLECTION_BASE_URL.trim().trimEnd('/') },
        tokenProvider = { BuildConfig.SHADOW_DATA_COLLECTION_WRITE_TOKEN.trim() },
        clientInfoProvider = { buildCapabilityCollectorClientInfo(context) }
    )

    suspend fun submitOnceIfEnabled() {
        if (submitted.get()) return
        val settings = playerSettingsDataStore.playerSettings.first()
        if (!settings.shadowAutoplayDataCollectionEnabled) return
        val baseUrl = baseUrlProvider()
        val token = tokenProvider()
        if (baseUrl.isBlank() || token.isBlank()) return
        val snapshot = deviceCapabilityRepository.snapshotForAutoplay() ?: return
        if (!submitted.compareAndSet(false, true)) return

        val envelope = JsonObject().apply {
            addProperty("sentAtMs", System.currentTimeMillis())
            add("client", clientInfoProvider())
            add("report", DeviceCapabilitySnapshotSerializer.toJson(snapshot))
        }.toString()

        withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder()
                    .url("$baseUrl/api/v1/device-capability-reports")
                    .header("Authorization", "Bearer $token")
                    .post(envelope.toRequestBody("application/json".toMediaType()))
                    .build()
                okHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        submitted.set(false)
                        Log.w(TAG, "Upload failed code=${response.code}")
                    }
                }
            }.onFailure { error ->
                submitted.set(false)
                Log.w(TAG, "Upload failed: ${error.message}")
            }
        }
    }
}

private fun buildCapabilityCollectorClientInfo(context: Context): JsonObject {
    return JsonObject().apply {
        addProperty("appVersion", BuildConfig.VERSION_NAME)
        addProperty("buildType", if (BuildConfig.IS_DEBUG_BUILD) "debug" else "release")
        addProperty("deviceModel", Build.MODEL)
        addProperty("sdkInt", Build.VERSION.SDK_INT)
        runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull()?.takeIf { it.isNotBlank() }?.let { addProperty("androidId", it) }
    }
}
