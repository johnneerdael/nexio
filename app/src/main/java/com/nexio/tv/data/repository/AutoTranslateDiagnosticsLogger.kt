package com.nexio.tv.data.repository

import android.util.Log
import com.nexio.tv.data.local.DebugSettingsDataStore
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

private const val AUTO_TRANSLATE_DIAGNOSTICS_TAG = "AutoTranslateDiagnostics"
private const val LOG_CHUNK_SIZE = 3_000

@Singleton
class AutoTranslateDiagnosticsLogger private constructor() {
    @Volatile private var metadataEnabled = false
    @Volatile private var unsafeBodyLoggingEnabled = false

    @Inject
    constructor(debugSettingsDataStore: DebugSettingsDataStore) : this() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope.launch {
            debugSettingsDataStore.autoTranslateDiagnosticsEnabled.collect { enabled ->
                metadataEnabled = enabled
            }
        }
        scope.launch {
            debugSettingsDataStore.autoTranslateUnsafeBodyLoggingEnabled.collect { enabled ->
                unsafeBodyLoggingEnabled = enabled
            }
        }
    }

    fun isMetadataEnabled(): Boolean = metadataEnabled

    fun isUnsafeBodyLoggingEnabled(): Boolean = metadataEnabled && unsafeBodyLoggingEnabled

    fun log(message: String) {
        if (!metadataEnabled) return
        Log.d(AUTO_TRANSLATE_DIAGNOSTICS_TAG, message)
    }

    fun logUnsafe(label: String, body: String) {
        if (!isUnsafeBodyLoggingEnabled()) return
        if (body.isEmpty()) {
            Log.d(AUTO_TRANSLATE_DIAGNOSTICS_TAG, "$label body=<empty>")
            return
        }
        body.chunked(LOG_CHUNK_SIZE).forEachIndexed { index, chunk ->
            Log.d(
                AUTO_TRANSLATE_DIAGNOSTICS_TAG,
                "$label chunk=${index + 1}/${(body.length + LOG_CHUNK_SIZE - 1) / LOG_CHUNK_SIZE} body=$chunk"
            )
        }
    }

    companion object {
        fun disabled(): AutoTranslateDiagnosticsLogger = AutoTranslateDiagnosticsLogger()

        fun sha256Short(value: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray(StandardCharsets.UTF_8))
            return digest.take(8).joinToString("") { "%02x".format(it) }
        }
    }
}
