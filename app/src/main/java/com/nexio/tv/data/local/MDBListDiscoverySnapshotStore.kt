package com.nexio.tv.data.local

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import com.nexio.tv.core.profile.ProfileManager
import com.nexio.tv.core.sync.profilePrefsName
import com.nexio.tv.data.repository.MDBListCustomCatalog
import com.nexio.tv.data.repository.MDBListDiscoverySnapshot
import com.nexio.tv.data.repository.MDBListListOption
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.RailItemPreview
import com.nexio.tv.domain.model.toLegacyRailItemPreview
import com.nexio.tv.domain.model.toMetaPreview
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
class MDBListDiscoverySnapshotStore private constructor(
    @ApplicationContext private val context: Context,
    private val activeProfileId: () -> Int
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        profileManager: ProfileManager
    ) : this(
        context = context,
        activeProfileId = { profileManager.activeProfileId.value }
    )

    constructor(context: Context) : this(
        context = context,
        activeProfileId = { 1 }
    )

    companion object {
        private const val TAG = "MDBListDiscoveryStore"
        private const val PREFS_NAME = "mdblist_discovery_snapshot"
        private const val SNAPSHOT_KEY = "snapshot"
        private const val SNAPSHOT_DIR = "mdblist-discovery-snapshot-v1"
    }

    private val gson = Gson()

    private fun prefsName(profileId: Int = activeProfileId()): String =
        profilePrefsName(PREFS_NAME, profileId)

    fun read(profileId: Int = activeProfileId()): MDBListDiscoverySnapshot? {
        return runCatching {
            // CLAUDE.md hard rule #3: file-backed streaming. The 114 KiB
            // {"personalLists":...} char[] observed in heap dump 2026-05-10
            // ANR investigation lives in this store. Migrated to file-backed
            // JSON + streaming JsonReader.
            val file = snapshotFileFor(profileId)
            if (file.exists()) {
                streamReadSnapshot(file)
            } else {
                migrateLegacySnapshotToFile(profileId, file)
            }
        }.onFailure { error ->
            Log.w(TAG, "Failed to restore MDBList discovery snapshot", error)
            clear(profileId)
        }.getOrNull()
    }

    fun write(
        snapshot: MDBListDiscoverySnapshot,
        profileId: Int = activeProfileId()
    ) {
        runCatching {
            val target = snapshotFileFor(profileId)
            target.parentFile?.mkdirs()
            writeSnapshotToFile(snapshot, target)
        }.onFailure { error ->
            Log.w(TAG, "Failed to persist MDBList discovery snapshot", error)
        }
    }

    fun clear(profileId: Int = activeProfileId()) {
        runCatching {
            snapshotFileFor(profileId).takeIf { it.exists() }?.delete()
            context.getSharedPreferences(prefsName(profileId), Context.MODE_PRIVATE)
                .edit().remove(SNAPSHOT_KEY).apply()
        }.onFailure { error ->
            Log.w(TAG, "Failed to clear MDBList discovery snapshot", error)
        }
    }

    private fun snapshotFileFor(profileId: Int): File {
        val parent = File(context.filesDir, SNAPSHOT_DIR)
        if (!parent.exists()) parent.mkdirs()
        return File(parent, "p${profileId}.json")
    }

    private fun streamReadSnapshot(file: File): MDBListDiscoverySnapshot? {
        var personalLists: List<MDBListListOption> = emptyList()
        var topLists: List<MDBListListOption> = emptyList()
        var customListCatalogs: List<MDBListCustomCatalog> = emptyList()
        var updatedAtMs: Long = 0L

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
                                "personalLists" -> {
                                    val element: JsonArray? = gson.fromJson(reader, JsonArray::class.java)
                                    personalLists = decodeListOptionsArray(element, isPersonal = true)
                                }
                                "topLists" -> {
                                    val element: JsonArray? = gson.fromJson(reader, JsonArray::class.java)
                                    topLists = decodeListOptionsArray(element, isPersonal = false)
                                }
                                "customListCatalogs" -> {
                                    val element: JsonArray? = gson.fromJson(reader, JsonArray::class.java)
                                    customListCatalogs = decodeCustomCatalogsArray(element)
                                }
                                "updatedAtMs" -> updatedAtMs = reader.nextLong()
                                else -> reader.skipValue()
                            }
                        }
                        reader.endObject()
                    }
                }
            }
            MDBListDiscoverySnapshot(
                personalLists = personalLists,
                topLists = topLists,
                customListCatalogs = customListCatalogs,
                updatedAtMs = updatedAtMs
            )
        }.onFailure { error ->
            Log.w(TAG, "Failed to stream-read MDBList discovery snapshot", error)
        }.getOrNull()
    }

    private fun decodeListOptionsArray(
        array: JsonArray?,
        isPersonal: Boolean
    ): List<MDBListListOption> {
        if (array == null) return emptyList()
        // Reuses the existing decodeListOptions(root, key, isPersonal) helper by
        // wrapping the array in a transient JsonObject under the expected key.
        val root = JsonObject().apply {
            add(if (isPersonal) "personalLists" else "topLists", array)
        }
        return decodeListOptions(root, if (isPersonal) "personalLists" else "topLists", isPersonal)
    }

    private fun decodeCustomCatalogsArray(array: JsonArray?): List<MDBListCustomCatalog> {
        if (array == null) return emptyList()
        val root = JsonObject().apply { add("customListCatalogs", array) }
        return decodeCustomCatalogs(root)
    }

    private fun writeSnapshotToFile(snapshot: MDBListDiscoverySnapshot, target: File) {
        val tempFile = File(target.parentFile, "${target.name}.tmp")
        FileOutputStream(tempFile).use { fos ->
            BufferedWriter(OutputStreamWriter(fos, Charsets.UTF_8)).use { bw ->
                JsonWriter(bw).use { writer ->
                    writer.beginObject()
                    writer.name("personalLists")
                    val personalListsType = object : TypeToken<List<MDBListListOption>>() {}.type
                    gson.toJson(snapshot.personalLists, personalListsType, writer)
                    writer.name("topLists")
                    gson.toJson(snapshot.topLists, personalListsType, writer)
                    writer.name("customListCatalogs")
                    val catalogsType = object : TypeToken<List<MDBListCustomCatalog>>() {}.type
                    gson.toJson(snapshot.customListCatalogs, catalogsType, writer)
                    writer.name("updatedAtMs").value(snapshot.updatedAtMs)
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

    private fun migrateLegacySnapshotToFile(
        profileId: Int,
        target: File
    ): MDBListDiscoverySnapshot? {
        val prefs = context.getSharedPreferences(prefsName(profileId), Context.MODE_PRIVATE)
        val legacy = prefs.getString(SNAPSHOT_KEY, null)?.takeIf { it.isNotBlank() } ?: return null
        val decoded = decode(legacy) ?: return null
        runCatching { writeSnapshotToFile(decoded, target) }
            .onFailure { error -> Log.w(TAG, "Failed to migrate legacy MDBList snapshot to file", error) }
        runCatching { prefs.edit().remove(SNAPSHOT_KEY).apply() }
        return decoded
    }

    private fun decode(raw: String): MDBListDiscoverySnapshot? {
        val root = gson.fromJson(raw, JsonObject::class.java) ?: return null
        val canonical = MDBListDiscoverySnapshot(
            personalLists = decodeListOptions(root, "personalLists", isPersonal = true),
            topLists = decodeListOptions(root, "topLists", isPersonal = false),
            customListCatalogs = decodeCustomCatalogs(root),
            updatedAtMs = root.get("updatedAtMs")?.asLong ?: 0L
        )
        if (canonical.updatedAtMs > 0L ||
            canonical.personalLists.isNotEmpty() ||
            canonical.topLists.isNotEmpty() ||
            canonical.customListCatalogs.isNotEmpty()
        ) {
            return canonical
        }

        // Legacy payloads were stored via direct Gson reflection and may use obfuscated field names.
        return runCatching {
            gson.fromJson(raw, MDBListDiscoverySnapshot::class.java)
        }.getOrNull()
    }

    private inline fun <reified T> decodeArray(root: JsonObject, key: String): List<T> {
        val array = root.getAsJsonArray(key) ?: return emptyList()
        val type = object : TypeToken<List<T>>() {}.type
        return gson.fromJson<List<T>>(array, type) ?: emptyList()
    }

    private fun decodeCustomCatalogs(root: JsonObject): List<MDBListCustomCatalog> {
        val array = root.getAsJsonArray("customListCatalogs") ?: return emptyList()
        return array.mapNotNull { element ->
            val obj = element.asJsonObjectOrNull() ?: return@mapNotNull null
            val catalogId = obj.cleanString("catalogId")
            val itemArray = obj.getAsJsonArray("itemRecords") ?: obj.getAsJsonArray("items")
            val itemRecords = decodeRailItems(itemArray, catalogId)
            MDBListCustomCatalog(
                key = obj.cleanString("key"),
                catalogId = catalogId,
                catalogName = obj.cleanString("catalogName"),
                type = runCatching { gson.fromJson(obj.get("type"), ContentType::class.java) }
                    .getOrDefault(ContentType.UNKNOWN),
                itemRecords = itemRecords
            )
        }
    }

    private fun decodeRailItems(
        array: com.google.gson.JsonArray?,
        fallbackRailId: String
    ): List<RailItemPreview> {
        if (array == null) return emptyList()
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

    private fun decodeListOptions(
        root: JsonObject,
        key: String,
        isPersonal: Boolean
    ): List<MDBListListOption> {
        val array = root.getAsJsonArray(key) ?: return emptyList()
        val prefix = if (isPersonal) "personal" else "top"
        return array.mapNotNull { element ->
            val obj = element.asJsonObjectOrNull() ?: return@mapNotNull null
            val rawKey = obj.cleanString("key")
            val payload = rawKey.substringAfter(':', missingDelimiterValue = rawKey)
            val owner = obj.cleanString("owner").ifBlank {
                payload.substringBefore('/').trim()
            }.ifBlank {
                "mdblist"
            }
            val listId = obj.cleanString("listId").ifBlank {
                payload.substringAfter('/', missingDelimiterValue = "").trim()
            }
            val normalizedKey = rawKey.ifBlank {
                if (owner.isNotBlank() && listId.isNotBlank()) "$prefix:$owner/$listId" else ""
            }
            if (normalizedKey.isBlank() || listId.isBlank()) {
                return@mapNotNull null
            }

            MDBListListOption(
                key = normalizedKey,
                owner = owner,
                listId = listId,
                itemListIds = decodeStringList(obj, "itemListIds"),
                title = obj.cleanString("title").ifBlank { "$owner/$listId" },
                itemCount = obj.cleanInt("itemCount"),
                isPersonal = isPersonal
            )
        }
    }

    private fun decodeStringList(root: JsonObject, key: String): List<String> {
        val array = root.getAsJsonArray(key) ?: return emptyList()
        return array.mapNotNull { element ->
            element.cleanString()
        }.filter { it.isNotBlank() }
    }

    private fun JsonElement.asJsonObjectOrNull(): JsonObject? =
        takeIf { it.isJsonObject }?.asJsonObject

    private fun JsonObject.cleanString(key: String): String {
        val value = get(key) ?: return ""
        return value.cleanString()
    }

    private fun JsonElement.cleanString(): String {
        if (isJsonNull) return ""
        return runCatching { asString.trim() }
            .getOrDefault("")
            .takeUnless { it == "undefined" || it == "null" }
            ?: ""
    }

    private fun JsonObject.cleanInt(key: String): Int {
        val value = get(key) ?: return 0
        if (value.isJsonNull) return 0
        return runCatching { value.asInt }.getOrDefault(0).coerceAtLeast(0)
    }
}
