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
        decodeWatchedItems(preferences[watchedItemsKey] ?: emptySet())
    }

    fun isWatched(contentId: String, season: Int? = null, episode: Int? = null): Flow<Boolean> {
        return allItems.map { items ->
            watchedItemsContainMatch(
                items = items,
                contentIds = listOf(contentId),
                season = season,
                episode = episode
            )
        }
    }

    fun getWatchedEpisodesForContent(contentId: String): Flow<Set<Pair<Int, Int>>> {
        return allItems.map { items ->
            watchedEpisodesForContent(items = items, contentIds = listOf(contentId))
        }
    }

    suspend fun markAsWatched(item: WatchedItem) {
        markAsWatched(item = item, contentIds = listOf(item.contentId))
    }

    suspend fun markAsWatched(item: WatchedItem, contentIds: Collection<String>) {
        store().edit { preferences ->
            val currentItems = decodeWatchedItems(preferences[watchedItemsKey] ?: emptySet())
            val updated = upsertWatchedItem(
                items = currentItems,
                item = item,
                contentIds = contentIds
            )
            preferences[watchedItemsKey] = encodeWatchedItems(updated)
        }
    }

    suspend fun unmarkAsWatched(contentId: String, season: Int? = null, episode: Int? = null) {
        unmarkAsWatched(
            contentIds = listOf(contentId),
            season = season,
            episode = episode
        )
    }

    suspend fun unmarkAsWatched(
        contentIds: Collection<String>,
        season: Int? = null,
        episode: Int? = null
    ) {
        store().edit { preferences ->
            val currentItems = decodeWatchedItems(preferences[watchedItemsKey] ?: emptySet())
            val updated = removeWatchedItems(
                items = currentItems,
                contentIds = contentIds,
                season = season,
                episode = episode
            )
            preferences[watchedItemsKey] = encodeWatchedItems(updated)
        }
    }

    suspend fun getAllItems(): List<WatchedItem> {
        return allItems.first()
    }

    suspend fun mergeRemoteItems(remoteItems: List<WatchedItem>) {
        store().edit { preferences ->
            val currentItems = decodeWatchedItems(preferences[watchedItemsKey] ?: emptySet())
            val localKeys = currentItems.map { Triple(it.contentId, it.season, it.episode) }.toSet()

            val newItems = remoteItems.filter { remote ->
                Triple(remote.contentId, remote.season, remote.episode) !in localKeys
            }

            if (newItems.isNotEmpty()) {
                preferences[watchedItemsKey] = encodeWatchedItems(currentItems + newItems)
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
            preferences[watchedItemsKey] = encodeWatchedItems(deduped.values)
        }
    }

    private fun decodeWatchedItems(rawItems: Set<String>): List<WatchedItem> {
        return rawItems.mapNotNull { json ->
            runCatching { gson.fromJson(json, WatchedItem::class.java) }.getOrNull()
        }
    }

    private fun encodeWatchedItems(items: Collection<WatchedItem>): Set<String> {
        return items.map { gson.toJson(it) }.toSet()
    }
}

internal fun watchedItemsContainMatch(
    items: Collection<WatchedItem>,
    contentIds: Collection<String>,
    season: Int? = null,
    episode: Int? = null
): Boolean {
    val aliases = expandWatchedItemContentIds(contentIds)
    if (aliases.isEmpty()) return false
    return items.any { existing ->
        existing.matchesWatchedItemAliases(aliases) &&
            existing.season == season &&
            existing.episode == episode
    }
}

internal fun watchedEpisodesForContent(
    items: Collection<WatchedItem>,
    contentIds: Collection<String>
): Set<Pair<Int, Int>> {
    val aliases = expandWatchedItemContentIds(contentIds)
    if (aliases.isEmpty()) return emptySet()
    return items.filter { existing ->
        existing.matchesWatchedItemAliases(aliases) &&
            existing.season != null &&
            existing.episode != null
    }.map { existing ->
        existing.season!! to existing.episode!!
    }.toSet()
}

internal fun upsertWatchedItem(
    items: Collection<WatchedItem>,
    item: WatchedItem,
    contentIds: Collection<String> = listOf(item.contentId)
): List<WatchedItem> {
    return removeWatchedItems(
        items = items,
        contentIds = contentIds,
        season = item.season,
        episode = item.episode
    ) + item
}

internal fun removeWatchedItems(
    items: Collection<WatchedItem>,
    contentIds: Collection<String>,
    season: Int? = null,
    episode: Int? = null
): List<WatchedItem> {
    val aliases = expandWatchedItemContentIds(contentIds)
    if (aliases.isEmpty()) return items.toList()
    val clearWholeShow = season == null && episode == null
    return items.filterNot { existing ->
        existing.matchesWatchedItemAliases(aliases) &&
            (clearWholeShow || (existing.season == season && existing.episode == episode))
    }
}

private fun expandWatchedItemContentIds(contentIds: Collection<String>): Set<String> {
    return buildSet {
        contentIds.forEach { contentId ->
            addAll(expandSeriesContentIdAliases(listOf(contentId)))
        }
    }
}

private fun WatchedItem.matchesWatchedItemAliases(targetAliases: Set<String>): Boolean {
    return expandWatchedItemContentIds(listOf(contentId)).any { it in targetAliases }
}
