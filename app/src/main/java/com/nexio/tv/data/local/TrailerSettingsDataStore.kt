package com.nexio.tv.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.trailerSettingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "trailer_settings"
)

enum class TrailerMaxQuality(
    val maxHeight: Int,
    val label: String,
    internal val storedValue: String
) {
    P720(720, "720p", "720p"),
    P1080(1080, "1080p", "1080p"),
    P2160(2160, "2160p", "2160p");

    companion object {
        val DEFAULT: TrailerMaxQuality = P1080

        fun fromStoredValue(value: String?): TrailerMaxQuality =
            entries.firstOrNull { it.storedValue == value } ?: DEFAULT
    }
}

@Singleton
class TrailerSettingsDataStore internal constructor(
    private val dataStore: DataStore<Preferences>
) {
    @Inject constructor(
        @ApplicationContext context: Context
    ) : this(context.trailerSettingsDataStore)

    private fun store() = dataStore

    private val enabledKey = booleanPreferencesKey("trailer_enabled")
    private val delaySecondsKey = intPreferencesKey("trailer_delay_seconds")
    private val maxQualityKey = stringPreferencesKey("trailer_max_quality")

    val settings: Flow<TrailerSettings> = dataStore.data.map { prefs ->
        TrailerSettings(
            enabled = prefs[enabledKey] ?: true,
            delaySeconds = prefs[delaySecondsKey] ?: 7,
            maxQuality = TrailerMaxQuality.fromStoredValue(prefs[maxQualityKey])
        )
    }

    suspend fun setEnabled(enabled: Boolean) {
        store().edit { it[enabledKey] = enabled }
    }

    suspend fun setDelaySeconds(seconds: Int) {
        store().edit { it[delaySecondsKey] = seconds.coerceAtLeast(0) }
    }

    suspend fun setMaxQuality(maxQuality: TrailerMaxQuality) {
        store().edit { it[maxQualityKey] = maxQuality.storedValue }
    }
}

data class TrailerSettings(
    val enabled: Boolean = true,
    val delaySeconds: Int = 7,
    val maxQuality: TrailerMaxQuality = TrailerMaxQuality.DEFAULT
)
