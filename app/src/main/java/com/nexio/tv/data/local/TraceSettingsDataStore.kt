package com.nexio.tv.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.nexio.tv.core.trace.TraceMode
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.traceSettingsStore: DataStore<Preferences> by preferencesDataStore(name = "trace_settings")

@Singleton
class TraceSettingsDataStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val dataStore = context.traceSettingsStore
    private val modeKey = stringPreferencesKey("trace_mode")
    private val firstPaintLogcatKey = booleanPreferencesKey("logcat_first_paint_enabled")
    private val metaRouteLogcatKey = booleanPreferencesKey("logcat_meta_route_enabled")
    private val intRuntimeLogcatKey = booleanPreferencesKey("logcat_int_runtime_enabled")

    val mode: Flow<TraceMode> = dataStore.data.map { prefs -> TraceMode.parse(prefs[modeKey]) }

    val firstPaintLogcatEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[firstPaintLogcatKey] ?: false
    }

    val metaRouteLogcatEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[metaRouteLogcatKey] ?: false
    }

    val intRuntimeLogcatEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[intRuntimeLogcatKey] ?: false
    }

    suspend fun setMode(mode: TraceMode) {
        dataStore.edit { prefs ->
            prefs[modeKey] = mode.name
        }
    }

    suspend fun setFirstPaintLogcatEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[firstPaintLogcatKey] = enabled
        }
    }

    suspend fun setMetaRouteLogcatEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[metaRouteLogcatKey] = enabled
        }
    }

    suspend fun setIntRuntimeLogcatEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[intRuntimeLogcatKey] = enabled
        }
    }
}
