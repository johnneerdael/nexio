package com.nexio.tv.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.nexio.tv.core.profile.ProfileManager
import com.nexio.tv.domain.model.MDBListSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

data class MDBListCatalogPreferences(
    val hiddenPersonalListKeys: Set<String> = emptySet(),
    val selectedTopListKeys: Set<String> = emptySet(),
    val catalogOrder: List<String> = emptyList()
) {
    fun isPersonalListEnabled(listKey: String): Boolean {
        val target = canonicalMDBListKey(listKey)
        return hiddenPersonalListKeys.none { canonicalMDBListKey(it) == target }
    }

    fun isTopListSelected(listKey: String): Boolean {
        val target = canonicalMDBListKey(listKey)
        return selectedTopListKeys.any { canonicalMDBListKey(it) == target }
    }
}

@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class MDBListSettingsDataStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    private companion object {
        private const val FEATURE = "mdblist_settings"
    }

    private fun store(profileId: Int = profileManager.activeProfileId.value): DataStore<Preferences> =
        factory.get(profileId, FEATURE)

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

    val settings: Flow<MDBListSettings> = profileManager.activeProfileId.flatMapLatest(::settingsForProfile)

    val catalogPreferences: Flow<MDBListCatalogPreferences> =
        profileManager.activeProfileId.flatMapLatest(::catalogPreferencesForProfile)

    fun settingsForProfile(profileId: Int): Flow<MDBListSettings> = store(profileId).data.map { prefs ->
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

    fun catalogPreferencesForProfile(profileId: Int): Flow<MDBListCatalogPreferences> =
        store(profileId).data.map { prefs ->
            MDBListCatalogPreferences(
                hiddenPersonalListKeys = prefs[hiddenPersonalListKeysKey] ?: emptySet(),
                selectedTopListKeys = prefs[selectedTopListKeysKey] ?: emptySet(),
                catalogOrder = parseCatalogOrder(prefs[catalogOrderCsvKey])
            )
        }

    suspend fun setEnabled(enabled: Boolean, profileId: Int? = null) {
        store(profileId ?: profileManager.activeProfileId.value).edit { it[enabledKey] = enabled }
    }

    suspend fun setApiKey(apiKey: String, profileId: Int? = null) {
        store(profileId ?: profileManager.activeProfileId.value).edit { it[apiKeyKey] = apiKey.trim() }
    }

    suspend fun setShowTrakt(enabled: Boolean, profileId: Int? = null) {
        store(profileId ?: profileManager.activeProfileId.value).edit { it[showTraktKey] = enabled }
    }

    suspend fun setShowImdb(enabled: Boolean, profileId: Int? = null) {
        store(profileId ?: profileManager.activeProfileId.value).edit { it[showImdbKey] = enabled }
    }

    suspend fun setShowTmdb(enabled: Boolean, profileId: Int? = null) {
        store(profileId ?: profileManager.activeProfileId.value).edit { it[showTmdbKey] = enabled }
    }

    suspend fun setShowLetterboxd(enabled: Boolean, profileId: Int? = null) {
        store(profileId ?: profileManager.activeProfileId.value).edit { it[showLetterboxdKey] = enabled }
    }

    suspend fun setShowTomatoes(enabled: Boolean, profileId: Int? = null) {
        store(profileId ?: profileManager.activeProfileId.value).edit { it[showTomatoesKey] = enabled }
    }

    suspend fun setShowAudience(enabled: Boolean, profileId: Int? = null) {
        store(profileId ?: profileManager.activeProfileId.value).edit { it[showAudienceKey] = enabled }
    }

    suspend fun setShowMetacritic(enabled: Boolean, profileId: Int? = null) {
        store(profileId ?: profileManager.activeProfileId.value).edit { it[showMetacriticKey] = enabled }
    }

    suspend fun setPersonalListEnabled(
        listKey: String,
        enabled: Boolean,
        profileId: Int? = null
    ) {
        val key = listKey.trim()
        if (key.isBlank()) return
        store(profileId ?: profileManager.activeProfileId.value).edit { prefs ->
            val current = prefs[hiddenPersonalListKeysKey] ?: emptySet()
            prefs[hiddenPersonalListKeysKey] = if (enabled) current - key else current + key
        }
    }

    suspend fun setTopListSelected(
        listKey: String,
        selected: Boolean,
        profileId: Int? = null
    ) {
        val key = listKey.trim()
        if (key.isBlank()) return
        store(profileId ?: profileManager.activeProfileId.value).edit { prefs ->
            val current = prefs[selectedTopListKeysKey] ?: emptySet()
            prefs[selectedTopListKeysKey] = if (selected) current + key else current - key
        }
    }

    suspend fun setCatalogPreferences(
        hiddenPersonalListKeys: Set<String>,
        selectedTopListKeys: Set<String>,
        catalogOrder: List<String>,
        profileId: Int? = null
    ) {
        store(profileId ?: profileManager.activeProfileId.value).edit { prefs ->
            prefs[hiddenPersonalListKeysKey] = hiddenPersonalListKeys.filter { it.isNotBlank() }.toSet()
            prefs[selectedTopListKeysKey] = selectedTopListKeys.filter { it.isNotBlank() }.toSet()
            val sanitizedOrder = catalogOrder
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
            if (sanitizedOrder.isEmpty()) {
                prefs.remove(catalogOrderCsvKey)
            } else {
                prefs[catalogOrderCsvKey] = sanitizedOrder.joinToString(",")
            }
        }
    }

    suspend fun moveCatalog(
        listKey: String,
        direction: Int,
        availableKeys: Set<String>,
        profileId: Int? = null
    ) {
        val key = listKey.trim()
        if (key.isBlank() || direction == 0 || key !in availableKeys) return
        store(profileId ?: profileManager.activeProfileId.value).edit { prefs ->
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
        val availableByCanonical = availableKeys.associateBy { canonicalMDBListKey(it) }
        val uniqueKnown = rawOrder.asSequence()
            .map { availableByCanonical[canonicalMDBListKey(it)] ?: "" }
            .filter { it.isNotBlank() }
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

private fun canonicalMDBListKey(value: String): String {
    val trimmed = value.trim()
    if (trimmed.isBlank()) return ""
    val prefix = trimmed.substringBefore(':').lowercase()
    val payload = trimmed.substringAfter(':', "")
    val listId = payload.substringAfterLast('/').trim().lowercase()
    return if (prefix.isBlank() || listId.isBlank()) {
        trimmed.lowercase()
    } else {
        "$prefix:$listId"
    }
}
