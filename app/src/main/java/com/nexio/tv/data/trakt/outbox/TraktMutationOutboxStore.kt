package com.nexio.tv.data.trakt.outbox

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import com.nexio.tv.core.profile.ProfileManager
import com.nexio.tv.core.sync.profilePrefsName
import com.nexio.tv.domain.model.TrackingProvider
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TraktMutationOutboxStore private constructor(
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
        private const val TAG = "TraktMutationOutbox"
        internal const val BASE_PREFS_NAME = "trakt_mutation_outbox"
        private const val SNAPSHOT_KEY = "snapshot"
        private const val SCHEMA_VERSION = 1
        private const val JSON_SCHEMA_VERSION = "schemaVersion"
        private const val JSON_SNAPSHOT = "snapshot"
        private const val JSON_ITEMS = "items"
        private const val JSON_NEXT_WRITABLE_AT_MS = "nextWritableAtMs"
        private const val JSON_UPDATED_AT_MS = "updatedAtMs"
        private const val SNAPSHOT_DIR = "trakt-mutation-outbox-v1"
    }

    private val gson = Gson()
    private val mutex = Mutex()

    private fun activeStoreProfileId(): Int =
        injectedProfileManager?.activeProfileId?.value ?: activeProfileId()

    private fun prefsName(profileId: Int): String =
        profilePrefsName(BASE_PREFS_NAME, profileId.coerceAtLeast(1))

    suspend fun read(profileId: Int = activeStoreProfileId()): TraktMutationOutboxSnapshot {
        return mutex.withLock {
            // CLAUDE.md hard rule #3: file-backed streaming. The legacy
            // SharedPreferences-stored payload (heap-confirmed 37 KiB
            // {"adapterKey":"scrobble"...} char[] held by
            // SharedPreferencesImpl.mMap during 2026-05-10 ANR investigation)
            // pinned the entire JSON during prefs.getString() and the
            // JsonParser.parseString(rawString) parse held it again via
            // StringReader. Migrated to file-backed JSON + streaming
            // JsonReader so the payload is consumed token-by-token.
            val file = snapshotFileFor(profileId)
            if (file.exists()) {
                streamReadSnapshot(file)
            } else {
                migrateLegacySnapshotToFile(profileId, file)
            }
        }
    }

    suspend fun write(snapshot: TraktMutationOutboxSnapshot, profileId: Int = activeStoreProfileId()) {
        mutex.withLock {
            // Streaming write — JsonWriter over BufferedWriter over
            // FileOutputStream + atomic rename. Replaces
            // prefs.edit().putString(payload.toString()).commit() which
            // synchronously serialised the entire 37+ KiB payload to a
            // String and then blocked the caller on disk I/O via SharedPrefs
            // XML serialisation; intermittent ANRs (Input dispatching timed
            // out) tracked back to this hot path.
            runCatching {
                val target = snapshotFileFor(profileId)
                target.parentFile?.mkdirs()
                writeSnapshotToFile(snapshot, target)
            }.onFailure { error ->
                Log.w(TAG, "Failed to persist Trakt mutation outbox snapshot", error)
            }
        }
    }

    suspend fun clear(profileId: Int = activeStoreProfileId()) {
        mutex.withLock {
            runCatching { snapshotFileFor(profileId).takeIf { it.exists() }?.delete() }
            // Also clear any lingering legacy prefs entry.
            val prefs = context.getSharedPreferences(prefsName(profileId), Context.MODE_PRIVATE)
            prefs.edit().remove(SNAPSHOT_KEY).apply()
        }
    }

    private fun snapshotFileFor(profileId: Int): File {
        val parent = File(context.filesDir, SNAPSHOT_DIR)
        if (!parent.exists()) parent.mkdirs()
        return File(parent, "p${profileId.coerceAtLeast(1)}.json")
    }

    private fun streamReadSnapshot(file: File): TraktMutationOutboxSnapshot {
        var schemaVersion = -1
        var snapshot: TraktMutationOutboxSnapshot = TraktMutationOutboxSnapshot()

        return runCatching {
            FileInputStream(file).use { fis ->
                BufferedReader(InputStreamReader(fis, Charsets.UTF_8)).use { br ->
                    JsonReader(br).use { reader ->
                        if (reader.peek() == JsonToken.NULL) {
                            reader.nextNull()
                            return@runCatching TraktMutationOutboxSnapshot()
                        }
                        reader.beginObject()
                        while (reader.hasNext()) {
                            when (reader.nextName()) {
                                JSON_SCHEMA_VERSION -> {
                                    schemaVersion = reader.nextInt()
                                    if (schemaVersion > SCHEMA_VERSION) {
                                        return@runCatching TraktMutationOutboxSnapshot()
                                    }
                                }
                                JSON_SNAPSHOT -> {
                                    val obj: JsonObject? = gson.fromJson(reader, JsonObject::class.java)
                                    snapshot = deserializeSnapshot(obj)
                                }
                                else -> reader.skipValue()
                            }
                        }
                        reader.endObject()
                    }
                }
            }
            snapshot
        }.onFailure { error ->
            Log.w(TAG, "Failed to stream-read Trakt mutation outbox snapshot", error)
        }.getOrDefault(TraktMutationOutboxSnapshot())
    }

    private fun writeSnapshotToFile(snapshot: TraktMutationOutboxSnapshot, target: File) {
        val tempFile = File(target.parentFile, "${target.name}.tmp")
        FileOutputStream(tempFile).use { fos ->
            BufferedWriter(OutputStreamWriter(fos, Charsets.UTF_8)).use { bw ->
                JsonWriter(bw).use { writer ->
                    writer.beginObject()
                    writer.name(JSON_SCHEMA_VERSION).value(SCHEMA_VERSION)
                    writer.name(JSON_SNAPSHOT)
                    gson.toJson(snapshot.toJson(), JsonObject::class.java, writer)
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
     * once via JsonParser, write it to file, then remove the prefs entry.
     */
    private fun migrateLegacySnapshotToFile(
        profileId: Int,
        target: File
    ): TraktMutationOutboxSnapshot {
        val prefs = context.getSharedPreferences(prefsName(profileId), Context.MODE_PRIVATE)
        val raw = prefs.getString(SNAPSHOT_KEY, null)?.takeIf { it.isNotBlank() }
            ?: return TraktMutationOutboxSnapshot()
        val root = runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull()
        if (root == null || root.schemaVersion() > SCHEMA_VERSION) {
            return TraktMutationOutboxSnapshot()
        }
        val decoded = deserializeSnapshot(root.objectOrNull(JSON_SNAPSHOT))
        runCatching { writeSnapshotToFile(decoded, target) }
            .onFailure { error -> Log.w(TAG, "Failed to migrate legacy Trakt mutation outbox to file", error) }
        runCatching { prefs.edit().remove(SNAPSHOT_KEY).apply() }
        return decoded
    }

    private fun JsonObject.schemaVersion(): Int {
        return runCatching {
            get(JSON_SCHEMA_VERSION)?.takeIf { it.isJsonPrimitive }?.asInt
        }.getOrNull() ?: 0
    }

    private fun deserializeSnapshot(snapshotJson: JsonObject?): TraktMutationOutboxSnapshot {
        if (snapshotJson == null) return TraktMutationOutboxSnapshot()
        val items = snapshotJson.arrayOrNull(JSON_ITEMS)
            ?.mapNotNull(::deserializeEnvelope)
            .orEmpty()
        return TraktMutationOutboxSnapshot(
            items = items,
            nextWritableAtMs = snapshotJson.longOrZero(JSON_NEXT_WRITABLE_AT_MS),
            updatedAtMs = snapshotJson.longOrZero(JSON_UPDATED_AT_MS)
        )
    }

    private fun deserializeEnvelope(element: JsonElement?): TraktMutationEnvelope? {
        if (element == null || element.isJsonNull) return null
        return runCatching {
            val obj = element.asJsonObject
            val provider = obj.stringOrNull("provider")
            val credentialHash = obj.stringOrNull("credentialHash")
            if (provider.isNullOrBlank() || credentialHash.isNullOrBlank()) {
                return@runCatching quarantinedLegacyEnvelope(obj)
            }
            gson.fromJson(obj, TraktMutationEnvelope::class.java)
                .copy(profileId = obj.intOrNull("profileId") ?: 1)
                .sanitizedOrNull()
        }.getOrNull()
    }

    private fun quarantinedLegacyEnvelope(obj: JsonObject): TraktMutationEnvelope? {
        val adapterKey = obj.stringOrNull("adapterKey")?.takeIf { it.isNotBlank() } ?: return null
        val mutationKind = obj.stringOrNull("mutationKind")?.takeIf { it.isNotBlank() } ?: return null
        val priority = runCatching {
            TraktMutationPriorityBucket.valueOf(obj.stringOrNull("priority") ?: "")
        }.getOrNull() ?: return null
        return TraktMutationEnvelope(
            id = obj.stringOrNull("id")?.takeIf { it.isNotBlank() } ?: return null,
            profileId = (obj.intOrNull("profileId") ?: 1).coerceAtLeast(1),
            provider = TrackingProvider.TRAKT,
            credentialHash = "legacy-missing-account-scope",
            adapterKey = adapterKey,
            mutationKind = mutationKind,
            priority = priority,
            payload = obj.objectOrNull("payload") ?: JsonObject(),
            metadata = obj.objectOrNull("metadata") ?: JsonObject(),
            state = TraktMutationLifecycleState.TERMINAL_FAILED,
            lastError = "MISSING_ACCOUNT_SCOPE",
            completedAtMs = System.currentTimeMillis()
        )
    }

    private fun TraktMutationEnvelope.sanitizedOrNull(): TraktMutationEnvelope? {
        val cleanId = (id as String?)?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val cleanAdapterKey = (adapterKey as String?)?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val cleanMutationKind = (mutationKind as String?)?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val cleanPriority = (priority as TraktMutationPriorityBucket?) ?: return null
        val cleanState = (state as TraktMutationLifecycleState?) ?: return null
        val cleanProvider = (provider as TrackingProvider?) ?: return null
        val cleanCredentialHash = (credentialHash as String?)?.trim()?.takeIf { it.isNotBlank() } ?: return null

        return copy(
            id = cleanId,
            profileId = profileId.coerceAtLeast(1),
            provider = cleanProvider,
            credentialHash = cleanCredentialHash,
            adapterKey = cleanAdapterKey,
            mutationKind = cleanMutationKind,
            priority = cleanPriority,
            payload = (payload as JsonObject?) ?: JsonObject(),
            metadata = (metadata as JsonObject?) ?: JsonObject(),
            state = cleanState
        )
    }

    private fun TraktMutationOutboxSnapshot.toJson(): JsonObject {
        return JsonObject().apply {
            add(JSON_ITEMS, JsonArray().apply {
                items.forEach { envelope ->
                    val obj = gson.toJsonTree(envelope).asJsonObject
                    obj.addProperty("profileId", envelope.profileId)
                    obj.addProperty("provider", envelope.provider.name)
                    obj.addProperty("credentialHash", envelope.credentialHash)
                    add(obj)
                }
            })
            addProperty(JSON_NEXT_WRITABLE_AT_MS, nextWritableAtMs)
            addProperty(JSON_UPDATED_AT_MS, updatedAtMs)
        }
    }

    private fun JsonObject.intOrNull(key: String): Int? {
        return runCatching {
            get(key)?.takeIf { !it.isJsonNull }?.asInt
        }.getOrNull()
    }

    private fun JsonObject.stringOrNull(key: String): String? {
        return runCatching {
            get(key)?.takeIf { !it.isJsonNull }?.asString
        }.getOrNull()
    }

    private fun JsonObject.objectOrNull(key: String): JsonObject? {
        return runCatching {
            get(key)?.takeIf { it.isJsonObject }?.asJsonObject
        }.getOrNull()
    }

    private fun JsonObject.arrayOrNull(key: String): JsonArray? {
        return runCatching {
            get(key)?.takeIf { it.isJsonArray }?.asJsonArray
        }.getOrNull()
    }

    private fun JsonObject.longOrZero(fieldName: String): Long {
        val value = get(fieldName) ?: return 0L
        if (value.isJsonNull) return 0L
        return runCatching { value.asLong }.getOrDefault(0L)
    }
}
