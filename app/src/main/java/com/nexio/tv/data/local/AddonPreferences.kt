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
import com.nexio.tv.domain.model.AddonParserPreset
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
    data class AddonInstallConfig(
        val url: String,
        val parserPreset: AddonParserPreset = AddonParserPreset.GENERIC
    )

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

    val installedAddons: Flow<List<AddonInstallConfig>> = dataStore.data.map { preferences ->
        val json = preferences[orderedUrlsKey]
        if (json != null) {
            parseInstallConfigList(json)
        } else {
            val legacySet = preferences[legacyUrlsKey] ?: getDefaultAddons()
            legacySet.mapNotNull { url ->
                safeCanonicalizeUrl(url, "legacy preferences")?.let { normalized ->
                    AddonInstallConfig(url = normalized)
                }
            }.distinctBy { it.url.lowercase() }
        }
    }

    val installedAddonUrls: Flow<List<String>> = installedAddons.map { addons ->
        addons.map { it.url }
    }

    suspend fun ensureMigrated() {
        val ds = store()
        val prefs = ds.data.first()
        if (prefs[orderedUrlsKey] == null) {
            val legacySet = prefs[legacyUrlsKey] ?: getDefaultAddons()
            val migrated = legacySet.mapNotNull { url ->
                safeCanonicalizeUrl(url, "legacy migration")?.let { normalized ->
                    AddonInstallConfig(url = normalized)
                }
            }.distinctBy { it.url.lowercase() }
            ds.edit { preferences ->
                preferences[orderedUrlsKey] = gson.toJson(migrated)
                preferences.remove(legacyUrlsKey)
            }
        }
    }

    suspend fun addAddon(
        url: String,
        parserPreset: AddonParserPreset = AddonParserPreset.GENERIC
    ) {
        store().edit { preferences ->
            val current = getCurrentList(preferences)
            val normalizedUrl = canonicalizeUrl(url)
            if (current.any { it.url.equals(normalizedUrl, ignoreCase = true) }) return@edit
            preferences[orderedUrlsKey] = gson.toJson(
                current + AddonInstallConfig(
                    url = normalizedUrl,
                    parserPreset = parserPreset
                )
            )
        }
    }

    suspend fun removeAddon(url: String) {
        store().edit { preferences ->
            val current = getCurrentList(preferences).toMutableList()
            val normalizedUrl = canonicalizeUrl(url)

            val indexToRemove = current.indexOfFirst { it.url.equals(normalizedUrl, ignoreCase = true) }
            if (indexToRemove != -1) {
                current.removeAt(indexToRemove)
            }
            preferences[orderedUrlsKey] = gson.toJson(current)
        }
    }

    suspend fun setAddonOrder(urls: List<String>) {
        store().edit { preferences ->
            val currentByUrl = getCurrentList(preferences)
                .associateBy { it.url.lowercase() }
            val reordered = urls.mapNotNull { url ->
                val normalized = canonicalizeUrl(url)
                currentByUrl[normalized.lowercase()] ?: AddonInstallConfig(url = normalized)
            }
            preferences[orderedUrlsKey] = gson.toJson(reordered)
        }
    }

    suspend fun updateAddonParserPreset(url: String, parserPreset: AddonParserPreset) {
        store().edit { preferences ->
            val normalizedUrl = canonicalizeUrl(url)
            val updated = getCurrentList(preferences).map { addon ->
                if (addon.url.equals(normalizedUrl, ignoreCase = true)) {
                    addon.copy(parserPreset = parserPreset)
                } else {
                    addon
                }
            }
            preferences[orderedUrlsKey] = gson.toJson(updated)
        }
    }

    suspend fun setAddonConfigs(configs: List<AddonInstallConfig>) {
        store().edit { preferences ->
            val normalized = configs.mapNotNull { addon ->
                safeCanonicalizeUrl(addon.url, "remote sync")?.let { url ->
                    AddonInstallConfig(url = url, parserPreset = addon.parserPreset)
                }
            }.distinctBy { it.url.lowercase() }
            preferences[orderedUrlsKey] = gson.toJson(normalized)
        }
    }

    private fun getCurrentList(preferences: Preferences): List<AddonInstallConfig> {
        val json = preferences[orderedUrlsKey]
        return if (json != null) {
            parseInstallConfigList(json)
        } else {
            val legacySet = preferences[legacyUrlsKey] ?: getDefaultAddons()
            legacySet.mapNotNull { url ->
                safeCanonicalizeUrl(url, "legacy preferences")?.let { normalized ->
                    AddonInstallConfig(url = normalized)
                }
            }.distinctBy { it.url.lowercase() }
        }
    }

    private fun parseInstallConfigList(json: String): List<AddonInstallConfig> {
        return try {
            val objectType = object : TypeToken<List<AddonInstallConfig>>() {}.type
            val parsedObjects: List<AddonInstallConfig>? = gson.fromJson(json, objectType)
            if (parsedObjects != null) {
                return parsedObjects.mapNotNull { addon ->
                    safeCanonicalizeUrl(addon.url, "preferences")?.let { normalized ->
                        AddonInstallConfig(
                            url = normalized,
                            parserPreset = addon.parserPreset
                        )
                    }
                }.distinctBy { it.url.lowercase() }
            }

            val legacyType = object : TypeToken<List<String>>() {}.type
            val parsedUrls: List<String> = gson.fromJson(json, legacyType) ?: return emptyList()
            parsedUrls.mapNotNull { url ->
                safeCanonicalizeUrl(url, "preferences")?.let { normalized ->
                    AddonInstallConfig(url = normalized)
                }
            }.distinctBy { it.url.lowercase() }
        } catch (e: Exception) {
            getDefaultAddons().map { AddonInstallConfig(url = it) }
        }
    }

    private fun getDefaultAddons(): Set<String> = setOf(
        "https://v3-cinemeta.strem.io",
        "https://opensubtitles-v3.strem.io"
    )
}
