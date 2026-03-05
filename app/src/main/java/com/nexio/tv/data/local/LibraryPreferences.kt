package com.nexio.tv.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.google.gson.Gson
import com.nexio.tv.domain.model.SavedLibraryItem
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.libraryPreferencesDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "library_preferences"
)

@Singleton
class LibraryPreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "LibraryPrefs"
    }

    private val dataStore = context.libraryPreferencesDataStore
    private fun store() = dataStore

    private val gson = Gson()
    private val libraryItemsKey = stringSetPreferencesKey("library_items")

    val libraryItems: Flow<List<SavedLibraryItem>> = dataStore.data.map { preferences ->
        val raw = preferences[libraryItemsKey] ?: emptySet()
        raw.mapNotNull { json ->
            runCatching { gson.fromJson(json, SavedLibraryItem::class.java) }.getOrNull()
        }
    }

    fun isInLibrary(itemId: String, itemType: String): Flow<Boolean> {
        return libraryItems.map { items ->
            items.any { it.id == itemId && it.type.equals(itemType, ignoreCase = true) }
        }
    }

    suspend fun addItem(item: SavedLibraryItem) {
        store().edit { preferences ->
            val current = preferences[libraryItemsKey] ?: emptySet()
            val filtered = current.filterNot { json ->
                runCatching {
                    gson.fromJson(json, SavedLibraryItem::class.java)
                }.getOrNull()?.let { saved ->
                    saved.id == item.id && saved.type.equals(item.type, ignoreCase = true)
                } ?: false
            }
            val itemWithTimestamp = if (item.addedAt == 0L) item.copy(addedAt = System.currentTimeMillis()) else item
            preferences[libraryItemsKey] = filtered.toSet() + gson.toJson(itemWithTimestamp)
        }
    }

    suspend fun removeItem(itemId: String, itemType: String) {
        store().edit { preferences ->
            val current = preferences[libraryItemsKey] ?: emptySet()
            val filtered = current.filterNot { json ->
                runCatching {
                    gson.fromJson(json, SavedLibraryItem::class.java)
                }.getOrNull()?.let { saved ->
                    saved.id == itemId && saved.type.equals(itemType, ignoreCase = true)
                } ?: false
            }
            preferences[libraryItemsKey] = filtered.toSet()
        }
    }

    suspend fun getAllItems(): List<SavedLibraryItem> {
        return libraryItems.first()
    }

    suspend fun mergeRemoteItems(remoteItems: List<SavedLibraryItem>) {
        store().edit { preferences ->
            val current = preferences[libraryItemsKey] ?: emptySet()
            if (remoteItems.isEmpty() && current.isNotEmpty()) {
                Log.w(TAG, "mergeRemoteItems: remote list empty while local has ${current.size} entries; preserving local library")
                return@edit
            }
            val dedupedRemote = linkedMapOf<Pair<String, String>, SavedLibraryItem>()
            remoteItems.forEach { item ->
                dedupedRemote[item.id to item.type.lowercase()] = item
            }
            preferences[libraryItemsKey] = dedupedRemote.values
                .map { gson.toJson(it) }
                .toSet()
        }
    }
}
