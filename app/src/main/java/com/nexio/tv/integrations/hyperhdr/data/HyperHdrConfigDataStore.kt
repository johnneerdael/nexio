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
        val JSON_PORT = intPreferencesKey("hyperhdr_json_port")
        val PRIORITY = intPreferencesKey("hyperhdr_priority")
        val HDR_MODE = stringPreferencesKey("hyperhdr_hdr_mode")
    }

    val config: Flow<HyperHdrConfig> = dataStore.data.map { prefs ->
        HyperHdrConfig(
            enabled = prefs[Keys.ENABLED] ?: false,
            host = prefs[Keys.HOST] ?: "",
            port = prefs[Keys.PORT] ?: 19400,
            jsonPort = prefs[Keys.JSON_PORT] ?: 19444,
            priority = prefs[Keys.PRIORITY] ?: 100,
            hdrMode = prefs[Keys.HDR_MODE]?.let {
                runCatching { HdrMode.valueOf(it) }.getOrDefault(HdrMode.Auto)
            } ?: HdrMode.Auto,
        )
    }

    suspend fun update(transform: (HyperHdrConfig) -> HyperHdrConfig) {
        dataStore.edit { prefs ->
            val current = HyperHdrConfig(
                enabled = prefs[Keys.ENABLED] ?: false,
                host = prefs[Keys.HOST] ?: "",
                port = prefs[Keys.PORT] ?: 19400,
                jsonPort = prefs[Keys.JSON_PORT] ?: 19444,
                priority = prefs[Keys.PRIORITY] ?: 100,
                hdrMode = prefs[Keys.HDR_MODE]?.let {
                    runCatching { HdrMode.valueOf(it) }.getOrDefault(HdrMode.Auto)
                } ?: HdrMode.Auto,
            )
            val next = transform(current)
            prefs[Keys.ENABLED] = next.enabled
            prefs[Keys.HOST] = next.host
            prefs[Keys.PORT] = next.port
            prefs[Keys.JSON_PORT] = next.jsonPort
            prefs[Keys.PRIORITY] = next.priority
            prefs[Keys.HDR_MODE] = next.hdrMode.name
        }
    }
}
