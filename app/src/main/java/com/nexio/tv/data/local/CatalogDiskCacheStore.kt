package com.nexio.tv.data.local

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.nexio.tv.domain.model.CatalogRow
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CatalogDiskCacheStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "CatalogDiskCacheStore"
        private const val PREFS_NAME = "catalog_disk_cache_v1"
        private const val ENTRY_PREFIX = "catalog::"
    }

    data class Entry(
        val catalogRow: CatalogRow,
        val catalogVersionHash: String,
        val updatedAtMs: Long
    )

    private val gson = Gson()
    private val entryStore by lazy {
        FileBackedJsonObjectStore(
            file = File(context.filesDir, "catalog-disk-cache-v1/entries.json")
        ).also(::migrateLegacyPrefsIfNeeded)
    }

    fun read(cacheKey: String): Entry? {
        return runCatching {
            val root = entryStore.get(prefKey(cacheKey)) ?: return null
            val rowJson = root.get("catalogRow") ?: return null
            val row = gson.fromJson(rowJson, CatalogRow::class.java)
                ?.sanitizedForCache()
                ?: return null
            Entry(
                catalogRow = row,
                catalogVersionHash = root.get("catalogVersionHash")?.asString.orEmpty(),
                updatedAtMs = root.get("updatedAtMs")?.asLong ?: 0L
            )
        }.onFailure { error ->
            Log.w(TAG, "Failed to read catalog cache entry", error)
        }.getOrNull()
    }

    fun write(cacheKey: String, row: CatalogRow, catalogVersionHash: String) {
        runCatching {
            val payload = JsonObject().apply {
                add("catalogRow", gson.toJsonTree(row.sanitizedForCache()))
                addProperty("catalogVersionHash", catalogVersionHash)
                addProperty("updatedAtMs", System.currentTimeMillis())
            }
            entryStore.put(prefKey(cacheKey), payload)
        }.onFailure { error ->
            Log.w(TAG, "Failed to write catalog cache entry", error)
        }
    }

    fun remove(cacheKey: String) {
        runCatching {
            entryStore.remove(prefKey(cacheKey))
        }.onFailure { error ->
            Log.w(TAG, "Failed to remove catalog cache entry", error)
        }
    }

    private fun migrateLegacyPrefsIfNeeded(store: FileBackedJsonObjectStore) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val legacyKeys = prefs.all.keys
            .filter { key -> key.startsWith(ENTRY_PREFIX) }
        if (legacyKeys.isEmpty()) return

        val existingFileKeys = store.keys()
        val entriesToMigrate = linkedMapOf<String, JsonObject>()
        for (key in legacyKeys) {
            if (key in existingFileKeys) continue
            val raw = prefs.getString(key, null)?.takeIf { it.isNotBlank() } ?: continue
            val root = runCatching { gson.fromJson(raw, JsonObject::class.java) }
                .getOrNull()
                ?: continue
            entriesToMigrate[key] = root
        }

        if (entriesToMigrate.isEmpty() || store.putAll(entriesToMigrate)) {
            val editor = prefs.edit()
            for (key in legacyKeys) {
                editor.remove(key)
            }
            editor.commit()
        }
    }

    private fun prefKey(cacheKey: String): String {
        return "$ENTRY_PREFIX$cacheKey"
    }
}
