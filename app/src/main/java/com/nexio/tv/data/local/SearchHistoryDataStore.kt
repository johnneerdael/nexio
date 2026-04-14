package com.nexio.tv.data.local

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.nexio.tv.core.profile.ProfileManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

internal const val DEFAULT_MAX_RECENT_SEARCHES = 8

internal fun nextSearchHistory(
    current: List<String>,
    query: String,
    maxItems: Int = DEFAULT_MAX_RECENT_SEARCHES
): List<String> {
    val normalized = query.trim()
    if (normalized.isEmpty()) return current

    return normalizeSearchHistory(listOf(normalized) + current)
        .take(maxItems.coerceAtLeast(1))
}

internal fun normalizeSearchHistory(items: List<String>): List<String> {
    val seen = linkedSetOf<String>()
    return items.asSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .filter { value -> seen.add(value.lowercase()) }
        .toList()
}

@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class SearchHistoryDataStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager,
    private val gson: Gson = Gson()
) {
    companion object {
        private const val FEATURE = "search_history"
    }

    private val recentSearchesKey = stringPreferencesKey("recent_searches")
    private val searchHistoryListType = object : TypeToken<List<String>>() {}.type

    private fun store(profileId: Int = profileManager.activeProfileId.value) =
        factory.get(profileId, FEATURE)

    val recentSearches: Flow<List<String>> = profileManager.activeProfileId.flatMapLatest { pid ->
        store(pid).data.map { prefs -> decodeSearchHistory(prefs[recentSearchesKey]) }
    }

    suspend fun saveRecentSearch(
        query: String,
        maxItems: Int = DEFAULT_MAX_RECENT_SEARCHES
    ) {
        val normalized = query.trim()
        if (normalized.isEmpty()) return
        // Capture the profile ID once before entering the edit block so that a concurrent
        // profile switch cannot split the read (first()) and write (edit) across stores.
        // The single-lambda edit also makes the whole operation atomic within DataStore.
        val profileId = profileManager.activeProfileId.value
        store(profileId).edit { prefs ->
            val current = decodeSearchHistory(prefs[recentSearchesKey])
            val updated = nextSearchHistory(current = current, query = normalized, maxItems = maxItems)
            prefs[recentSearchesKey] = gson.toJson(updated)
        }
    }

    suspend fun clearRecentSearches() {
        store().edit { prefs ->
            prefs.remove(recentSearchesKey)
        }
    }

    private fun decodeSearchHistory(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            normalizeSearchHistory(gson.fromJson<List<String>>(raw, searchHistoryListType).orEmpty())
        }.getOrDefault(emptyList())
    }
}
