package com.nexio.tv.data.local

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import com.nexio.tv.domain.model.LibraryEntry
import com.nexio.tv.domain.model.LibraryListTab
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TraktLibrarySnapshotStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val metadataDiskCacheStore: MetadataDiskCacheStore
) {

    companion object {
        private const val TAG = "TraktLibraryStore"
        private const val PREFS_NAME = "trakt_library_snapshot"
        private const val SNAPSHOT_KEY = "snapshot"
        private const val SCHEMA_VERSION = 1
    }

    data class PersistedLibraryMetadata(
        val name: String? = null,
        val poster: String? = null,
        val background: String? = null,
        val logo: String? = null,
        val description: String? = null,
        val releaseInfo: String? = null,
        val imdbRating: Float? = null,
        val genres: List<String> = emptyList()
    )

    data class Snapshot(
        val listTabs: List<LibraryListTab> = emptyList(),
        val entriesByList: Map<String, List<LibraryEntry>> = emptyMap(),
        val metadataByContentKey: Map<String, PersistedLibraryMetadata> = emptyMap(),
        val updatedAtMs: Long = 0L
    )

    private val gson = Gson()

    fun read(): Snapshot? {
        return runCatching {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val raw = prefs.getString(SNAPSHOT_KEY, null)?.takeIf { it.isNotBlank() } ?: return null
            decodeSnapshot(raw)
        }.onFailure { error ->
            logWarning("Failed to restore Trakt library snapshot", error)
            clear()
        }.getOrNull()
    }

    fun write(snapshot: Snapshot) {
        runCatching {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val payload = JsonObject().apply {
                addProperty("schemaVersion", SCHEMA_VERSION)
                addProperty("languageEpoch", metadataDiskCacheStore.currentLanguageEpoch())
                add("listTabs", gson.toJsonTree(snapshot.listTabs))
                add("entriesByList", gson.toJsonTree(snapshot.entriesByList))
                add("metadataByContentKey", gson.toJsonTree(snapshot.metadataByContentKey))
                addProperty("updatedAtMs", snapshot.updatedAtMs)
            }
            prefs.edit().putString(SNAPSHOT_KEY, gson.toJson(payload)).commit()
        }.onFailure { error ->
            logWarning("Failed to persist Trakt library snapshot", error)
        }
    }

    fun clear() {
        runCatching {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().remove(SNAPSHOT_KEY).commit()
        }.onFailure { error ->
            logWarning("Failed to clear Trakt library snapshot", error)
        }
    }

    private fun decodeSnapshot(raw: String): Snapshot? {
        val root = gson.fromJson(raw, JsonObject::class.java) ?: return null
        val schemaVersion = root.get("schemaVersion")?.asInt ?: 0
        if (schemaVersion != SCHEMA_VERSION) return null

        val languageEpoch = root.get("languageEpoch")?.asInt ?: metadataDiskCacheStore.currentLanguageEpoch()
        if (languageEpoch != metadataDiskCacheStore.currentLanguageEpoch()) return null

        val listTabsType = object : TypeToken<List<LibraryListTab>>() {}.type
        val entriesByListType = object : TypeToken<Map<String, List<LibraryEntry>>>() {}.type
        val metadataByContentKeyType =
            object : TypeToken<Map<String, PersistedLibraryMetadata>>() {}.type

        return Snapshot(
            listTabs = gson.fromJson(root.get("listTabs"), listTabsType) ?: emptyList(),
            entriesByList = gson.fromJson(root.get("entriesByList"), entriesByListType) ?: emptyMap(),
            metadataByContentKey = gson.fromJson(root.get("metadataByContentKey"), metadataByContentKeyType)
                ?: emptyMap(),
            updatedAtMs = root.get("updatedAtMs")?.asLong ?: 0L
        )
    }

    private fun logWarning(message: String, error: Throwable) {
        runCatching { Log.w(TAG, message, error) }
    }
}
