package com.nexio.tv.instrumentation

import android.content.Context
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
class PlaybackTraceToggle @Inject constructor(
    @ApplicationContext private val appContext: Context
) {
    private val dataStore: DataStore<Preferences> = appContext.playbackTraceDataStore

    val enabledFlow: Flow<Boolean> = dataStore.data.map { it[KEY] ?: false }

    suspend fun setEnabled(value: Boolean) {
        dataStore.edit { it[KEY] = value }
        if (!value) {
            // On toggle-off, end any active session before flipping the gate so
            // the writer thread captures `playback_session_ended` deterministically.
            val active = PlaybackTracer.currentInternal()
            if (active != null) {
                PlaybackTracer.endSession(active.sessionId)
            }
        }
        PlaybackTracer.enabled = value
    }

    companion object {
        private val KEY = booleanPreferencesKey("playback_trace_enabled")
    }
}
