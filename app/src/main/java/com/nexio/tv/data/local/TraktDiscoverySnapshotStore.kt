package com.nexio.tv.data.local

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import com.nexio.tv.core.profile.ProfileManager
import com.nexio.tv.core.sync.profilePrefsName
import com.nexio.tv.data.repository.TraktCustomListCatalog
import com.nexio.tv.data.repository.TraktDiscoverySnapshot
import com.nexio.tv.data.repository.TraktPopularListOption
import com.nexio.tv.data.repository.TraktRecommendationRef
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.RailItemPreview
import com.nexio.tv.domain.model.toLegacyRailItemPreview
import com.nexio.tv.domain.model.toMetaPreview
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TraktDiscoverySnapshotStore private constructor(
    private val context: Context,
    private val activeProfileId: () -> Int,
    private val injectedProfileManager: ProfileManager?
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        profileManager: ProfileManager
    ) : this(
        context = context,
        activeProfileId = { profileManager.activeProfileId.value },
        injectedProfileManager = profileManager
    )

    constructor(context: Context) : this(
        context = context,
        activeProfileId = { 1 },
        injectedProfileManager = null
    )

    companion object {
        private const val TAG = "TraktDiscoveryStore"
        internal const val BASE_PREFS_NAME = "trakt_discovery_snapshot"
        private const val SNAPSHOT_KEY = "snapshot"
    }

    private val gson = Gson()

    private fun injectedPrefsName(profileId: Int): String =
        profilePrefsName(BASE_PREFS_NAME, profileId)

    private fun prefsName(profileId: Int = activeProfileId()): String =
        if (injectedProfileManager != null) {
            injectedPrefsName(profileId)
        } else {
            profilePrefsName(BASE_PREFS_NAME, profileId)
        }

    fun read(profileId: Int = activeProfileId()): TraktDiscoverySnapshot? {
        return runCatching {
            val prefs = context.getSharedPreferences(prefsName(profileId), Context.MODE_PRIVATE)
            val raw = prefs.getString(SNAPSHOT_KEY, null)?.takeIf { it.isNotBlank() } ?: return null
            decode(raw)
        }.onFailure { error ->
            Log.w(TAG, "Failed to restore Trakt discovery snapshot", error)
            clear(profileId)
        }.getOrNull()
    }

    fun readActiveProfile(): TraktDiscoverySnapshot? {
        return read(activeProfileId())
    }

    fun write(
        snapshot: TraktDiscoverySnapshot,
        profileId: Int = activeProfileId()
    ) {
        runCatching {
            val prefs = context.getSharedPreferences(prefsName(profileId), Context.MODE_PRIVATE)
            val payload = JsonObject().apply {
                add("calendarItems", gson.toJsonTree(snapshot.calendarItemRecords))
                add("recommendationMovieItems", gson.toJsonTree(snapshot.recommendationMovieItemRecords))
                add("recommendationShowItems", gson.toJsonTree(snapshot.recommendationShowItemRecords))
                add("trendingMovieItems", gson.toJsonTree(snapshot.trendingMovieItemRecords))
                add("trendingShowItems", gson.toJsonTree(snapshot.trendingShowItemRecords))
                add("popularMovieItems", gson.toJsonTree(snapshot.popularMovieItemRecords))
                add("popularShowItems", gson.toJsonTree(snapshot.popularShowItemRecords))
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

    fun clear(profileId: Int = activeProfileId()) {
        runCatching {
            val prefs = context.getSharedPreferences(prefsName(profileId), Context.MODE_PRIVATE)
            prefs.edit().remove(SNAPSHOT_KEY).commit()
        }.onFailure { error ->
            Log.w(TAG, "Failed to clear Trakt discovery snapshot", error)
        }
    }

    private fun decode(raw: String): TraktDiscoverySnapshot? {
        val root = gson.fromJson(raw, JsonObject::class.java) ?: return null
        val canonical = TraktDiscoverySnapshot(
            calendarItemRecords = decodeRailItems(root, "calendarItems", "trakt_calendar"),
            recommendationMovieItemRecords = decodeRailItems(root, "recommendationMovieItems", "trakt_recommended_movies"),
            recommendationShowItemRecords = decodeRailItems(root, "recommendationShowItems", "trakt_recommended_shows"),
            trendingMovieItemRecords = decodeRailItems(root, "trendingMovieItems", "trakt_trending_movies"),
            trendingShowItemRecords = decodeRailItems(root, "trendingShowItems", "trakt_trending_shows"),
            popularMovieItemRecords = decodeRailItems(root, "popularMovieItems", "trakt_popular_movies"),
            popularShowItemRecords = decodeRailItems(root, "popularShowItems", "trakt_popular_shows"),
            customListCatalogs = decodeCustomCatalogs(root),
            popularLists = decodePopularLists(root),
            recommendationRefsByStatusKey = decodeMap(root, "recommendationRefsByStatusKey"),
            updatedAtMs = root.get("updatedAtMs")?.asLong ?: 0L
        )
        if (canonical.updatedAtMs > 0L ||
            canonical.calendarItemRecords.isNotEmpty() ||
            canonical.recommendationMovieItemRecords.isNotEmpty() ||
            canonical.recommendationShowItemRecords.isNotEmpty() ||
            canonical.trendingMovieItemRecords.isNotEmpty() ||
            canonical.trendingShowItemRecords.isNotEmpty() ||
            canonical.popularMovieItemRecords.isNotEmpty() ||
            canonical.popularShowItemRecords.isNotEmpty() ||
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

    private fun decodeRailItems(root: JsonObject, key: String, fallbackRailId: String): List<RailItemPreview> {
        val array = root.getAsJsonArray(key) ?: return emptyList()
        return array.mapNotNull { element ->
            val obj = element.asJsonObjectOrNull() ?: return@mapNotNull null
            if (obj.has("sourcePayloadHash") && obj.has("sourceItemId")) {
                runCatching { gson.fromJson(obj, RailItemPreview::class.java) }.getOrNull()
            } else {
                runCatching {
                    gson.fromJson(obj, MetaPreview::class.java)
                        ?.sanitizedForCache()
                        ?.toLegacyRailItemPreview(railId = fallbackRailId)
                }.getOrNull()
            }
        }
    }

    private fun decodeCustomCatalogs(root: JsonObject): List<TraktCustomListCatalog> {
        val array = root.getAsJsonArray("customListCatalogs") ?: return emptyList()
        return array.mapNotNull { element ->
            val obj = element.asJsonObjectOrNull() ?: return@mapNotNull null
            val catalogId = obj.cleanString("catalogId")
            val itemArray = obj.getAsJsonArray("itemRecords") ?: obj.getAsJsonArray("items")
            val itemRecords = if (itemArray == null) emptyList() else JsonObject().apply { add("items", itemArray) }
                .let { decodeRailItems(it, "items", catalogId) }
            TraktCustomListCatalog(
                key = obj.cleanString("key"),
                catalogId = catalogId,
                catalogName = obj.cleanString("catalogName"),
                type = runCatching { gson.fromJson(obj.get("type"), com.nexio.tv.domain.model.ContentType::class.java) }
                    .getOrDefault(com.nexio.tv.domain.model.ContentType.UNKNOWN),
                itemRecords = itemRecords
            )
        }
    }

    private fun decodePopularLists(root: JsonObject): List<TraktPopularListOption> {
        val array = root.getAsJsonArray("popularLists") ?: return emptyList()
        return array.mapNotNull { element ->
            val obj = element.asJsonObjectOrNull() ?: return@mapNotNull null
            val key = obj.cleanString("key")
            val userId = obj.cleanString("userId").ifBlank {
                key.substringBefore('/').trim()
            }
            val listId = obj.cleanString("listId").ifBlank {
                key.substringAfter('/', missingDelimiterValue = "").trim()
            }
            val normalizedKey = key.ifBlank {
                if (userId.isNotBlank() && listId.isNotBlank()) "$userId/$listId" else ""
            }
            if (normalizedKey.isBlank() || userId.isBlank() || listId.isBlank()) {
                return@mapNotNull null
            }

            TraktPopularListOption(
                key = normalizedKey,
                userId = userId,
                listId = listId,
                catalogIdBase = obj.cleanString("catalogIdBase").ifBlank {
                    "trakt_list_${slugify(normalizedKey)}"
                },
                title = obj.cleanString("title").ifBlank { normalizedKey },
                itemCount = obj.cleanInt("itemCount"),
                alternateKeys = decodeStringList(obj, "alternateKeys")
            )
        }
    }

    private fun decodeStringList(root: JsonObject, key: String): List<String> {
        val array = root.getAsJsonArray(key) ?: return emptyList()
        return array.mapNotNull { element ->
            if (element.isJsonNull) return@mapNotNull null
            runCatching { element.asString.trim() }
                .getOrDefault("")
                .takeIf { it.isNotBlank() && it != "undefined" && it != "null" }
        }
    }

    private fun decodeMap(root: JsonObject, key: String): Map<String, TraktRecommendationRef> {
        val value = root.get(key) ?: return emptyMap()
        val type = object : TypeToken<Map<String, TraktRecommendationRef>>() {}.type
        return gson.fromJson<Map<String, TraktRecommendationRef>>(value, type) ?: emptyMap()
    }

    private fun JsonElement.asJsonObjectOrNull(): JsonObject? =
        takeIf { it.isJsonObject }?.asJsonObject

    private fun JsonObject.cleanString(key: String): String {
        val value = get(key) ?: return ""
        if (value.isJsonNull) return ""
        return runCatching { value.asString.trim() }
            .getOrDefault("")
            .takeUnless { it == "undefined" || it == "null" }
            ?: ""
    }

    private fun JsonObject.cleanInt(key: String): Int {
        val value = get(key) ?: return 0
        if (value.isJsonNull) return 0
        return runCatching { value.asInt }.getOrDefault(0).coerceAtLeast(0)
    }

    private fun slugify(value: String): String {
        return value.lowercase()
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
            .ifBlank { "custom" }
    }
}
