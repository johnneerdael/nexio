package com.nuvio.tv.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.traktSettingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "trakt_settings")

@Singleton
class TraktSettingsDataStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val dataStore = context.traktSettingsDataStore

    private val continueWatchingDaysCapKey = intPreferencesKey("continue_watching_days_cap")
    private val dismissedNextUpKeysKey = stringSetPreferencesKey("dismissed_next_up_keys")

    companion object {
        const val DEFAULT_CONTINUE_WATCHING_DAYS_CAP = 60
        const val MIN_CONTINUE_WATCHING_DAYS_CAP = 7
        const val MAX_CONTINUE_WATCHING_DAYS_CAP = 365
    }

    val continueWatchingDaysCap: Flow<Int> = dataStore.data.map { prefs ->
        (prefs[continueWatchingDaysCapKey] ?: DEFAULT_CONTINUE_WATCHING_DAYS_CAP)
            .coerceIn(MIN_CONTINUE_WATCHING_DAYS_CAP, MAX_CONTINUE_WATCHING_DAYS_CAP)
    }

    val dismissedNextUpKeys: Flow<Set<String>> = dataStore.data.map { prefs ->
        prefs[dismissedNextUpKeysKey] ?: emptySet()
    }

    suspend fun setContinueWatchingDaysCap(days: Int) {
        dataStore.edit { prefs ->
            prefs[continueWatchingDaysCapKey] =
                days.coerceIn(MIN_CONTINUE_WATCHING_DAYS_CAP, MAX_CONTINUE_WATCHING_DAYS_CAP)
        }
    }

    suspend fun addDismissedNextUpKey(key: String) {
        if (key.isBlank()) return
        dataStore.edit { prefs ->
            val current = prefs[dismissedNextUpKeysKey] ?: emptySet()
            prefs[dismissedNextUpKeysKey] = current + key
        }
    }
}
