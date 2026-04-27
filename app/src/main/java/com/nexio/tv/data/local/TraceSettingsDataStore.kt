package com.nexio.tv.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
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

    val mode: Flow<TraceMode> = dataStore.data.map { prefs -> TraceMode.parse(prefs[modeKey]) }

    suspend fun setMode(mode: TraceMode) {
        dataStore.edit { prefs ->
            prefs[modeKey] = mode.name
        }
    }
}
