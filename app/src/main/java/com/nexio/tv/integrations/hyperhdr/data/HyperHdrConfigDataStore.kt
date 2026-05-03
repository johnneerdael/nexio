package com.nexio.tv.integrations.hyperhdr.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HyperHdrConfigDataStore @Inject constructor(
    @com.nexio.tv.integrations.hyperhdr.di.HyperHdrPrefs
    private val dataStore: DataStore<Preferences>,
) {
    private object Keys {
        val ENABLED = booleanPreferencesKey("hyperhdr_enabled")
        val HOST = stringPreferencesKey("hyperhdr_host")
        val PORT = intPreferencesKey("hyperhdr_port")
        val PRIORITY = intPreferencesKey("hyperhdr_priority")
    }

    val config: Flow<HyperHdrConfig> = dataStore.data.map { prefs ->
        HyperHdrConfig(
            enabled = prefs[Keys.ENABLED] ?: false,
            host = prefs[Keys.HOST] ?: "",
            port = prefs[Keys.PORT] ?: 19400,
            priority = prefs[Keys.PRIORITY] ?: 100,
        )
    }

    suspend fun update(transform: (HyperHdrConfig) -> HyperHdrConfig) {
        dataStore.edit { prefs ->
            val current = HyperHdrConfig(
                enabled = prefs[Keys.ENABLED] ?: false,
                host = prefs[Keys.HOST] ?: "",
                port = prefs[Keys.PORT] ?: 19400,
                priority = prefs[Keys.PRIORITY] ?: 100,
            )
            val next = transform(current)
            prefs[Keys.ENABLED] = next.enabled
            prefs[Keys.HOST] = next.host
            prefs[Keys.PORT] = next.port
            prefs[Keys.PRIORITY] = next.priority
        }
    }
}
