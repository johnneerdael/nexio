package com.nexio.tv.data.local

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import com.nexio.tv.data.repository.ContinueWatchingSnapshot
import com.nexio.tv.data.repository.TraktProgressService
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
                add("nextUpItems", encodeNextUpItems(snapshot.nextUpItems))
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
            nextUpItems = decodeNextUpItems(root, "nextUpItems"),
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

    private fun encodeNextUpItems(
        items: List<TraktProgressService.CalendarShowEntry>
    ): JsonArray {
        return JsonArray().apply {
            items.forEach { entry ->
                val contentId = entry.contentId.trim()
                if (contentId.isBlank()) return@forEach
                add(
                    JsonObject().apply {
                        addProperty("contentId", contentId)
                        addProperty(
                            "contentType",
                            entry.contentType.takeIf { it.isNotBlank() } ?: "series"
                        )
                        addProperty("name", entry.name)
                        addProperty("season", entry.season)
                        addProperty("episode", entry.episode)
                        entry.episodeTitle?.let { addProperty("episodeTitle", it) }
                        addProperty(
                            "videoId",
                            entry.videoId.takeIf { it.isNotBlank() }
                                ?: "$contentId:${entry.season}:${entry.episode}"
                        )
                        entry.firstAired?.let { addProperty("firstAired", it) }
                        addProperty("firstAiredMs", entry.firstAiredMs)
                        entry.poster?.let { addProperty("poster", it) }
                        entry.backdrop?.let { addProperty("backdrop", it) }
                        entry.logo?.let { addProperty("logo", it) }
                        entry.traktShowId?.let { addProperty("traktShowId", it) }
                        entry.traktEpisodeId?.let { addProperty("traktEpisodeId", it) }
                    }
                )
            }
        }
    }

    private fun decodeNextUpItems(
        root: JsonObject,
        key: String
    ): List<TraktProgressService.CalendarShowEntry> {
        val array = root.getAsJsonArray(key) ?: return emptyList()
        if (array.size() == 0) return emptyList()

        val canonical = array.mapNotNull { element ->
            val obj = runCatching { element.asJsonObject }.getOrNull() ?: return@mapNotNull null
            decodeNextUpItemObject(obj)
        }
        if (canonical.isNotEmpty()) {
            return canonical
        }

        val legacyType = object : TypeToken<List<TraktProgressService.CalendarShowEntry>>() {}.type
        val legacy = runCatching {
            gson.fromJson<List<TraktProgressService.CalendarShowEntry>>(array, legacyType).orEmpty()
        }.getOrDefault(emptyList())
        return legacy.mapNotNull(::normalizeNextUpEntry)
    }

    private fun decodeNextUpItemObject(
        obj: JsonObject
    ): TraktProgressService.CalendarShowEntry? {
        val contentId = obj.stringOrNull("contentId")?.trim().orEmpty()
        if (contentId.isBlank()) return null

        val season = obj.intOrNull("season")?.takeIf { it > 0 } ?: return null
        val episode = obj.intOrNull("episode")?.takeIf { it > 0 } ?: return null
        val contentType = obj.stringOrNull("contentType")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: "series"
        val name = obj.stringOrNull("name")
            ?.takeIf { it.isNotBlank() }
            ?: contentId
        val videoId = obj.stringOrNull("videoId")
            ?.takeIf { it.isNotBlank() }
            ?: "$contentId:$season:$episode"

        return TraktProgressService.CalendarShowEntry(
            contentId = contentId,
            contentType = contentType,
            name = name,
            season = season,
            episode = episode,
            episodeTitle = obj.stringOrNull("episodeTitle"),
            videoId = videoId,
            firstAired = obj.stringOrNull("firstAired"),
            firstAiredMs = obj.longOrNull("firstAiredMs") ?: 0L,
            poster = obj.stringOrNull("poster"),
            backdrop = obj.stringOrNull("backdrop"),
            logo = obj.stringOrNull("logo"),
            traktShowId = obj.intOrNull("traktShowId"),
            traktEpisodeId = obj.intOrNull("traktEpisodeId")
        )
    }

    private fun normalizeNextUpEntry(
        entry: TraktProgressService.CalendarShowEntry
    ): TraktProgressService.CalendarShowEntry? {
        return try {
            val contentId = entry.contentId.trim()
            if (contentId.isBlank()) return null
            val season = entry.season.takeIf { it > 0 } ?: return null
            val episode = entry.episode.takeIf { it > 0 } ?: return null
            entry.copy(
                contentId = contentId,
                contentType = entry.contentType.takeIf { it.isNotBlank() } ?: "series",
                name = entry.name.takeIf { it.isNotBlank() } ?: contentId,
                videoId = entry.videoId.takeIf { it.isNotBlank() } ?: "$contentId:$season:$episode"
            )
        } catch (_: Throwable) {
            null
        }
    }

    private fun JsonObject.stringOrNull(key: String): String? {
        return runCatching {
            get(key)?.takeIf { !it.isJsonNull }?.asString
        }.getOrNull()
    }

    private fun JsonObject.intOrNull(key: String): Int? {
        return runCatching {
            get(key)?.takeIf { !it.isJsonNull }?.asInt
        }.getOrNull()
    }

    private fun JsonObject.longOrNull(key: String): Long? {
        return runCatching {
            get(key)?.takeIf { !it.isJsonNull }?.asLong
        }.getOrNull()
    }
}
