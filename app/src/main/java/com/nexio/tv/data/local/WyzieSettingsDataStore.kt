package com.nexio.tv.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.nexio.tv.domain.model.WyzieSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.wyzieSettingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "wyzie_settings"
)

@Singleton
class WyzieSettingsDataStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val store get() = context.wyzieSettingsDataStore

    private val keyApi = stringPreferencesKey("wyzie_api_key")
    private val keyEnabled = booleanPreferencesKey("wyzie_enabled")

    val settings: Flow<WyzieSettings> = store.data.map { prefs ->
        WyzieSettings(
            apiKey = prefs[keyApi]?.takeIf { it.isNotBlank() },
            enabled = prefs[keyEnabled] ?: true,
        )
    }

    suspend fun setApiKey(value: String) {
        store.edit { prefs ->
            val trimmed = value.trim()
            if (trimmed.isEmpty()) {
                prefs.remove(keyApi)
            } else {
                prefs[keyApi] = trimmed
            }
        }
    }

    suspend fun setEnabled(enabled: Boolean) {
        store.edit { prefs ->
            prefs[keyEnabled] = enabled
        }
    }
}
