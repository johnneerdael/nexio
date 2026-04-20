package com.nexio.tv.data.local

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import com.nexio.tv.core.profile.ProfileManager
import com.nexio.tv.core.sync.profilePrefsName
import com.nexio.tv.core.locale.AppLocaleResolver
import com.nexio.tv.core.tvdb.TvdbAirAvailabilityDiagnosticReason
import com.nexio.tv.core.tvdb.TvdbAirAvailabilityPrecision
import com.nexio.tv.data.repository.ContinueWatchingSnapshot
import com.nexio.tv.data.repository.TrackingNextUpEntry
import com.nexio.tv.domain.model.WatchProgress
import com.nexio.tv.domain.model.HomeDisplayMetadata
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ContinueWatchingSnapshotStore private constructor(
    private val context: Context,
    private val metadataDiskCacheStore: MetadataDiskCacheStore,
    private val activeProfileId: () -> Int,
    private val injectedProfileManager: ProfileManager?
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        metadataDiskCacheStore: MetadataDiskCacheStore,
        profileManager: ProfileManager
    ) : this(
        context = context,
        metadataDiskCacheStore = metadataDiskCacheStore,
        activeProfileId = { profileManager.activeProfileId.value },
        injectedProfileManager = profileManager
    )

    constructor(
        context: Context,
        metadataDiskCacheStore: MetadataDiskCacheStore
    ) : this(
        context = context,
        metadataDiskCacheStore = metadataDiskCacheStore,
        activeProfileId = { 1 },
        injectedProfileManager = null
    )

    companion object {
        private const val TAG = "ContinueWatchingStore"
        internal const val BASE_PREFS_NAME = "continue_watching_snapshot"
        private const val SNAPSHOT_KEY = "snapshot"
        private const val SCHEMA_VERSION = 5
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

    fun read(profileId: Int = activeProfileId()): ContinueWatchingSnapshot? {
        return runCatching {
            val prefs = context.getSharedPreferences(prefsName(profileId), Context.MODE_PRIVATE)
            val raw = prefs.getString(SNAPSHOT_KEY, null)?.takeIf { it.isNotBlank() } ?: return null
            decode(raw)
        }.onFailure { error ->
            Log.w(TAG, "Failed to restore continue watching snapshot", error)
            clear()
        }.getOrNull()
    }

    fun write(
        snapshot: ContinueWatchingSnapshot,
        profileId: Int = activeProfileId()
    ) {
        runCatching {
            val prefs = context.getSharedPreferences(prefsName(profileId), Context.MODE_PRIVATE)
            val payload = JsonObject().apply {
                addProperty("schemaVersion", SCHEMA_VERSION)
                addProperty("languageEpoch", metadataDiskCacheStore.currentLanguageEpoch())
                addProperty("languageTag", currentLanguageTag())
                add("resumeItems", gson.toJsonTree(snapshot.resumeItems))
                add("nextUpItems", encodeNextUpItems(snapshot.nextUpItems))
                add("traktUpNextItems", encodeNextUpItems(snapshot.traktUpNextItems))
                add("scheduledReemit", encodeNextUpItems(snapshot.scheduledReemit))
                add("displayMetadataByItemKey", gson.toJsonTree(snapshot.displayMetadataByItemKey))
                addProperty("updatedAtMs", snapshot.updatedAtMs)
            }
            prefs.edit().putString(SNAPSHOT_KEY, gson.toJson(payload)).apply()
        }.onFailure { error ->
            Log.w(TAG, "Failed to persist continue watching snapshot", error)
        }
    }

    fun clear(profileId: Int = activeProfileId()) {
        runCatching {
            val prefs = context.getSharedPreferences(prefsName(profileId), Context.MODE_PRIVATE)
            prefs.edit().remove(SNAPSHOT_KEY).apply()
        }.onFailure { error ->
            Log.w(TAG, "Failed to clear continue watching snapshot", error)
        }
    }

    private fun decode(raw: String): ContinueWatchingSnapshot? {
        val root = gson.fromJson(raw, JsonObject::class.java) ?: return null
        val schemaVersion = root.get("schemaVersion")?.asInt ?: 0
        if (schemaVersion != SCHEMA_VERSION) {
            return null
        }
        val languageTag = root.get("languageTag")?.asString?.trim().orEmpty()
        if (languageTag.isBlank() || languageTag != currentLanguageTag()) {
            return null
        }
        val canonical = ContinueWatchingSnapshot(
            resumeItems = decodeArray<WatchProgress>(root, "resumeItems").ifEmpty {
                decodeArray(root, "movieProgressItems")
            },
            nextUpItems = decodeNextUpItems(root, "nextUpItems"),
            traktUpNextItems = decodeNextUpItems(root, "traktUpNextItems").ifEmpty {
                decodeNextUpItems(root, "nextUpItems")
            },
            scheduledReemit = decodeNextUpItems(root, "scheduledReemit"),
            displayMetadataByItemKey = decodeDisplayMetadata(root, "displayMetadataByItemKey"),
            updatedAtMs = root.get("updatedAtMs")?.asLong ?: 0L
        )
        if (
            canonical.updatedAtMs > 0L ||
            canonical.resumeItems.isNotEmpty() ||
            canonical.nextUpItems.isNotEmpty() ||
            canonical.traktUpNextItems.isNotEmpty() ||
            canonical.displayMetadataByItemKey.isNotEmpty()
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
        items: List<TrackingNextUpEntry>
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
                        addProperty("activityAtMs", entry.activityAtMs)
                        entry.poster?.let { addProperty("poster", it) }
                        entry.backdrop?.let { addProperty("backdrop", it) }
                        entry.logo?.let { addProperty("logo", it) }
                        entry.traktShowId?.let { addProperty("traktShowId", it) }
                        entry.traktEpisodeId?.let { addProperty("traktEpisodeId", it) }
                        entry.tvdbAvailabilityInstantMs
                            ?.takeIf { it > 0L }
                            ?.let { addProperty("tvdbAvailabilityInstantMs", it) }
                        if (entry.tvdbAvailabilityPrecision != TvdbAirAvailabilityPrecision.UNKNOWN) {
                            addProperty("tvdbAvailabilityPrecision", entry.tvdbAvailabilityPrecision.name)
                        }
                        entry.tvdbAvailabilitySourceZoneId?.let { addProperty("tvdbAvailabilitySourceZoneId", it) }
                        entry.tvdbAvailabilitySourcePolicy?.let { addProperty("tvdbAvailabilitySourcePolicy", it) }
                        entry.tvdbAvailabilityDiagnosticReason?.let {
                            addProperty("tvdbAvailabilityDiagnosticReason", it.name)
                        }
                        entry.tvdbAvailabilityDeviceLocalDateTime?.let {
                            addProperty("tvdbAvailabilityDeviceLocalDateTime", it)
                        }
                    }
                )
            }
        }
    }

    private fun decodeNextUpItems(
        root: JsonObject,
        key: String
    ): List<TrackingNextUpEntry> {
        val array = root.getAsJsonArray(key) ?: return emptyList()
        if (array.size() == 0) return emptyList()

        val canonical = array.mapNotNull { element ->
            val obj = runCatching { element.asJsonObject }.getOrNull() ?: return@mapNotNull null
            decodeNextUpItemObject(obj)
        }
        if (canonical.isNotEmpty()) {
            return canonical
        }

        val legacyType = object : TypeToken<List<TrackingNextUpEntry>>() {}.type
        val legacy = runCatching {
            gson.fromJson<List<TrackingNextUpEntry>>(array, legacyType).orEmpty()
        }.getOrDefault(emptyList())
        return legacy.mapNotNull(::normalizeNextUpEntry)
    }

    private fun decodeDisplayMetadata(
        root: JsonObject,
        key: String
    ): Map<String, HomeDisplayMetadata> {
        val obj = root.getAsJsonObject(key) ?: return emptyMap()
        val type = object : TypeToken<Map<String, HomeDisplayMetadata>>() {}.type
        return gson.fromJson<Map<String, HomeDisplayMetadata>>(obj, type)
            ?.mapValues { (_, metadata) -> metadata.sanitizedForCache() }
            ?: emptyMap()
    }

    private fun currentLanguageTag(): String {
        return AppLocaleResolver.resolveEffectiveAppLanguageTag(context)
    }

    private fun decodeNextUpItemObject(
        obj: JsonObject
    ): TrackingNextUpEntry? {
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

        return TrackingNextUpEntry(
            contentId = contentId,
            contentType = contentType,
            name = name,
            season = season,
            episode = episode,
            episodeTitle = obj.stringOrNull("episodeTitle"),
            videoId = videoId,
            firstAired = obj.stringOrNull("firstAired"),
            firstAiredMs = obj.longOrNull("firstAiredMs") ?: 0L,
            activityAtMs = obj.longOrNull("activityAtMs")
                ?: obj.longOrNull("firstAiredMs")
                ?: 0L,
            poster = obj.stringOrNull("poster"),
            backdrop = obj.stringOrNull("backdrop"),
            logo = obj.stringOrNull("logo"),
            traktShowId = obj.intOrNull("traktShowId"),
            traktEpisodeId = obj.intOrNull("traktEpisodeId"),
            tvdbAvailabilityInstantMs = obj.longOrNull("tvdbAvailabilityInstantMs"),
            tvdbAvailabilityPrecision = obj.tvdbAvailabilityPrecisionOrDefault(),
            tvdbAvailabilitySourceZoneId = obj.stringOrNull("tvdbAvailabilitySourceZoneId"),
            tvdbAvailabilitySourcePolicy = obj.stringOrNull("tvdbAvailabilitySourcePolicy"),
            tvdbAvailabilityDiagnosticReason = obj.tvdbAvailabilityDiagnosticReasonOrNull(),
            tvdbAvailabilityDeviceLocalDateTime = obj.stringOrNull("tvdbAvailabilityDeviceLocalDateTime")
        )
    }

    private fun normalizeNextUpEntry(
        entry: TrackingNextUpEntry
    ): TrackingNextUpEntry? {
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

    private fun JsonObject.tvdbAvailabilityPrecisionOrDefault(): TvdbAirAvailabilityPrecision {
        val value = stringOrNull("tvdbAvailabilityPrecision") ?: return TvdbAirAvailabilityPrecision.UNKNOWN
        return runCatching {
            TvdbAirAvailabilityPrecision.valueOf(value)
        }.getOrDefault(TvdbAirAvailabilityPrecision.UNKNOWN)
    }

    private fun JsonObject.tvdbAvailabilityDiagnosticReasonOrNull(): TvdbAirAvailabilityDiagnosticReason? {
        val value = stringOrNull("tvdbAvailabilityDiagnosticReason") ?: return null
        return runCatching {
            TvdbAirAvailabilityDiagnosticReason.valueOf(value)
        }.getOrNull()
    }
}
