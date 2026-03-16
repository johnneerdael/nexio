package com.nexio.tv.data.local

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.nexio.tv.domain.model.CatalogRow
import dagger.hilt.android.qualifiers.ApplicationContext
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

    fun read(cacheKey: String): Entry? {
        return runCatching {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val raw = prefs.getString(prefKey(cacheKey), null)?.takeIf { it.isNotBlank() } ?: return null
            val root = gson.fromJson(raw, JsonObject::class.java) ?: return null
            val rowJson = root.get("catalogRow") ?: return null
            val row = gson.fromJson(rowJson, CatalogRow::class.java) ?: return null
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
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val payload = JsonObject().apply {
                add("catalogRow", gson.toJsonTree(row))
                addProperty("catalogVersionHash", catalogVersionHash)
                addProperty("updatedAtMs", System.currentTimeMillis())
            }
            prefs.edit().putString(prefKey(cacheKey), gson.toJson(payload)).apply()
        }.onFailure { error ->
            Log.w(TAG, "Failed to write catalog cache entry", error)
        }
    }

    fun remove(cacheKey: String) {
        runCatching {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().remove(prefKey(cacheKey)).apply()
        }.onFailure { error ->
            Log.w(TAG, "Failed to remove catalog cache entry", error)
        }
    }

    private fun prefKey(cacheKey: String): String {
        return "$ENTRY_PREFIX$cacheKey"
    }
}

