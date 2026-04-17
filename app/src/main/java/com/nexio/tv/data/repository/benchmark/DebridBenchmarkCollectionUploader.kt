package com.nexio.tv.data.repository.benchmark

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.google.gson.JsonObject
import com.nexio.tv.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

private const val TAG = "BenchmarkUpload"

@Singleton
class DebridBenchmarkCollectionUploader internal constructor(
    private val okHttpClient: OkHttpClient,
    private val baseUrlProvider: () -> String,
    private val tokenProvider: () -> String,
    private val clientInfoProvider: () -> JsonObject
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        okHttpClient: OkHttpClient
    ) : this(
        okHttpClient = okHttpClient,
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
