package com.nexio.tv.data.local

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.nexio.tv.core.sync.normalizeAddonInstallUrl
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.addonPreferencesDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "addon_preferences"
)

@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class AddonPreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "AddonPreferences"
    }

    private val dataStore = context.addonPreferencesDataStore
    private fun store() = dataStore

    private val gson = Gson()
    private val orderedUrlsKey = stringPreferencesKey("installed_addon_urls_ordered")
    private val legacyUrlsKey = stringSetPreferencesKey("installed_addon_urls")

    private fun canonicalizeUrl(url: String): String {
        return normalizeAddonInstallUrl(url)
    }

    private fun safeCanonicalizeUrl(url: String, label: String): String? {
        return runCatching { canonicalizeUrl(url) }
            .onFailure { error ->
                Log.w(TAG, "Dropping malformed addon URL from $label: $url", error)
            }
            .getOrNull()
    }

    val installedAddonUrls: Flow<List<String>> = dataStore.data.map { preferences ->
        val json = preferences[orderedUrlsKey]
        if (json != null) {
            parseUrlList(json)
        } else {
            val legacySet = preferences[legacyUrlsKey] ?: getDefaultAddons()
            legacySet.mapNotNull { url -> safeCanonicalizeUrl(url, "legacy preferences") }.distinct()
        }
    }

    suspend fun ensureMigrated() {
        val ds = store()
        val prefs = ds.data.first()
        if (prefs[orderedUrlsKey] == null) {
            val legacySet = prefs[legacyUrlsKey] ?: getDefaultAddons()
            val migrated = legacySet.mapNotNull { url -> safeCanonicalizeUrl(url, "legacy migration") }.distinct()
            ds.edit { preferences ->
                preferences[orderedUrlsKey] = gson.toJson(migrated)
                preferences.remove(legacyUrlsKey)
            }
        }
    }

    suspend fun addAddon(url: String) {
        store().edit { preferences ->
            val current = getCurrentList(preferences)
            val normalizedUrl = canonicalizeUrl(url)
            if (current.any { canonicalizeUrl(it).equals(normalizedUrl, ignoreCase = true) }) return@edit
            preferences[orderedUrlsKey] = gson.toJson(current + normalizedUrl)
        }
    }

    suspend fun removeAddon(url: String) {
        store().edit { preferences ->
            val current = getCurrentList(preferences).toMutableList()
            val normalizedUrl = canonicalizeUrl(url)

            val indexToRemove = current.indexOfFirst {
                canonicalizeUrl(it).equals(normalizedUrl, ignoreCase = true)
            }
            if (indexToRemove != -1) {
                current.removeAt(indexToRemove)
            }
            preferences[orderedUrlsKey] = gson.toJson(current)
        }
    }

    suspend fun setAddonOrder(urls: List<String>) {
        store().edit { preferences ->
            preferences[orderedUrlsKey] = gson.toJson(urls.map(::canonicalizeUrl))
        }
    }

    private fun getCurrentList(preferences: Preferences): List<String> {
        val json = preferences[orderedUrlsKey]
        return if (json != null) {
            parseUrlList(json)
        } else {
            val legacySet = preferences[legacyUrlsKey] ?: getDefaultAddons()
            legacySet.mapNotNull { url -> safeCanonicalizeUrl(url, "legacy preferences") }.distinct()
        }
    }

    private fun parseUrlList(json: String): List<String> {
        return try {
            val type = object : TypeToken<List<String>>() {}.type
            val parsed: List<String> = gson.fromJson(json, type) ?: return emptyList()
            parsed.mapNotNull { url -> safeCanonicalizeUrl(url, "preferences") }.distinct()
        } catch (e: Exception) {
            getDefaultAddons().toList()
        }
    }

    private fun getDefaultAddons(): Set<String> = setOf(
        "https://v3-cinemeta.strem.io",
        "https://opensubtitles-v3.strem.io"
    )
}
