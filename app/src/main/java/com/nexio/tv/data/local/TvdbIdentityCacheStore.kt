package com.nexio.tv.data.local

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.nexio.tv.core.tvdb.TvdbRemoteIdSource
import com.nexio.tv.core.tvdb.TvdbSeriesIdentity
import com.nexio.tv.core.tvdb.normalizeTvdbRemoteIdValue
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TvdbIdentityCacheStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "TvdbIdentityCacheStore"
        private const val PREFS_NAME = "tvdb_identity_cache_v1"
        private const val KEY_PREFIX = "tvdb_identity::"
        private const val CACHE_SCHEMA_VERSION = 1
    }

    private val gson = Gson()

    fun read(source: TvdbRemoteIdSource, value: String): TvdbSeriesIdentity? {
        val key = cacheKey(source, value)
        return runCatching {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val raw = prefs.getString(key, null)?.takeIf { it.isNotBlank() } ?: return null
            val root = gson.fromJson(raw, JsonObject::class.java) ?: return null
            val schemaVersion = root.get("schemaVersion")?.asInt ?: 0
            if (schemaVersion != CACHE_SCHEMA_VERSION) return null
            gson.fromJson(root.get("value"), TvdbSeriesIdentity::class.java)
        }.onFailure { error ->
            Log.w(TAG, "Failed to read TVDB identity cache entry", error)
        }.getOrNull()
    }

    fun write(source: TvdbRemoteIdSource, value: String, identity: TvdbSeriesIdentity) {
        val key = cacheKey(source, value)
        runCatching {
            val payload = JsonObject().apply {
                addProperty("schemaVersion", CACHE_SCHEMA_VERSION)
                addProperty("updatedAtMs", System.currentTimeMillis())
                add("value", gson.toJsonTree(identity))
            }
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putString(key, gson.toJson(payload)).apply()
        }.onFailure { error ->
            Log.w(TAG, "Failed to write TVDB identity cache entry", error)
        }
    }

    private fun cacheKey(source: TvdbRemoteIdSource, value: String): String {
        val normalizedValue = normalizeTvdbRemoteIdValue(value, source)
        return "$KEY_PREFIX$source:$normalizedValue"
    }
}
