package com.nexio.tv.data.repository.benchmark

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.nexio.tv.BuildConfig
import com.nexio.tv.instrumentation.PlaybackTraceController
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

private const val TAG = "DiagnosticsUpload"

internal sealed class DiagnosticsUploadResult {
    object Success : DiagnosticsUploadResult()
    object NoTrace : DiagnosticsUploadResult()
    data class Error(val message: String) : DiagnosticsUploadResult()
}

@Singleton
internal class PlaybackDiagnosticsUploader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient,
    private val traceController: PlaybackTraceController,
) {
    internal suspend fun uploadLastSession(): DiagnosticsUploadResult = withContext(Dispatchers.IO) {
        val baseUrl = BuildConfig.SHADOW_DATA_COLLECTION_BASE_URL.trim().trimEnd('/')
        if (baseUrl.isBlank()) return@withContext DiagnosticsUploadResult.Error("No collector URL configured")

        val androidId = runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: return@withContext DiagnosticsUploadResult.Error("Android ID unavailable")

        val token = CollectorPublicDashboardLinkProvider.hashAndroidId(androidId)
        if (token.isBlank()) return@withContext DiagnosticsUploadResult.Error("Token derivation failed")

        val lastTrace = traceController.listTraces().maxByOrNull { it.lastModified() }
            ?: return@withContext DiagnosticsUploadResult.NoTrace

        runCatching {
            val body = lastTrace.readBytes().toRequestBody("application/x-ndjson".toMediaType())
            val request = Request.Builder()
                .url("$baseUrl/public/$token/diagnostics")
                .header("X-App-Version", BuildConfig.VERSION_NAME)
                .header("X-Device-Model", Build.MODEL)
                .post(body)
                .build()
            okHttpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) DiagnosticsUploadResult.Success
                else DiagnosticsUploadResult.Error("HTTP ${response.code}")
            }
        }.getOrElse { e ->
            Log.w(TAG, "Upload failed: ${e.message}")
            DiagnosticsUploadResult.Error(e.message ?: "Unknown error")
        }
    }
}
