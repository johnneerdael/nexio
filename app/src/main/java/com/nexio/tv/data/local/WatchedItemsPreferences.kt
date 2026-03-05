package com.nexio.tv.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.google.gson.Gson
import com.nexio.tv.domain.model.WatchedItem
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.watchedItemsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "watched_items_preferences"
)

@Singleton
class WatchedItemsPreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "WatchedItemsPrefs"
    }

    private val dataStore = context.watchedItemsDataStore
    private fun store() = dataStore

    private val gson = Gson()
    private val watchedItemsKey = stringSetPreferencesKey("watched_items")

    private val allItems: Flow<List<WatchedItem>> = dataStore.data.map { preferences ->
        val raw = preferences[watchedItemsKey] ?: emptySet()
        raw.mapNotNull { json ->
            runCatching { gson.fromJson(json, WatchedItem::class.java) }.getOrNull()
        }
    }

    fun isWatched(contentId: String, season: Int? = null, episode: Int? = null): Flow<Boolean> {
        return allItems.map { items ->
            items.any { item ->
                item.contentId == contentId &&
                    item.season == season &&
                    item.episode == episode
            }
        }
    }

    fun getWatchedEpisodesForContent(contentId: String): Flow<Set<Pair<Int, Int>>> {
        return allItems.map { items ->
            items.filter { it.contentId == contentId && it.season != null && it.episode != null }
                .map { it.season!! to it.episode!! }
                .toSet()
        }
    }

    suspend fun markAsWatched(item: WatchedItem) {
        store().edit { preferences ->
            val current = preferences[watchedItemsKey] ?: emptySet()
            val filtered = current.filterNot { json ->
                runCatching {
                    gson.fromJson(json, WatchedItem::class.java)
                }.getOrNull()?.let { existing ->
                    existing.contentId == item.contentId &&
                        existing.season == item.season &&
                        existing.episode == item.episode
                } ?: false
            }
            preferences[watchedItemsKey] = filtered.toSet() + gson.toJson(item)
        }
    }

    suspend fun unmarkAsWatched(contentId: String, season: Int? = null, episode: Int? = null) {
        store().edit { preferences ->
            val current = preferences[watchedItemsKey] ?: emptySet()
            val filtered = current.filterNot { json ->
                runCatching {
                    gson.fromJson(json, WatchedItem::class.java)
                }.getOrNull()?.let { existing ->
                    existing.contentId == contentId &&
                        existing.season == season &&
                        existing.episode == episode
                } ?: false
            }
            preferences[watchedItemsKey] = filtered.toSet()
        }
    }

    suspend fun getAllItems(): List<WatchedItem> {
        return allItems.first()
    }

    suspend fun mergeRemoteItems(remoteItems: List<WatchedItem>) {
        store().edit { preferences ->
            val current = preferences[watchedItemsKey] ?: emptySet()
            val localItems = current.mapNotNull { json ->
                runCatching { gson.fromJson(json, WatchedItem::class.java) }.getOrNull()
            }
            val localKeys = localItems.map { Triple(it.contentId, it.season, it.episode) }.toSet()

            val newItems = remoteItems.filter { remote ->
                Triple(remote.contentId, remote.season, remote.episode) !in localKeys
            }

            if (newItems.isNotEmpty()) {
                preferences[watchedItemsKey] = current + newItems.map { gson.toJson(it) }.toSet()
            }
        }
    }

    suspend fun replaceWithRemoteItems(remoteItems: List<WatchedItem>) {
        store().edit { preferences ->
            val current = preferences[watchedItemsKey] ?: emptySet()
            if (remoteItems.isEmpty() && current.isNotEmpty()) {
                Log.w(TAG, "replaceWithRemoteItems: remote list empty while local has ${current.size} entries; preserving local watched items")
                return@edit
            }
            val deduped = linkedMapOf<Triple<String, Int?, Int?>, WatchedItem>()
            remoteItems.forEach { item ->
                deduped[Triple(item.contentId, item.season, item.episode)] = item
            }
            preferences[watchedItemsKey] = deduped.values
                .map { gson.toJson(it) }
                .toSet()
        }
    }
}
