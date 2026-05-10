package com.nexio.tv.data.local

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import com.nexio.tv.core.integration.RailKeyFactory
import com.nexio.tv.core.integration.RailMediaIdentityResolver
import com.nexio.tv.core.integration.RailMembership
import com.nexio.tv.core.profile.ProfileManager
import com.nexio.tv.core.sync.profilePrefsName
import com.nexio.tv.data.local.integration.RailCacheEntity
import com.nexio.tv.data.local.integration.RailItemEntity
import com.nexio.tv.domain.model.LibraryEntry
import com.nexio.tv.domain.model.LibraryListTab
import com.nexio.tv.domain.model.PosterShape
import com.nexio.tv.domain.model.TraktListPrivacy
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SimklLibrarySnapshotStore private constructor(
    private val context: Context,
    private val metadataDiskCacheStore: MetadataDiskCacheStore,
    private val activeProfileId: () -> Int,
    private val injectedProfileManager: ProfileManager?,
    private val identityResolver: RailMediaIdentityResolver
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        metadataDiskCacheStore: MetadataDiskCacheStore,
        profileManager: ProfileManager,
        identityResolver: RailMediaIdentityResolver
    ) : this(
        context = context,
        metadataDiskCacheStore = metadataDiskCacheStore,
        activeProfileId = { profileManager.activeProfileId.value },
        injectedProfileManager = profileManager,
        identityResolver = identityResolver
    )

    constructor(
        context: Context,
        metadataDiskCacheStore: MetadataDiskCacheStore
    ) : this(
        context = context,
        metadataDiskCacheStore = metadataDiskCacheStore,
        activeProfileId = { 1 },
        injectedProfileManager = null,
        identityResolver = RailMediaIdentityResolver()
    )

    companion object {
        private const val TAG = "SimklLibraryStore"
        internal const val BASE_PREFS_NAME = "simkl_library_snapshot"
        private const val SNAPSHOT_KEY = "snapshot"
        private const val SCHEMA_VERSION = 1
        private const val SNAPSHOT_DIR = "simkl-library-snapshot-v1"
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
        val updatedAtMs: Long = 0L,
        // Per-type activity timestamps from /sync/activities (used for date_from on delta syncs)
        val lastTvShowsAllAt: String? = null,
        val lastMoviesAllAt: String? = null,
        val lastAnimeAllAt: String? = null,
        val lastTvShowsRemovedFromListAt: String? = null,
        val lastMoviesRemovedFromListAt: String? = null,
        val lastAnimeRemovedFromListAt: String? = null
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
            // CLAUDE.md hard rule #3: file-backed streaming. Same shape as
            // TraktLibrarySnapshotStore (commit 78bac391f) — Simkl users with
            // multiple lists trivially exceed the 50 KB SharedPreferences ban.
            val file = snapshotFileFor(profileId)
            if (file.exists()) {
                streamReadSnapshot(file)
            } else {
                migrateLegacySnapshotToFile(profileId, file)
            }
        }.onFailure { error ->
            Log.w(TAG, "Failed to restore SIMKL library snapshot", error)
            clear(profileId)
        }.getOrNull()
    }

    fun write(snapshot: Snapshot, profileId: Int = activeProfileId()) {
        runCatching {
            val target = snapshotFileFor(profileId)
            target.parentFile?.mkdirs()
            writeSnapshotToFile(snapshot, target)
        }.onFailure { error ->
            Log.w(TAG, "Failed to persist SIMKL library snapshot", error)
        }
    }

    fun clear(profileId: Int = activeProfileId()) {
        runCatching {
            snapshotFileFor(profileId).takeIf { it.exists() }?.delete()
            context.getSharedPreferences(prefsName(profileId), Context.MODE_PRIVATE)
                .edit().remove(SNAPSHOT_KEY).apply()
        }.onFailure { error ->
            Log.w(TAG, "Failed to clear SIMKL library snapshot", error)
        }
    }

    private fun snapshotFileFor(profileId: Int): File {
        val parent = File(context.filesDir, SNAPSHOT_DIR)
        if (!parent.exists()) parent.mkdirs()
        return File(parent, "p${profileId}.json")
    }

    private fun streamReadSnapshot(file: File): Snapshot? {
        var schemaVersion = -1
        var listTabs: List<LibraryListTab> = emptyList()
        var entriesByList: Map<String, List<LibraryEntry>> = emptyMap()
        var metadataByContentKey: Map<String, PersistedLibraryMetadata> = emptyMap()
        var updatedAtMs: Long = 0L
        var lastTvShowsAllAt: String? = null
        var lastMoviesAllAt: String? = null
        var lastAnimeAllAt: String? = null
        var lastTvShowsRemovedFromListAt: String? = null
        var lastMoviesRemovedFromListAt: String? = null
        var lastAnimeRemovedFromListAt: String? = null

        return runCatching {
            FileInputStream(file).use { fis ->
                BufferedReader(InputStreamReader(fis, Charsets.UTF_8)).use { br ->
                    JsonReader(br).use { reader ->
                        if (reader.peek() == JsonToken.NULL) {
                            reader.nextNull()
                            return@runCatching null
                        }
                        reader.beginObject()
                        while (reader.hasNext()) {
                            when (reader.nextName()) {
                                "schemaVersion" -> {
                                    schemaVersion = reader.nextInt()
                                    if (schemaVersion > SCHEMA_VERSION) return@runCatching null
                                }
                                "listTabs" -> {
                                    val element: JsonArray? = gson.fromJson(reader, JsonArray::class.java)
                                    listTabs = decodeListTabs(element)
                                }
                                "entriesByList" -> {
                                    val element: JsonObject? = gson.fromJson(reader, JsonObject::class.java)
                                    entriesByList = decodeEntriesByList(element)
                                }
                                "metadataByContentKey" -> {
                                    val element: JsonObject? = gson.fromJson(reader, JsonObject::class.java)
                                    metadataByContentKey = decodeMetadata(element)
                                }
                                "updatedAtMs" -> updatedAtMs = reader.nextLong()
                                "lastTvShowsAllAt" -> lastTvShowsAllAt = nextStringOrNull(reader)
                                "lastMoviesAllAt" -> lastMoviesAllAt = nextStringOrNull(reader)
                                "lastAnimeAllAt" -> lastAnimeAllAt = nextStringOrNull(reader)
                                "lastTvShowsRemovedFromListAt" -> lastTvShowsRemovedFromListAt = nextStringOrNull(reader)
                                "lastMoviesRemovedFromListAt" -> lastMoviesRemovedFromListAt = nextStringOrNull(reader)
                                "lastAnimeRemovedFromListAt" -> lastAnimeRemovedFromListAt = nextStringOrNull(reader)
                                else -> reader.skipValue()
                            }
                        }
                        reader.endObject()
                    }
                }
            }
            Snapshot(
                listTabs = listTabs,
                entriesByList = entriesByList,
                metadataByContentKey = metadataByContentKey,
                updatedAtMs = updatedAtMs,
                lastTvShowsAllAt = lastTvShowsAllAt,
                lastMoviesAllAt = lastMoviesAllAt,
                lastAnimeAllAt = lastAnimeAllAt,
                lastTvShowsRemovedFromListAt = lastTvShowsRemovedFromListAt,
                lastMoviesRemovedFromListAt = lastMoviesRemovedFromListAt,
                lastAnimeRemovedFromListAt = lastAnimeRemovedFromListAt
            )
        }.onFailure { error ->
            Log.w(TAG, "Failed to stream-read SIMKL library snapshot", error)
        }.getOrNull()
    }

    private fun nextStringOrNull(reader: JsonReader): String? {
        return if (reader.peek() == JsonToken.NULL) {
            reader.nextNull(); null
        } else reader.nextString()
    }

    private fun writeSnapshotToFile(snapshot: Snapshot, target: File) {
        val tempFile = File(target.parentFile, "${target.name}.tmp")
        FileOutputStream(tempFile).use { fos ->
            BufferedWriter(OutputStreamWriter(fos, Charsets.UTF_8)).use { bw ->
                JsonWriter(bw).use { writer ->
                    writer.beginObject()
                    writer.name("schemaVersion").value(SCHEMA_VERSION)
                    writer.name("languageEpoch").value(metadataDiskCacheStore.currentLanguageEpoch())

                    writer.name("listTabs")
                    gson.toJson(encodeListTabs(snapshot.listTabs), JsonArray::class.java, writer)

                    writer.name("entriesByList")
                    gson.toJson(encodeEntriesByList(snapshot.entriesByList), JsonObject::class.java, writer)

                    writer.name("metadataByContentKey")
                    gson.toJson(encodeMetadata(snapshot.metadataByContentKey), JsonObject::class.java, writer)

                    writer.name("updatedAtMs").value(snapshot.updatedAtMs)
                    snapshot.lastTvShowsAllAt?.let { writer.name("lastTvShowsAllAt").value(it) }
                    snapshot.lastMoviesAllAt?.let { writer.name("lastMoviesAllAt").value(it) }
                    snapshot.lastAnimeAllAt?.let { writer.name("lastAnimeAllAt").value(it) }
                    snapshot.lastTvShowsRemovedFromListAt?.let { writer.name("lastTvShowsRemovedFromListAt").value(it) }
                    snapshot.lastMoviesRemovedFromListAt?.let { writer.name("lastMoviesRemovedFromListAt").value(it) }
                    snapshot.lastAnimeRemovedFromListAt?.let { writer.name("lastAnimeRemovedFromListAt").value(it) }
                    writer.endObject()
                }
            }
        }
        Files.move(
            tempFile.toPath(),
            target.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING
        )
    }

    private fun migrateLegacySnapshotToFile(profileId: Int, target: File): Snapshot? {
        val prefs = context.getSharedPreferences(prefsName(profileId), Context.MODE_PRIVATE)
        val legacy = prefs.getString(SNAPSHOT_KEY, null)?.takeIf { it.isNotBlank() } ?: return null
        val decoded = decodeSnapshot(legacy) ?: return null
        runCatching { writeSnapshotToFile(decoded, target) }
            .onFailure { error -> Log.w(TAG, "Failed to migrate legacy SIMKL snapshot to file", error) }
        runCatching { prefs.edit().remove(SNAPSHOT_KEY).apply() }
        return decoded
    }

    internal fun buildRailMemberships(snapshot: Snapshot, profileId: Int): List<RailMembership> {
        val now = System.currentTimeMillis()
        return snapshot.entriesByList.map { (listKey, entries) ->
            val railKey = RailKeyFactory.simklLibrary(profileId, listKey)
            val resolvedEntries = entries.map { entry ->
                identityResolver.fromLibraryEntry(entry, updatedAtEpochMs = now)
            }
            RailMembership(
                rail = RailCacheEntity(
                    railKey = railKey,
                    provider = "SIMKL",
                    kind = "LIBRARY",
                    paramsHash = listKey,
                    fetchedAtEpochMs = now,
                    expiresAtEpochMs = now + 30_000L,
                    staleUntilEpochMs = now + 3_600_000L
                ),
                items = resolvedEntries.mapIndexed { index, resolved ->
                    RailItemEntity(
                        key = "$railKey#${resolved.mediaIdentity.mediaKey}",
                        railKey = railKey,
                        mediaKey = resolved.mediaIdentity.mediaKey,
                        position = index,
                        updatedAtEpochMs = now
                    )
                },
                mediaIdentities = resolvedEntries.map { it.mediaIdentity },
                externalIds = resolvedEntries.flatMap { it.externalIds }
            )
        }
    }

    private fun decodeSnapshot(raw: String): Snapshot? {
        val root = gson.fromJson(raw, JsonObject::class.java) ?: return null
        val schemaVersion = root.get("schemaVersion")?.asInt ?: 0
        if (schemaVersion > SCHEMA_VERSION) return null
        return Snapshot(
            listTabs = decodeListTabs(root.get("listTabs")),
            entriesByList = decodeEntriesByList(root.get("entriesByList")),
            metadataByContentKey = decodeMetadata(root.get("metadataByContentKey")),
            updatedAtMs = root.longOrNull("updatedAtMs") ?: 0L,
            lastTvShowsAllAt = root.stringOrNull("lastTvShowsAllAt"),
            lastMoviesAllAt = root.stringOrNull("lastMoviesAllAt"),
            lastAnimeAllAt = root.stringOrNull("lastAnimeAllAt"),
            lastTvShowsRemovedFromListAt = root.stringOrNull("lastTvShowsRemovedFromListAt"),
            lastMoviesRemovedFromListAt = root.stringOrNull("lastMoviesRemovedFromListAt"),
            lastAnimeRemovedFromListAt = root.stringOrNull("lastAnimeRemovedFromListAt")
        )
    }

    private fun encodeMetadata(metadataByContentKey: Map<String, PersistedLibraryMetadata>): JsonObject = JsonObject().apply {
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

    private fun encodeListTabs(tabs: List<LibraryListTab>): JsonArray = JsonArray().apply {
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

    private fun encodeEntriesByList(entriesByList: Map<String, List<LibraryEntry>>): JsonObject = JsonObject().apply {
        entriesByList.forEach { (listKey, entries) ->
            add(listKey, JsonArray().apply { entries.forEach { add(encodeEntry(it)) } })
        }
    }

    private fun encodeEntry(entry: LibraryEntry): JsonObject = JsonObject().apply {
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
        add("genres", JsonArray().apply { entry.genres.forEach { add(it) } })
        entry.addonBaseUrl?.let { addProperty("addonBaseUrl", it) }
        add("listKeys", JsonArray().apply { entry.listKeys.forEach { add(it) } })
        addProperty("listedAt", entry.listedAt)
        entry.imdbId?.let { addProperty("imdbId", it) }
        entry.tmdbId?.let { addProperty("tmdbId", it) }
        entry.directPlaybackUrl?.let { addProperty("directPlaybackUrl", it) }
        entry.playbackStreamName?.let { addProperty("playbackStreamName", it) }
        entry.playbackFilename?.let { addProperty("playbackFilename", it) }
        entry.playbackSizeBytes?.let { addProperty("playbackSizeBytes", it) }
    }

    private fun decodeListTabs(element: JsonElement?): List<LibraryListTab> {
        val array = runCatching { element?.asJsonArray }.getOrNull() ?: return emptyList()
        return array.mapNotNull { item ->
            val obj = runCatching { item.asJsonObject }.getOrNull() ?: return@mapNotNull null
            val key = obj.stringOrNull("key")?.trim().orEmpty()
            if (key.isBlank()) return@mapNotNull null
            val type = obj.stringOrNull("type")?.let { runCatching { LibraryListTab.Type.valueOf(it) }.getOrNull() } ?: return@mapNotNull null
            LibraryListTab(
                key = key,
                title = obj.stringOrNull("title")?.takeIf { it.isNotBlank() } ?: key,
                type = type,
                traktListId = obj.longOrNull("traktListId"),
                slug = obj.stringOrNull("slug"),
                description = obj.stringOrNull("description"),
                privacy = obj.stringOrNull("privacy")?.let(TraktListPrivacy::fromApi),
                sortBy = obj.stringOrNull("sortBy"),
                sortHow = obj.stringOrNull("sortHow")
            )
        }
    }

    private fun decodeEntriesByList(element: JsonElement?): Map<String, List<LibraryEntry>> {
        val obj = runCatching { element?.asJsonObject }.getOrNull() ?: return emptyMap()
        return buildMap {
            obj.entrySet().forEach { (listKey, value) ->
                val entries = runCatching { value.asJsonArray }.getOrNull()?.mapNotNull { decodeEntry(it) }.orEmpty()
                if (entries.isNotEmpty()) put(listKey, entries)
            }
        }
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
                        genres = metadataObj.getAsJsonArray("genres")?.mapNotNull { runCatching { it.asString }.getOrNull() }.orEmpty()
                    )
                )
            }
        }
    }

    private fun decodeEntry(element: JsonElement): LibraryEntry? {
        val obj = runCatching { element.asJsonObject }.getOrNull() ?: return null
        val id = obj.stringOrNull("id")?.trim().orEmpty()
        val type = obj.stringOrNull("type")?.trim().orEmpty()
        if (id.isBlank() || type.isBlank()) return null
        val listKeys = obj.getAsJsonArray("listKeys")?.mapNotNull { runCatching { it.asString }.getOrNull() }?.toSet().orEmpty()
        val genres = obj.getAsJsonArray("genres")?.mapNotNull { runCatching { it.asString }.getOrNull() }.orEmpty()
        val posterShape = obj.stringOrNull("posterShape")?.let { runCatching { PosterShape.valueOf(it) }.getOrNull() } ?: PosterShape.POSTER
        return LibraryEntry(
            id = id,
            type = type,
            name = obj.stringOrNull("name") ?: id,
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
            imdbId = obj.stringOrNull("imdbId"),
            tmdbId = obj.intOrNull("tmdbId"),
            directPlaybackUrl = obj.stringOrNull("directPlaybackUrl"),
            playbackStreamName = obj.stringOrNull("playbackStreamName"),
            playbackFilename = obj.stringOrNull("playbackFilename"),
            playbackSizeBytes = obj.longOrNull("playbackSizeBytes")
        )
    }

    private fun JsonObject.stringOrNull(key: String): String? =
        runCatching { get(key) }.getOrNull()?.takeIf { !it.isJsonNull }?.asString

    private fun JsonObject.intOrNull(key: String): Int? =
        runCatching { get(key) }.getOrNull()?.takeIf { !it.isJsonNull }?.asInt

    private fun JsonObject.longOrNull(key: String): Long? =
        runCatching { get(key) }.getOrNull()?.takeIf { !it.isJsonNull }?.asLong

    private fun JsonObject.floatOrNull(key: String): Float? =
        runCatching { get(key) }.getOrNull()?.takeIf { !it.isJsonNull }?.asFloat
}
