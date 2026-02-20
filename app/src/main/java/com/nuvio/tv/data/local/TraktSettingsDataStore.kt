package com.nuvio.tv.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
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
    private val showUnairedNextUpKey = booleanPreferencesKey("show_unaired_next_up")

    companion object {
        const val CONTINUE_WATCHING_DAYS_CAP_ALL = 0
        const val DEFAULT_CONTINUE_WATCHING_DAYS_CAP = 60
        const val DEFAULT_SHOW_UNAIRED_NEXT_UP = true
        const val MIN_CONTINUE_WATCHING_DAYS_CAP = 7
        const val MAX_CONTINUE_WATCHING_DAYS_CAP = 365
    }

    val continueWatchingDaysCap: Flow<Int> = dataStore.data.map { prefs ->
        normalizeContinueWatchingDaysCap(
            prefs[continueWatchingDaysCapKey] ?: DEFAULT_CONTINUE_WATCHING_DAYS_CAP
        )
    }

    val dismissedNextUpKeys: Flow<Set<String>> = dataStore.data.map { prefs ->
        prefs[dismissedNextUpKeysKey] ?: emptySet()
    }

    val showUnairedNextUp: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[showUnairedNextUpKey] ?: DEFAULT_SHOW_UNAIRED_NEXT_UP
    }

    suspend fun setContinueWatchingDaysCap(days: Int) {
        dataStore.edit { prefs ->
            prefs[continueWatchingDaysCapKey] = normalizeContinueWatchingDaysCap(days)
        }
    }

    private fun normalizeContinueWatchingDaysCap(days: Int): Int {
        return if (days == CONTINUE_WATCHING_DAYS_CAP_ALL) {
            CONTINUE_WATCHING_DAYS_CAP_ALL
        } else {
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

    suspend fun setShowUnairedNextUp(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[showUnairedNextUpKey] = enabled
        }
    }
}
