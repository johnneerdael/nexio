package com.nexio.tv.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.animeSkipSettingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "animeskip_settings"
)

@Singleton
class AnimeSkipSettingsDataStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val dataStore = context.animeSkipSettingsDataStore
    private fun store() = dataStore

    private val enabledKey = booleanPreferencesKey("animeskip_enabled")
    private val clientIdKey = stringPreferencesKey("animeskip_client_id")

    val enabled: Flow<Boolean> = dataStore.data.map { it[enabledKey] ?: false }

    val clientId: Flow<String> = dataStore.data.map { it[clientIdKey] ?: "" }

    suspend fun setEnabled(enabled: Boolean) {
        store().edit { it[enabledKey] = enabled }
    }

    suspend fun setClientId(clientId: String) {
        store().edit { it[clientIdKey] = clientId.trim() }
    }
}
