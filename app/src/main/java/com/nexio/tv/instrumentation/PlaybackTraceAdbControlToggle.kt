package com.nexio.tv.instrumentation

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Secondary opt-in for the ADB diagnostics control plane.
 *
 * Default **off**. When off, [PlaybackTraceAdbReceiver] silently ignores
 * every incoming broadcast — leaving the receiver inert even though it is
 * exported in the manifest. When on, the receiver hands the command off
 * to [PlaybackTraceController] (the same controller the in-app UI uses).
 *
 * Persisted in the same `playback_trace_settings` DataStore as
 * [PlaybackTraceToggle] under a separate key, so flipping the master
 * tracer toggle does not also expose the ADB surface.
 *
 * Security note: per the spec design, this is a deliberate two-step
 * activation — the user must explicitly enable ADB control in the debrid
 * settings menu before any external `am broadcast` command will do
 * anything. The receiver itself ships exported (so `adb shell am broadcast`
 * can reach it at all) but is gated by this runtime flag.
 */
@Singleton
class PlaybackTraceAdbControlToggle @Inject constructor(
    @ApplicationContext appContext: Context,
) {
    private val dataStore: DataStore<Preferences> = appContext.playbackTraceDataStore

    val enabledFlow: Flow<Boolean> = dataStore.data.map { it[KEY] ?: false }

    suspend fun setEnabled(value: Boolean) {
        dataStore.edit { it[KEY] = value }
    }

    /** Suspending read for callers that need a one-shot snapshot. */
    suspend fun isEnabled(): Boolean = enabledFlow.first()

    companion object {
        private val KEY = booleanPreferencesKey("playback_trace_adb_control_enabled")
    }
}
