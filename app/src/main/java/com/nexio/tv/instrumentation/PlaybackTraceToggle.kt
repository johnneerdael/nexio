package com.nexio.tv.instrumentation

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

internal val Context.playbackTraceDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "playback_trace_settings"
)

/**
 * Persistent gate for [PlaybackTracer]. The setter mirrors spec §A.1
 * amendment C4 — toggling OFF mid-session ends the active session and rotates
 * the file; toggling ON is a no-op until the next `createMediaSource()` call
 * starts a new session.
 */
@Singleton
class PlaybackTraceToggle internal constructor(
    private val dataStore: DataStore<Preferences>
) {
    companion object {
        private const val TAG = "PlaybackTraceToggle"
        private val KEY = booleanPreferencesKey("playback_trace_enabled")
    }

    @Inject
    constructor(
        @ApplicationContext appContext: Context
    ) : this(appContext.playbackTraceDataStore)

    val enabledFlow: Flow<Boolean> = dataStore.data.map { it[KEY] ?: false }

    suspend fun setEnabled(value: Boolean) {
        val previous = PlaybackTracer.enabled
        val activeSessionId = PlaybackTracer.currentInternal()?.sessionId
        val callerSummary = Throwable().stackTrace
            .drop(1)
            .take(4)
            .joinToString(" <- ") { frame ->
                "${frame.className}.${frame.methodName}:${frame.lineNumber}"
            }
        Log.i(
            TAG,
            "setEnabled requested value=$value previous=$previous activeSession=$activeSessionId " +
                "caller=$callerSummary"
        )
        // Set the hot-path gate before publishing the persisted state so any
        // immediate observer refresh sees the new in-memory value.
        PlaybackTracer.enabled = value
        if (!value) {
            // On toggle-off, end any active session before flipping the gate so
            // the writer thread captures `playback_session_ended` deterministically.
            val active = PlaybackTracer.currentInternal()
            if (active != null) {
                PlaybackTracer.endSession(active.sessionId)
            }
        }
        dataStore.edit { it[KEY] = value }
        Log.i(
            TAG,
            "setEnabled committed value=$value current=${PlaybackTracer.enabled} activeSession=${PlaybackTracer.currentInternal()?.sessionId}"
        )
    }
}
