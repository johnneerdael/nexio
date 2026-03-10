package com.nexio.tv.data.local

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import com.nexio.tv.data.repository.ContinueWatchingSnapshot
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ContinueWatchingSnapshotStore @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        private const val TAG = "ContinueWatchingStore"
        private const val PREFS_NAME = "continue_watching_snapshot"
        private const val SNAPSHOT_KEY = "snapshot"
    }

    private val gson = Gson()

    fun read(): ContinueWatchingSnapshot? {
        return runCatching {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val raw = prefs.getString(SNAPSHOT_KEY, null)?.takeIf { it.isNotBlank() } ?: return null
            decode(raw)
        }.onFailure { error ->
            Log.w(TAG, "Failed to restore continue watching snapshot", error)
            clear()
        }.getOrNull()
    }

    fun write(snapshot: ContinueWatchingSnapshot) {
        runCatching {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val payload = JsonObject().apply {
                add("movieProgressItems", gson.toJsonTree(snapshot.movieProgressItems))
                add("nextUpItems", gson.toJsonTree(snapshot.nextUpItems))
                addProperty("updatedAtMs", snapshot.updatedAtMs)
            }
            prefs.edit().putString(SNAPSHOT_KEY, gson.toJson(payload)).apply()
        }.onFailure { error ->
            Log.w(TAG, "Failed to persist continue watching snapshot", error)
        }
    }

    fun clear() {
        runCatching {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().remove(SNAPSHOT_KEY).apply()
        }.onFailure { error ->
            Log.w(TAG, "Failed to clear continue watching snapshot", error)
        }
    }

    private fun decode(raw: String): ContinueWatchingSnapshot? {
        val root = gson.fromJson(raw, JsonObject::class.java) ?: return null
        val canonical = ContinueWatchingSnapshot(
            movieProgressItems = decodeArray(root, "movieProgressItems"),
            nextUpItems = decodeArray(root, "nextUpItems"),
            updatedAtMs = root.get("updatedAtMs")?.asLong ?: 0L
        )
        if (
            canonical.updatedAtMs > 0L ||
            canonical.movieProgressItems.isNotEmpty() ||
            canonical.nextUpItems.isNotEmpty()
        ) {
            return canonical
        }

        return runCatching {
            gson.fromJson(raw, ContinueWatchingSnapshot::class.java)
        }.getOrNull()
    }

    private inline fun <reified T> decodeArray(root: JsonObject, key: String): List<T> {
        val array = root.getAsJsonArray(key) ?: return emptyList()
        val type = object : TypeToken<List<T>>() {}.type
        return gson.fromJson<List<T>>(array, type) ?: emptyList()
    }
}
