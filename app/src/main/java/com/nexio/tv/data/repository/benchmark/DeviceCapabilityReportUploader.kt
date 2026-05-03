package com.nexio.tv.data.repository.benchmark

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.google.gson.JsonObject
import com.nexio.tv.BuildConfig
import com.nexio.tv.core.integration.IntegrationCallResult
import com.nexio.tv.data.integration.collector.ShadowAutoplayUploadIntegrationProvider
import com.nexio.tv.data.local.PlayerSettingsDataStore
import com.nexio.tv.data.repository.device.DeviceCapabilityRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

private const val DEVICE_CAPABILITY_UPLOAD_TAG = "DeviceCapabilityUpload"

@Singleton
class DeviceCapabilityReportUploader internal constructor(
    private val playerSettingsDataStore: PlayerSettingsDataStore,
    private val deviceCapabilityRepository: DeviceCapabilityRepository,
    private val uploadIntegrationProvider: ShadowAutoplayUploadIntegrationProvider,
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
        uploadIntegrationProvider: ShadowAutoplayUploadIntegrationProvider
    ) : this(
        playerSettingsDataStore = playerSettingsDataStore,
        deviceCapabilityRepository = deviceCapabilityRepository,
        uploadIntegrationProvider = uploadIntegrationProvider,
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

        try {
            withContext(Dispatchers.IO) {
                when (val result = uploadIntegrationProvider.uploadDeviceCapabilityReport(
                    baseUrl = baseUrl,
                    token = token,
                    envelopeJson = envelope
                )) {
                    is IntegrationCallResult.Success -> Unit
                    is IntegrationCallResult.HttpError -> {
                        submitted.set(false)
                        Log.w(DEVICE_CAPABILITY_UPLOAD_TAG, "Upload failed code=${result.statusCode}")
                    }
                    is IntegrationCallResult.NetworkError -> {
                        submitted.set(false)
                        Log.w(DEVICE_CAPABILITY_UPLOAD_TAG, "Upload failed: ${result.throwable.message}")
                    }
                    IntegrationCallResult.Missing -> {
                        submitted.set(false)
                    }
                }
            }
        } catch (error: CancellationException) {
            submitted.set(false)
            throw error
        } catch (error: Throwable) {
            submitted.set(false)
            Log.w(DEVICE_CAPABILITY_UPLOAD_TAG, "Upload failed: ${error.message}")
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
