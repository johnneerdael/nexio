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
import com.nexio.tv.core.locale.AppLocaleResolver
import com.nexio.tv.core.profile.ProfileManager
import com.nexio.tv.domain.model.CatalogRow
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

data class PersistedSyntheticCatalogGroup(
    val orderKey: String,
    val rows: List<CatalogRow>
)

@Singleton
class SyntheticHomeCatalogStore private constructor(
    @ApplicationContext private val context: Context,
    private val metadataDiskCacheStore: MetadataDiskCacheStore,
    private val activeProfileId: () -> Int
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        metadataDiskCacheStore: MetadataDiskCacheStore,
        profileManager: ProfileManager
    ) : this(
        context = context,
        metadataDiskCacheStore = metadataDiskCacheStore,
        activeProfileId = { profileManager.activeProfileId.value }
    )

    constructor(
        context: Context,
        metadataDiskCacheStore: MetadataDiskCacheStore
    ) : this(
        context = context,
        metadataDiskCacheStore = metadataDiskCacheStore,
        activeProfileId = { 1 }
    )

    companion object {
        private const val TAG = "SyntheticHomeCatalog"
        private const val PREFS_NAME = "synthetic_home_catalogs"
        private const val SNAPSHOT_KEY = "snapshot"
        private const val SCHEMA_VERSION = 5
        private const val SNAPSHOT_DIR = "synthetic-home-catalog-v1"
    }

    private val gson = Gson()

    data class Snapshot(
        val traktGroups: List<PersistedSyntheticCatalogGroup> = emptyList(),
        val simklGroups: List<PersistedSyntheticCatalogGroup> = emptyList(),
        val mdbListGroups: List<PersistedSyntheticCatalogGroup> = emptyList(),
        val kitsuGroups: List<PersistedSyntheticCatalogGroup> = emptyList(),
        val tmdbGroups: List<PersistedSyntheticCatalogGroup> = emptyList(),
        val tmdbIncludeAdult: Boolean? = null,
        val tmdbHideUnreleasedDigital: Boolean? = null
    )

    fun read(profileId: Int = activeProfileId()): Snapshot? {
        return runCatching {
            // CLAUDE.md hard rule #3: file-backed streaming. The legacy
            // SharedPreferences-stored payload pinned 50+ KB of catalogRow JSON
            // in prefs (heap-confirmed: 53 KiB catalogRow char[] entries from
            // 2026-05-10 ANR investigation) and reads via prefs.getString +
            // gson.fromJson(rawString, JsonObject::class) materialised the
            // entire payload as a String for the parse.
            val file = snapshotFileFor(profileId)
            if (file.exists()) {
                streamReadSnapshot(file)
            } else {
                migrateLegacySnapshotToFile(profileId, file)
            }
        }.onFailure { error ->
            Log.w(TAG, "Failed to restore synthetic home catalogs", error)
            clear(profileId)
        }.getOrNull()
    }

    fun write(
        snapshot: Snapshot,
        profileId: Int = activeProfileId()
    ) {
        runCatching {
            val target = snapshotFileFor(profileId)
            target.parentFile?.mkdirs()
            writeSnapshotToFile(snapshot, target)
        }.onFailure { error ->
            Log.w(TAG, "Failed to persist synthetic home catalogs", error)
        }
    }

    fun clear(profileId: Int = activeProfileId()) {
        runCatching {
            snapshotFileFor(profileId).takeIf { it.exists() }?.delete()
            // Also clear legacy prefs entry.
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().remove(snapshotKey(profileId)).apply()
        }.onFailure { error ->
            Log.w(TAG, "Failed to clear synthetic home catalogs", error)
        }
    }

    private fun snapshotFileFor(profileId: Int): File {
        val parent = File(context.filesDir, SNAPSHOT_DIR)
        if (!parent.exists()) parent.mkdirs()
        // Mirror the prefs key shape so distinct app languages keep distinct files.
        val sanitizedTag = currentLanguageTag()
            .lowercase()
            .replace(Regex("[^a-z0-9_-]"), "_")
            .ifBlank { "unknown" }
        return File(parent, "p${profileId}_${sanitizedTag}.json")
    }

    private fun streamReadSnapshot(file: File): Snapshot? {
        val expectedLanguageTag = currentLanguageTag()
        var schemaVersion = -1
        var languageTag: String? = null
        var traktGroups: List<PersistedSyntheticCatalogGroup> = emptyList()
        var simklGroups: List<PersistedSyntheticCatalogGroup> = emptyList()
        var mdbListGroups: List<PersistedSyntheticCatalogGroup> = emptyList()
        var kitsuGroups: List<PersistedSyntheticCatalogGroup> = emptyList()
        var tmdbGroups: List<PersistedSyntheticCatalogGroup> = emptyList()
        var tmdbIncludeAdult: Boolean? = null
        var tmdbHideUnreleasedDigital: Boolean? = null

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
                                    if (schemaVersion != 4 && schemaVersion != SCHEMA_VERSION) {
                                        return@runCatching null
                                    }
                                }
                                "languageTag" -> {
                                    languageTag = reader.nextString().trim()
                                    if (languageTag.isNullOrBlank() || languageTag != expectedLanguageTag) {
                                        return@runCatching null
                                    }
                                }
                                "traktGroups" -> {
                                    val element: JsonArray? = gson.fromJson(reader, JsonArray::class.java)
                                    traktGroups = decodeGroups(element)
                                }
                                "simklGroups" -> {
                                    val element: JsonArray? = gson.fromJson(reader, JsonArray::class.java)
                                    simklGroups = decodeGroups(element)
                                }
                                "mdbListGroups" -> {
                                    val element: JsonArray? = gson.fromJson(reader, JsonArray::class.java)
                                    mdbListGroups = decodeGroups(element)
                                }
                                "kitsuGroups" -> {
                                    val element: JsonArray? = gson.fromJson(reader, JsonArray::class.java)
                                    kitsuGroups = decodeGroups(element)
                                }
                                "tmdbGroups" -> {
                                    val element: JsonArray? = gson.fromJson(reader, JsonArray::class.java)
                                    tmdbGroups = decodeGroups(element)
                                }
                                "tmdbIncludeAdult" -> {
                                    tmdbIncludeAdult = if (reader.peek() == JsonToken.NULL) {
                                        reader.nextNull(); null
                                    } else reader.nextBoolean()
                                }
                                "tmdbHideUnreleasedDigital" -> {
                                    tmdbHideUnreleasedDigital = if (reader.peek() == JsonToken.NULL) {
                                        reader.nextNull(); null
                                    } else reader.nextBoolean()
                                }
                                else -> reader.skipValue()
                            }
                        }
                        reader.endObject()
                    }
                }
            }
            Snapshot(
                traktGroups = traktGroups,
                simklGroups = simklGroups,
                mdbListGroups = mdbListGroups,
                kitsuGroups = kitsuGroups,
                tmdbGroups = tmdbGroups,
                tmdbIncludeAdult = tmdbIncludeAdult,
                tmdbHideUnreleasedDigital = tmdbHideUnreleasedDigital
            )
        }.onFailure { error ->
            Log.w(TAG, "Failed to stream-read synthetic home catalogs", error)
        }.getOrNull()
    }

    private fun writeSnapshotToFile(snapshot: Snapshot, target: File) {
        val tempFile = File(target.parentFile, "${target.name}.tmp")
        FileOutputStream(tempFile).use { fos ->
            BufferedWriter(OutputStreamWriter(fos, Charsets.UTF_8)).use { bw ->
                JsonWriter(bw).use { writer ->
                    writer.beginObject()
                    writer.name("schemaVersion").value(SCHEMA_VERSION)
                    writer.name("languageEpoch").value(metadataDiskCacheStore.currentLanguageEpoch())
                    writer.name("languageTag").value(currentLanguageTag())

                    writer.name("traktGroups")
                    gson.toJson(encodeGroups(snapshot.traktGroups), JsonArray::class.java, writer)
                    writer.name("simklGroups")
                    gson.toJson(encodeGroups(snapshot.simklGroups), JsonArray::class.java, writer)
                    writer.name("mdbListGroups")
                    gson.toJson(encodeGroups(snapshot.mdbListGroups), JsonArray::class.java, writer)
                    writer.name("kitsuGroups")
                    gson.toJson(encodeGroups(snapshot.kitsuGroups), JsonArray::class.java, writer)
                    writer.name("tmdbGroups")
                    gson.toJson(encodeGroups(snapshot.tmdbGroups), JsonArray::class.java, writer)

                    snapshot.tmdbIncludeAdult?.let { writer.name("tmdbIncludeAdult").value(it) }
                    snapshot.tmdbHideUnreleasedDigital?.let { writer.name("tmdbHideUnreleasedDigital").value(it) }
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

    /**
     * One-time legacy migration: when the file does not yet exist but the
     * SharedPreferences-stored payload is present, decode the legacy String
     * once via the existing decodeSnapshot() path, write it to file, then
     * remove the prefs entry.
     */
    private fun migrateLegacySnapshotToFile(profileId: Int, target: File): Snapshot? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val key = snapshotKey(profileId)
        val legacy = prefs.getString(key, null)?.takeIf { it.isNotBlank() } ?: return null
        val decoded = decodeSnapshot(legacy) ?: return null
        runCatching { writeSnapshotToFile(decoded, target) }
            .onFailure { error -> Log.w(TAG, "Failed to migrate legacy synthetic catalog to file", error) }
        runCatching { prefs.edit().remove(key).apply() }
        return decoded
    }

    private fun decodeSnapshot(raw: String): Snapshot? {
        val root = gson.fromJson(raw, JsonObject::class.java) ?: return null
        val schemaVersion = root.get("schemaVersion")?.asInt ?: 0
        if (schemaVersion != 4 && schemaVersion != SCHEMA_VERSION) {
            return null
        }
        val languageTag = root.get("languageTag")?.asString?.trim().orEmpty()
        if (languageTag.isBlank() || languageTag != currentLanguageTag()) {
            return null
        }
        return Snapshot(
            traktGroups = decodeGroups(root.getAsJsonArray("traktGroups")),
            simklGroups = decodeGroups(root.getAsJsonArray("simklGroups")),
            mdbListGroups = decodeGroups(root.getAsJsonArray("mdbListGroups")),
            kitsuGroups = decodeGroups(root.getAsJsonArray("kitsuGroups")),
            tmdbGroups = decodeGroups(root.getAsJsonArray("tmdbGroups")),
            tmdbIncludeAdult = decodeBoolean(root, "tmdbIncludeAdult"),
            tmdbHideUnreleasedDigital = decodeBoolean(root, "tmdbHideUnreleasedDigital")
        )
    }

    private fun currentLanguageTag(): String {
        return AppLocaleResolver.resolveEffectiveAppLanguageTag(context)
    }

    private fun snapshotKey(profileId: Int = activeProfileId()): String {
        return "$SNAPSHOT_KEY:p$profileId:${currentLanguageTag()}"
    }

    private fun decodeGroups(array: JsonArray?): List<PersistedSyntheticCatalogGroup> {
        return array
            ?.mapNotNull(::decodeGroup)
            .orEmpty()
    }

    private fun encodeGroups(groups: List<PersistedSyntheticCatalogGroup>): JsonArray {
        return JsonArray().apply {
            groups.forEach { group ->
                add(
                    JsonObject().apply {
                        addProperty("orderKey", group.orderKey)
                        add("rows", JsonArray().apply {
                            group.rows.forEach { row ->
                                add(gson.toJsonTree(row))
                            }
                        })
                    }
                )
            }
        }
    }

    private fun decodeGroup(element: JsonElement): PersistedSyntheticCatalogGroup? {
        val obj = element.asJsonObject ?: return null
        val orderKey = obj.get("orderKey")?.asString?.trim().orEmpty()
        if (orderKey.isBlank()) return null
        val rows = obj.getAsJsonArray("rows")
            ?.mapNotNull(::decodeRow)
            .orEmpty()
        return PersistedSyntheticCatalogGroup(
            orderKey = orderKey,
            rows = rows
        )
    }

    private fun decodeBoolean(root: JsonObject, name: String): Boolean? {
        return root.get(name)
            ?.takeIf { !it.isJsonNull }
            ?.let { runCatching { it.asBoolean }.getOrNull() }
    }

    private fun decodeRow(element: JsonElement): CatalogRow? {
        return runCatching {
            gson.fromJson(element, CatalogRow::class.java)
                ?.sanitizedForCache()
        }.getOrNull()
    }
}
