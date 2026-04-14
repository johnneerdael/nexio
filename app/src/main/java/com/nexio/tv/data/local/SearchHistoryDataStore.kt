package com.nexio.tv.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.searchHistoryDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "search_history"
)

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
class SearchHistoryDataStore internal constructor(
    private val dataStore: DataStore<Preferences>,
    private val gson: Gson = Gson()
) {
    @Inject constructor(
        @ApplicationContext context: Context
    ) : this(context.searchHistoryDataStore)

    private val recentSearchesKey = stringPreferencesKey("recent_searches")
    private val searchHistoryListType = object : TypeToken<List<String>>() {}.type

    val recentSearches: Flow<List<String>> = dataStore.data.map { prefs ->
        decodeSearchHistory(prefs[recentSearchesKey])
    }

    suspend fun saveRecentSearch(
        query: String,
        maxItems: Int = DEFAULT_MAX_RECENT_SEARCHES
    ) {
        val normalized = query.trim()
        if (normalized.isEmpty()) return

        val updated = nextSearchHistory(
            current = recentSearches.first(),
            query = normalized,
            maxItems = maxItems
        )

        dataStore.edit { prefs ->
            prefs[recentSearchesKey] = gson.toJson(updated)
        }
    }

    suspend fun clearRecentSearches() {
        dataStore.edit { prefs ->
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
