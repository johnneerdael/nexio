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
class LibraryPreferences internal constructor(
    private val dataStore: DataStore<Preferences>
) {
    @Inject
    constructor(@ApplicationContext context: Context) : this(context.libraryPreferencesDataStore)

    companion object {
        private const val TAG = "LibraryPrefs"
    }

    private fun store() = dataStore

    private val gson = Gson()
    private val libraryItemsKey = stringSetPreferencesKey("library_items")

    val libraryItems: Flow<List<SavedLibraryItem>> = dataStore.data.map { preferences ->
        val raw = preferences[libraryItemsKey] ?: emptySet()
        raw.mapNotNull { json ->
            decodeSavedLibraryItem(json)
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
                decodeSavedLibraryItem(json)?.let { saved ->
                    saved.id == item.id && saved.type.equals(item.type, ignoreCase = true)
                } ?: false
            }
            val sanitizedItem = item.sanitizedOrNull() ?: return@edit
            val itemWithTimestamp = if (sanitizedItem.addedAt == 0L) {
                sanitizedItem.copy(addedAt = System.currentTimeMillis())
            } else {
                sanitizedItem
            }
            preferences[libraryItemsKey] = filtered.toSet() + gson.toJson(itemWithTimestamp)
        }
    }

    suspend fun removeItem(itemId: String, itemType: String) {
        store().edit { preferences ->
            val current = preferences[libraryItemsKey] ?: emptySet()
            val filtered = current.filterNot { json ->
                decodeSavedLibraryItem(json)?.let { saved ->
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
                val sanitized = item.sanitizedOrNull() ?: return@forEach
                dedupedRemote[sanitized.id to sanitized.type.lowercase()] = sanitized
            }
            preferences[libraryItemsKey] = dedupedRemote.values
                .map { gson.toJson(it) }
                .toSet()
        }
    }

    private fun decodeSavedLibraryItem(json: String): SavedLibraryItem? {
        return runCatching { gson.fromJson(json, SavedLibraryItem::class.java) }
            .getOrNull()
            ?.sanitizedOrNull()
    }

    private fun SavedLibraryItem.sanitizedOrNull(): SavedLibraryItem? {
        val cleanId = (id as String?)?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val cleanType = (type as String?)?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val cleanName = (name as String?)?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return copy(
            id = cleanId,
            type = cleanType,
            name = cleanName,
            posterShape = posterShape ?: com.nexio.tv.domain.model.PosterShape.POSTER,
            genres = genres.orEmpty(),
            addedAt = addedAt.coerceAtLeast(0L)
        )
    }
}
