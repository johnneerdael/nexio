package com.nexio.tv.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.premiumizeSettingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "premiumize_settings"
)

data class PremiumizeSettings(
    val apiKey: String = ""
) {
    val isConfigured: Boolean
        get() = apiKey.isNotBlank()
}

@Singleton
class PremiumizeSettingsDataStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val apiKeyKey = stringPreferencesKey("premiumize_api_key")

    val settings: Flow<PremiumizeSettings> = context.premiumizeSettingsDataStore.data.map { prefs ->
        PremiumizeSettings(
            apiKey = prefs[apiKeyKey].orEmpty()
        )
    }

    suspend fun setApiKey(value: String) {
        context.premiumizeSettingsDataStore.edit { prefs ->
            val trimmed = value.trim()
            if (trimmed.isBlank()) {
                prefs.remove(apiKeyKey)
            } else {
                prefs[apiKeyKey] = trimmed
            }
        }
    }
}
