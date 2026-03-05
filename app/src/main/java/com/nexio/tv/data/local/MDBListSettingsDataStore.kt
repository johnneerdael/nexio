package com.nexio.tv.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.nexio.tv.domain.model.MDBListSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.mdbListSettingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "mdblist_settings"
)

data class MDBListCatalogPreferences(
    val hiddenPersonalListKeys: Set<String> = emptySet(),
    val selectedTopListKeys: Set<String> = emptySet(),
    val catalogOrder: List<String> = emptyList()
) {
    fun isPersonalListEnabled(listKey: String): Boolean = listKey !in hiddenPersonalListKeys
    fun isTopListSelected(listKey: String): Boolean = listKey in selectedTopListKeys
}

@Singleton
class MDBListSettingsDataStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val dataStore = context.mdbListSettingsDataStore
    private fun store() = dataStore

    private val enabledKey = booleanPreferencesKey("mdblist_enabled")
    private val apiKeyKey = stringPreferencesKey("mdblist_api_key")
    private val showTraktKey = booleanPreferencesKey("mdblist_show_trakt")
    private val showImdbKey = booleanPreferencesKey("mdblist_show_imdb")
    private val showTmdbKey = booleanPreferencesKey("mdblist_show_tmdb")
    private val showLetterboxdKey = booleanPreferencesKey("mdblist_show_letterboxd")
    private val showTomatoesKey = booleanPreferencesKey("mdblist_show_tomatoes")
    private val showAudienceKey = booleanPreferencesKey("mdblist_show_audience")
    private val showMetacriticKey = booleanPreferencesKey("mdblist_show_metacritic")
    private val hiddenPersonalListKeysKey = stringSetPreferencesKey("mdblist_hidden_personal_list_keys")
    private val selectedTopListKeysKey = stringSetPreferencesKey("mdblist_selected_top_list_keys")
    private val catalogOrderCsvKey = stringPreferencesKey("mdblist_catalog_order_csv")

    val settings: Flow<MDBListSettings> = dataStore.data.map { prefs ->
        MDBListSettings(
            enabled = prefs[enabledKey] ?: false,
            apiKey = prefs[apiKeyKey] ?: "",
            showTrakt = prefs[showTraktKey] ?: true,
            showImdb = prefs[showImdbKey] ?: true,
            showTmdb = prefs[showTmdbKey] ?: true,
            showLetterboxd = prefs[showLetterboxdKey] ?: true,
            showTomatoes = prefs[showTomatoesKey] ?: true,
            showAudience = prefs[showAudienceKey] ?: true,
            showMetacritic = prefs[showMetacriticKey] ?: true
        )
    }

    val catalogPreferences: Flow<MDBListCatalogPreferences> = dataStore.data.map { prefs ->
        MDBListCatalogPreferences(
            hiddenPersonalListKeys = prefs[hiddenPersonalListKeysKey] ?: emptySet(),
            selectedTopListKeys = prefs[selectedTopListKeysKey] ?: emptySet(),
            catalogOrder = parseCatalogOrder(prefs[catalogOrderCsvKey])
        )
    }

    suspend fun setEnabled(enabled: Boolean) {
        store().edit { it[enabledKey] = enabled }
    }

    suspend fun setApiKey(apiKey: String) {
        store().edit { it[apiKeyKey] = apiKey.trim() }
    }

    suspend fun setShowTrakt(enabled: Boolean) {
        store().edit { it[showTraktKey] = enabled }
    }

    suspend fun setShowImdb(enabled: Boolean) {
        store().edit { it[showImdbKey] = enabled }
    }

    suspend fun setShowTmdb(enabled: Boolean) {
        store().edit { it[showTmdbKey] = enabled }
    }

    suspend fun setShowLetterboxd(enabled: Boolean) {
        store().edit { it[showLetterboxdKey] = enabled }
    }

    suspend fun setShowTomatoes(enabled: Boolean) {
        store().edit { it[showTomatoesKey] = enabled }
    }

    suspend fun setShowAudience(enabled: Boolean) {
        store().edit { it[showAudienceKey] = enabled }
    }

    suspend fun setShowMetacritic(enabled: Boolean) {
        store().edit { it[showMetacriticKey] = enabled }
    }

    suspend fun setPersonalListEnabled(listKey: String, enabled: Boolean) {
        val key = listKey.trim()
        if (key.isBlank()) return
        store().edit { prefs ->
            val current = prefs[hiddenPersonalListKeysKey] ?: emptySet()
            prefs[hiddenPersonalListKeysKey] = if (enabled) current - key else current + key
        }
    }

    suspend fun setTopListSelected(listKey: String, selected: Boolean) {
        val key = listKey.trim()
        if (key.isBlank()) return
        store().edit { prefs ->
            val current = prefs[selectedTopListKeysKey] ?: emptySet()
            prefs[selectedTopListKeysKey] = if (selected) current + key else current - key
        }
    }

    suspend fun moveCatalog(listKey: String, direction: Int, availableKeys: Set<String>) {
        val key = listKey.trim()
        if (key.isBlank() || direction == 0 || key !in availableKeys) return
        store().edit { prefs ->
            val currentOrder = sanitizeCatalogOrder(
                parseCatalogOrder(prefs[catalogOrderCsvKey]),
                availableKeys
            ).toMutableList()
            val index = currentOrder.indexOf(key)
            if (index == -1) return@edit
            val target = (index + direction).coerceIn(0, currentOrder.lastIndex)
            if (target == index) return@edit
            currentOrder.removeAt(index)
            currentOrder.add(target, key)
            prefs[catalogOrderCsvKey] = currentOrder.joinToString(",")
        }
    }

    fun sanitizeCatalogOrder(rawOrder: List<String>, availableKeys: Set<String>): List<String> {
        if (availableKeys.isEmpty()) return emptyList()
        val uniqueKnown = rawOrder.asSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() && it in availableKeys }
            .distinct()
            .toList()
        return uniqueKnown + availableKeys.filterNot { it in uniqueKnown }
    }

    private fun parseCatalogOrder(raw: String?): List<String> {
        return raw
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?.distinct()
            ?: emptyList()
    }
}
