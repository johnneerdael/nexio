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
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

private const val TAG = "ShadowAutoplayUpload"

@Singleton
class ShadowAutoplayCollectionUploader internal constructor(
    private val playerSettingsDataStore: PlayerSettingsDataStore,
    private val uploadIntegrationProvider: ShadowAutoplayUploadIntegrationProvider,
    private val logger: ShadowAutoPlayDecisionLogger,
    private val baseUrlProvider: () -> String,
    private val tokenProvider: () -> String,
    private val clientInfoProvider: () -> JsonObject
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        playerSettingsDataStore: PlayerSettingsDataStore,
        uploadIntegrationProvider: ShadowAutoplayUploadIntegrationProvider,
        logger: ShadowAutoPlayDecisionLogger
    ) : this(
        playerSettingsDataStore = playerSettingsDataStore,
        uploadIntegrationProvider = uploadIntegrationProvider,
        logger = logger,
        baseUrlProvider = { BuildConfig.SHADOW_DATA_COLLECTION_BASE_URL.trim().trimEnd('/') },
        tokenProvider = { BuildConfig.SHADOW_DATA_COLLECTION_WRITE_TOKEN.trim() },
        clientInfoProvider = { buildCollectorClientInfo(context) }
    )

    suspend fun submitIfEnabled(event: ShadowAutoPlayDecisionEvent) {
        val settings = playerSettingsDataStore.playerSettings.first()
        if (!settings.shadowAutoplayDataCollectionEnabled) return
        val baseUrl = baseUrlProvider()
        val token = tokenProvider()
        if (baseUrl.isBlank() || token.isBlank()) return

        val payloadJson = logger.encode(event)
        val envelope = JsonObject().apply {
            addProperty("sentAtMs", System.currentTimeMillis())
            add("client", clientInfoProvider())
            add("payload", com.google.gson.JsonParser.parseString(payloadJson))
        }.toString()

        try {
            withContext(Dispatchers.IO) {
                when (val result = uploadIntegrationProvider.uploadEvent(
                    baseUrl = baseUrl,
                    token = token,
                    envelopeJson = envelope
                )) {
                    is IntegrationCallResult.Success -> Unit
                    is IntegrationCallResult.HttpError -> Log.w(TAG, "Upload failed code=${result.statusCode}")
                    is IntegrationCallResult.NetworkError -> Log.w(TAG, "Upload failed: ${result.throwable.message}")
                    IntegrationCallResult.Missing -> Unit
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Log.w(TAG, "Upload failed: ${error.message}")
        }
    }
}

private fun buildCollectorClientInfo(context: Context): JsonObject {
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
