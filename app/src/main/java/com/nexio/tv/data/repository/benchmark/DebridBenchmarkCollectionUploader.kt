package com.nexio.tv.data.repository.benchmark

import android.content.Context
import android.os.Build
import android.provider.Settings
import com.google.gson.JsonObject
import com.nexio.tv.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DebridBenchmarkCollectionUploader internal constructor(
    private val baseUrlProvider: () -> String,
    private val tokenProvider: () -> String,
    private val clientInfoProvider: () -> JsonObject
) {
    @Inject
    constructor(
        @ApplicationContext context: Context
    ) : this(
        baseUrlProvider = { BuildConfig.SHADOW_DATA_COLLECTION_BASE_URL.trim().trimEnd('/') },
        tokenProvider = { BuildConfig.SHADOW_DATA_COLLECTION_WRITE_TOKEN.trim() },
        clientInfoProvider = { buildBenchmarkCollectorClientInfo(context) }
    )

    suspend fun submitIfEnabled(result: DebridBenchmarkResult) = Unit
}

private fun buildBenchmarkCollectorClientInfo(context: Context): JsonObject {
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
