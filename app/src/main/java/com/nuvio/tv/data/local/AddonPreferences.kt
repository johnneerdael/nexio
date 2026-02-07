package com.nuvio.tv.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "addon_preferences")

@Singleton
class AddonPreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val gson = Gson()
    private val orderedUrlsKey = stringPreferencesKey("installed_addon_urls_ordered")
    private val legacyUrlsKey = stringSetPreferencesKey("installed_addon_urls")

    val installedAddonUrls: Flow<List<String>> = context.dataStore.data
        .map { preferences ->
            val json = preferences[orderedUrlsKey]
            if (json != null) {
                parseUrlList(json)
            } else {
                val legacySet = preferences[legacyUrlsKey] ?: getDefaultAddons()
                legacySet.toList()
            }
        }

    suspend fun ensureMigrated() {
        val prefs = context.dataStore.data.first()
        if (prefs[orderedUrlsKey] == null) {
            val legacySet = prefs[legacyUrlsKey] ?: getDefaultAddons()
            context.dataStore.edit { preferences ->
                preferences[orderedUrlsKey] = gson.toJson(legacySet.toList())
                preferences.remove(legacyUrlsKey)
            }
        }
    }

    suspend fun addAddon(url: String) {
        context.dataStore.edit { preferences ->
            val current = getCurrentList(preferences)
            if (url !in current) {
                preferences[orderedUrlsKey] = gson.toJson(current + url)
            }
        }
    }

    suspend fun removeAddon(url: String) {
        context.dataStore.edit { preferences ->
            val current = getCurrentList(preferences)
            preferences[orderedUrlsKey] = gson.toJson(current - url)
        }
    }

    suspend fun setAddonOrder(urls: List<String>) {
        context.dataStore.edit { preferences ->
            preferences[orderedUrlsKey] = gson.toJson(urls)
        }
    }

    private fun getCurrentList(preferences: Preferences): List<String> {
        val json = preferences[orderedUrlsKey]
        return if (json != null) {
            parseUrlList(json)
        } else {
            val legacySet = preferences[legacyUrlsKey] ?: getDefaultAddons()
            legacySet.toList()
        }
    }

    private fun parseUrlList(json: String): List<String> {
        return try {
            val type = object : TypeToken<List<String>>() {}.type
            gson.fromJson(json, type) ?: getDefaultAddons().toList()
        } catch (e: Exception) {
            getDefaultAddons().toList()
        }
    }

    private fun getDefaultAddons(): Set<String> = setOf(
        "https://v3-cinemeta.strem.io"
    )
}
