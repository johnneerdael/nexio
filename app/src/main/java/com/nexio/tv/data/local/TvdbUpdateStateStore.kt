package com.nexio.tv.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.tvdbUpdateStateDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "tvdb_update_state"
)

@Singleton
class TvdbUpdateStateStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val dataStore = context.tvdbUpdateStateDataStore

    private val lastSuccessfulCursorKey = longPreferencesKey("last_successful_cursor")
    private val lastRefreshStatusKey = stringPreferencesKey("last_refresh_status")
    private val lastRefreshAtMsKey = longPreferencesKey("last_refresh_at_ms")
    private val lastRefreshErrorKey = stringPreferencesKey("last_refresh_error")

    val lastSuccessfulCursor: Flow<Long> = dataStore.data.map { prefs ->
        prefs[lastSuccessfulCursorKey] ?: 0L
    }

    suspend fun currentCursor(): Long {
        return dataStore.data.first()[lastSuccessfulCursorKey] ?: 0L
    }

    suspend fun storeSuccessfulCursor(cursor: Long) {
        dataStore.edit { prefs ->
            prefs[lastSuccessfulCursorKey] = cursor
            prefs[lastRefreshAtMsKey] = System.currentTimeMillis()
        }
    }

    suspend fun recordStatus(status: String, error: String? = null) {
        dataStore.edit { prefs ->
            prefs[lastRefreshStatusKey] = status
            prefs[lastRefreshAtMsKey] = System.currentTimeMillis()
            if (error != null) {
                prefs[lastRefreshErrorKey] = error.take(500)
            } else {
                prefs.remove(lastRefreshErrorKey)
            }
        }
    }
}
