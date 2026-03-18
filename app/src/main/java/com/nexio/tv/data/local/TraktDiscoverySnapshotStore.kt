package com.nexio.tv.data.local

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import com.nexio.tv.data.repository.TraktCustomListCatalog
import com.nexio.tv.data.repository.TraktDiscoverySnapshot
import com.nexio.tv.data.repository.TraktPopularListOption
import com.nexio.tv.data.repository.TraktRecommendationRef
import com.nexio.tv.domain.model.MetaPreview
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TraktDiscoverySnapshotStore @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        private const val TAG = "TraktDiscoveryStore"
        private const val PREFS_NAME = "trakt_discovery_snapshot"
        private const val SNAPSHOT_KEY = "snapshot"
    }

    private val gson = Gson()

    fun read(): TraktDiscoverySnapshot? {
        return runCatching {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val raw = prefs.getString(SNAPSHOT_KEY, null)?.takeIf { it.isNotBlank() } ?: return null
            decode(raw)
        }.onFailure { error ->
            Log.w(TAG, "Failed to restore Trakt discovery snapshot", error)
            clear()
        }.getOrNull()
    }

    fun write(snapshot: TraktDiscoverySnapshot) {
        runCatching {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val payload = JsonObject().apply {
                add("calendarItems", gson.toJsonTree(snapshot.calendarItems))
                add("recommendationMovieItems", gson.toJsonTree(snapshot.recommendationMovieItems))
                add("recommendationShowItems", gson.toJsonTree(snapshot.recommendationShowItems))
                add("trendingMovieItems", gson.toJsonTree(snapshot.trendingMovieItems))
                add("trendingShowItems", gson.toJsonTree(snapshot.trendingShowItems))
                add("popularMovieItems", gson.toJsonTree(snapshot.popularMovieItems))
                add("popularShowItems", gson.toJsonTree(snapshot.popularShowItems))
                add("customListCatalogs", gson.toJsonTree(snapshot.customListCatalogs))
                add("popularLists", gson.toJsonTree(snapshot.popularLists))
                add("recommendationRefsByStatusKey", gson.toJsonTree(snapshot.recommendationRefsByStatusKey))
                addProperty("updatedAtMs", snapshot.updatedAtMs)
            }
            prefs.edit().putString(SNAPSHOT_KEY, gson.toJson(payload)).commit()
        }.onFailure { error ->
            Log.w(TAG, "Failed to persist Trakt discovery snapshot", error)
        }
    }

    fun clear() {
        runCatching {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().remove(SNAPSHOT_KEY).commit()
        }.onFailure { error ->
            Log.w(TAG, "Failed to clear Trakt discovery snapshot", error)
        }
    }

    private fun decode(raw: String): TraktDiscoverySnapshot? {
        val root = gson.fromJson(raw, JsonObject::class.java) ?: return null
        val canonical = TraktDiscoverySnapshot(
            calendarItems = decodeArray(root, "calendarItems"),
            recommendationMovieItems = decodeArray(root, "recommendationMovieItems"),
            recommendationShowItems = decodeArray(root, "recommendationShowItems"),
            trendingMovieItems = decodeArray(root, "trendingMovieItems"),
            trendingShowItems = decodeArray(root, "trendingShowItems"),
            popularMovieItems = decodeArray(root, "popularMovieItems"),
            popularShowItems = decodeArray(root, "popularShowItems"),
            customListCatalogs = decodeArray(root, "customListCatalogs"),
            popularLists = decodeArray(root, "popularLists"),
            recommendationRefsByStatusKey = decodeMap(root, "recommendationRefsByStatusKey"),
            updatedAtMs = root.get("updatedAtMs")?.asLong ?: 0L
        )
        if (canonical.updatedAtMs > 0L ||
            canonical.calendarItems.isNotEmpty() ||
            canonical.recommendationMovieItems.isNotEmpty() ||
            canonical.recommendationShowItems.isNotEmpty() ||
            canonical.trendingMovieItems.isNotEmpty() ||
            canonical.trendingShowItems.isNotEmpty() ||
            canonical.popularMovieItems.isNotEmpty() ||
            canonical.popularShowItems.isNotEmpty() ||
            canonical.customListCatalogs.isNotEmpty() ||
            canonical.popularLists.isNotEmpty() ||
            canonical.recommendationRefsByStatusKey.isNotEmpty()
        ) {
            return canonical
        }

        // Legacy payloads were stored via direct Gson reflection and may use obfuscated field names.
        return runCatching {
            gson.fromJson(raw, TraktDiscoverySnapshot::class.java)
        }.getOrNull()
    }

    private inline fun <reified T> decodeArray(root: JsonObject, key: String): List<T> {
        val array = root.getAsJsonArray(key) ?: return emptyList()
        val type = object : TypeToken<List<T>>() {}.type
        return gson.fromJson<List<T>>(array, type) ?: emptyList()
    }

    private fun decodeMap(root: JsonObject, key: String): Map<String, TraktRecommendationRef> {
        val value = root.get(key) ?: return emptyMap()
        val type = object : TypeToken<Map<String, TraktRecommendationRef>>() {}.type
        return gson.fromJson<Map<String, TraktRecommendationRef>>(value, type) ?: emptyMap()
    }
}
