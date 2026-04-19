package com.nexio.tv.data.local

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.nexio.tv.core.profile.ProfileManager
import com.nexio.tv.core.sync.profilePrefsName
import com.nexio.tv.domain.model.LibraryEntry
import com.nexio.tv.domain.model.LibraryListTab
import com.nexio.tv.domain.model.PosterShape
import com.nexio.tv.domain.model.TraktListPrivacy
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TraktLibrarySnapshotStore private constructor(
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
        private const val TAG = "TraktLibraryStore"
        internal const val BASE_PREFS_NAME = "trakt_library_snapshot"
        private const val SNAPSHOT_KEY = "snapshot"
        private const val SCHEMA_VERSION = 2
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

    private val profileManager: ProfileManager
        get() = injectedProfileManager ?: error("ProfileManager unavailable")

    private fun prefsName(profileId: Int = activeProfileId()): String =
        if (injectedProfileManager != null) {
            profilePrefsName(BASE_PREFS_NAME, profileId)
        } else {
            profilePrefsName(BASE_PREFS_NAME, profileId)
        }

    fun read(profileId: Int = activeProfileId()): Snapshot? {
        return runCatching {
            val prefs = context.getSharedPreferences(prefsName(profileId), Context.MODE_PRIVATE)
            val raw = prefs.getString(SNAPSHOT_KEY, null)?.takeIf { it.isNotBlank() } ?: return null
            decodeSnapshot(raw)?.also { snapshot ->
                logDebug(
                    "read success updatedAtMs=${snapshot.updatedAtMs} " +
                        "listTabs=${snapshot.listTabs.size} " +
                        "listsWithEntries=${snapshot.entriesByList.count { it.value.isNotEmpty() }} " +
                        "metadata=${snapshot.metadataByContentKey.size}"
                )
            }
        }.onFailure { error ->
            logWarning("Failed to restore Trakt library snapshot", error)
            clear(profileId)
        }.getOrNull()
    }

    fun write(snapshot: Snapshot, profileId: Int = activeProfileId()) {
        runCatching {
            val prefs = context.getSharedPreferences(prefsName(profileId), Context.MODE_PRIVATE)
            val payload = JsonObject().apply {
                addProperty("schemaVersion", SCHEMA_VERSION)
                addProperty("languageEpoch", metadataDiskCacheStore.currentLanguageEpoch())
                add("listTabs", encodeListTabs(snapshot.listTabs))
                add("entriesByList", encodeEntriesByList(snapshot.entriesByList))
                add("metadataByContentKey", encodeMetadata(snapshot.metadataByContentKey))
                addProperty("updatedAtMs", snapshot.updatedAtMs)
            }
            prefs.edit().putString(SNAPSHOT_KEY, gson.toJson(payload)).commit()
            logDebug(
                "write success updatedAtMs=${snapshot.updatedAtMs} " +
                    "listTabs=${snapshot.listTabs.size} " +
                    "listsWithEntries=${snapshot.entriesByList.count { it.value.isNotEmpty() }} " +
                    "metadata=${snapshot.metadataByContentKey.size} " +
                    "languageEpoch=${metadataDiskCacheStore.currentLanguageEpoch()}"
            )
        }.onFailure { error ->
            logWarning("Failed to persist Trakt library snapshot", error)
        }
    }

    fun clear(profileId: Int = activeProfileId()) {
        runCatching {
            val prefs = context.getSharedPreferences(prefsName(profileId), Context.MODE_PRIVATE)
            prefs.edit().remove(SNAPSHOT_KEY).commit()
            logDebug("clear success")
        }.onFailure { error ->
            logWarning("Failed to clear Trakt library snapshot", error)
        }
    }

    private fun decodeSnapshot(raw: String): Snapshot? {
        val root = gson.fromJson(raw, JsonObject::class.java) ?: return null
        val schemaVersion = root.get("schemaVersion")?.asInt ?: 0
        if (schemaVersion > SCHEMA_VERSION) {
            logDebug("decode ignored future schemaVersion=$schemaVersion current=$SCHEMA_VERSION")
            return null
        }

        return Snapshot(
            listTabs = decodeListTabs(root.get("listTabs")),
            entriesByList = decodeEntriesByList(root.get("entriesByList")),
            metadataByContentKey = decodeMetadata(root.get("metadataByContentKey")),
            updatedAtMs = root.longOrNull("updatedAtMs") ?: 0L
        )
    }

    private fun encodeListTabs(tabs: List<LibraryListTab>): JsonArray {
        return JsonArray().apply {
            tabs.forEach { tab ->
                add(
                    JsonObject().apply {
                        addProperty("key", tab.key)
                        addProperty("title", tab.title)
                        addProperty("type", tab.type.name)
                        tab.traktListId?.let { addProperty("traktListId", it) }
                        tab.slug?.let { addProperty("slug", it) }
                        tab.description?.let { addProperty("description", it) }
                        tab.privacy?.let { addProperty("privacy", it.apiValue) }
                        tab.sortBy?.let { addProperty("sortBy", it) }
                        tab.sortHow?.let { addProperty("sortHow", it) }
                    }
                )
            }
        }
    }

    private fun encodeEntriesByList(entriesByList: Map<String, List<LibraryEntry>>): JsonObject {
        return JsonObject().apply {
            entriesByList.forEach { (listKey, entries) ->
                add(
                    listKey,
                    JsonArray().apply {
                        entries.forEach { entry ->
                            add(encodeEntry(entry))
                        }
                    }
                )
            }
        }
    }

    private fun encodeEntry(entry: LibraryEntry): JsonObject {
        return JsonObject().apply {
            addProperty("id", entry.id)
            addProperty("type", entry.type)
            addProperty("name", entry.name)
            entry.poster?.let { addProperty("poster", it) }
            addProperty("posterShape", entry.posterShape.name)
            entry.background?.let { addProperty("background", it) }
            entry.logo?.let { addProperty("logo", it) }
            entry.description?.let { addProperty("description", it) }
            entry.releaseInfo?.let { addProperty("releaseInfo", it) }
            entry.imdbRating?.let { addProperty("imdbRating", it) }
            add(
                "genres",
                JsonArray().apply { entry.genres.forEach { add(it) } }
            )
            entry.addonBaseUrl?.let { addProperty("addonBaseUrl", it) }
            add(
                "listKeys",
                JsonArray().apply { entry.listKeys.forEach { add(it) } }
            )
            addProperty("listedAt", entry.listedAt)
            entry.traktRank?.let { addProperty("traktRank", it) }
            entry.imdbId?.let { addProperty("imdbId", it) }
            entry.tmdbId?.let { addProperty("tmdbId", it) }
            entry.traktId?.let { addProperty("traktId", it) }
            entry.directPlaybackUrl?.let { addProperty("directPlaybackUrl", it) }
            entry.playbackHeaders?.takeIf { it.isNotEmpty() }?.let { headers ->
                add("playbackHeaders", JsonObject().apply {
                    headers.forEach { (key, value) -> addProperty(key, value) }
                })
            }
            entry.playbackStreamName?.let { addProperty("playbackStreamName", it) }
            entry.playbackFilename?.let { addProperty("playbackFilename", it) }
            entry.playbackSizeBytes?.let { addProperty("playbackSizeBytes", it) }
        }
    }

    private fun encodeMetadata(metadataByContentKey: Map<String, PersistedLibraryMetadata>): JsonObject {
        return JsonObject().apply {
            metadataByContentKey.forEach { (key, metadata) ->
                add(
                    key,
                    JsonObject().apply {
                        metadata.name?.let { addProperty("name", it) }
                        metadata.poster?.let { addProperty("poster", it) }
                        metadata.background?.let { addProperty("background", it) }
                        metadata.logo?.let { addProperty("logo", it) }
                        metadata.description?.let { addProperty("description", it) }
                        metadata.releaseInfo?.let { addProperty("releaseInfo", it) }
                        metadata.imdbRating?.let { addProperty("imdbRating", it) }
                        add("genres", JsonArray().apply { metadata.genres.forEach { add(it) } })
                    }
                )
            }
        }
    }

    private fun decodeListTabs(element: JsonElement?): List<LibraryListTab> {
        val array = runCatching { element?.asJsonArray }.getOrNull() ?: return emptyList()
        return array.mapNotNull(::decodeListTab)
    }

    private fun decodeListTab(element: JsonElement): LibraryListTab? {
        val obj = runCatching { element.asJsonObject }.getOrNull() ?: return null
        val key = obj.stringOrNull("key")?.trim().orEmpty()
        if (key.isBlank()) return null
        val title = obj.stringOrNull("title")?.takeIf { it.isNotBlank() } ?: key
        val type = obj.stringOrNull("type")
            ?.let { raw ->
                runCatching { LibraryListTab.Type.valueOf(raw) }.getOrNull()
            }
            ?: return null
        return LibraryListTab(
            key = key,
            title = title,
            type = type,
            traktListId = obj.longOrNull("traktListId"),
            slug = obj.stringOrNull("slug"),
            description = obj.stringOrNull("description"),
            privacy = obj.stringOrNull("privacy")?.let(TraktListPrivacy::fromApi),
            sortBy = obj.stringOrNull("sortBy"),
            sortHow = obj.stringOrNull("sortHow")
        )
    }

    private fun decodeEntriesByList(element: JsonElement?): Map<String, List<LibraryEntry>> {
        val obj = runCatching { element?.asJsonObject }.getOrNull() ?: return emptyMap()
        return buildMap {
            obj.entrySet().forEach { (listKey, value) ->
                val entries = runCatching { value.asJsonArray }
                    .getOrNull()
                    ?.mapNotNull(::decodeEntry)
                    .orEmpty()
                if (entries.isNotEmpty()) {
                    put(listKey, entries)
                }
            }
        }
    }

    private fun decodeEntry(element: JsonElement): LibraryEntry? {
        val obj = runCatching { element.asJsonObject }.getOrNull() ?: return null
        val id = obj.stringOrNull("id")?.trim().orEmpty()
        val type = obj.stringOrNull("type")?.trim().orEmpty()
        if (id.isBlank() || type.isBlank()) return null
        val name = obj.stringOrNull("name")?.takeIf { it.isNotBlank() } ?: id
        val posterShape = obj.stringOrNull("posterShape")
            ?.let { raw -> runCatching { PosterShape.valueOf(raw) }.getOrNull() }
            ?: PosterShape.POSTER
        val genres = runCatching { obj.getAsJsonArray("genres") }
            .getOrNull()
            ?.mapNotNull { genre -> runCatching { genre.asString }.getOrNull()?.takeIf { it.isNotBlank() } }
            .orEmpty()
        val listKeys = runCatching { obj.getAsJsonArray("listKeys") }
            .getOrNull()
            ?.mapNotNull { key -> runCatching { key.asString }.getOrNull()?.takeIf { it.isNotBlank() } }
            ?.toSet()
            .orEmpty()
        val playbackHeaders = runCatching { obj.getAsJsonObject("playbackHeaders") }
            .getOrNull()
            ?.entrySet()
            ?.mapNotNull { (key, value) ->
                runCatching { value.asString }.getOrNull()?.let { key to it }
            }
            ?.toMap()

        return LibraryEntry(
            id = id,
            type = type,
            name = name,
            poster = obj.stringOrNull("poster"),
            posterShape = posterShape,
            background = obj.stringOrNull("background"),
            logo = obj.stringOrNull("logo"),
            description = obj.stringOrNull("description"),
            releaseInfo = obj.stringOrNull("releaseInfo"),
            imdbRating = obj.floatOrNull("imdbRating"),
            genres = genres,
            addonBaseUrl = obj.stringOrNull("addonBaseUrl"),
            listKeys = listKeys,
            listedAt = obj.longOrNull("listedAt") ?: 0L,
            traktRank = obj.intOrNull("traktRank"),
            imdbId = obj.stringOrNull("imdbId"),
            tmdbId = obj.intOrNull("tmdbId"),
            traktId = obj.intOrNull("traktId"),
            directPlaybackUrl = obj.stringOrNull("directPlaybackUrl"),
            playbackHeaders = playbackHeaders,
            playbackStreamName = obj.stringOrNull("playbackStreamName"),
            playbackFilename = obj.stringOrNull("playbackFilename"),
            playbackSizeBytes = obj.longOrNull("playbackSizeBytes")
        )
    }

    private fun decodeMetadata(element: JsonElement?): Map<String, PersistedLibraryMetadata> {
        val obj = runCatching { element?.asJsonObject }.getOrNull() ?: return emptyMap()
        return buildMap {
            obj.entrySet().forEach { (key, value) ->
                val metadataObj = runCatching { value.asJsonObject }.getOrNull() ?: return@forEach
                put(
                    key,
                    PersistedLibraryMetadata(
                        name = metadataObj.stringOrNull("name"),
                        poster = metadataObj.stringOrNull("poster"),
                        background = metadataObj.stringOrNull("background"),
                        logo = metadataObj.stringOrNull("logo"),
                        description = metadataObj.stringOrNull("description"),
                        releaseInfo = metadataObj.stringOrNull("releaseInfo"),
                        imdbRating = metadataObj.floatOrNull("imdbRating"),
                        genres = runCatching { metadataObj.getAsJsonArray("genres") }
                            .getOrNull()
                            ?.mapNotNull { genre ->
                                runCatching { genre.asString }.getOrNull()?.takeIf { it.isNotBlank() }
                            }
                            .orEmpty()
                    )
                )
            }
        }
    }

    private fun JsonObject.stringOrNull(key: String): String? {
        return runCatching {
            get(key)?.takeIf { !it.isJsonNull }?.asString
        }.getOrNull()
    }

    private fun JsonObject.longOrNull(key: String): Long? {
        return runCatching {
            get(key)?.takeIf { !it.isJsonNull }?.asLong
        }.getOrNull()
    }

    private fun JsonObject.intOrNull(key: String): Int? {
        return runCatching {
            get(key)?.takeIf { !it.isJsonNull }?.asInt
        }.getOrNull()
    }

    private fun JsonObject.floatOrNull(key: String): Float? {
        return runCatching {
            get(key)?.takeIf { !it.isJsonNull }?.asFloat
        }.getOrNull()
    }

    private fun logWarning(message: String, error: Throwable) {
        runCatching { Log.w(TAG, message, error) }
    }

    private fun logDebug(message: String) {
        runCatching { Log.d(TAG, message) }
    }
}
