package com.nexio.tv.data.trakt.outbox

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.nexio.tv.core.profile.ProfileManager
import com.nexio.tv.core.sync.profilePrefsName
import dagger.hilt.android.qualifiers.ApplicationContext
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
        internal const val BASE_PREFS_NAME = "trakt_mutation_outbox"
        private const val SNAPSHOT_KEY = "snapshot"
        private const val SCHEMA_VERSION = 1
        private const val JSON_SCHEMA_VERSION = "schemaVersion"
        private const val JSON_SNAPSHOT = "snapshot"
        private const val JSON_ITEMS = "items"
        private const val JSON_NEXT_WRITABLE_AT_MS = "nextWritableAtMs"
        private const val JSON_UPDATED_AT_MS = "updatedAtMs"
    }

    private val gson = Gson()
    private val mutex = Mutex()

    private val profileManager: ProfileManager
        get() = injectedProfileManager ?: error("ProfileManager unavailable")

    private fun injectedPrefsName(): String =
        profilePrefsName(BASE_PREFS_NAME, profileManager.activeProfileId.value)

    private fun prefsName(): String =
        if (injectedProfileManager != null) {
            injectedPrefsName()
        } else {
            profilePrefsName(BASE_PREFS_NAME, activeProfileId())
        }

    suspend fun read(): TraktMutationOutboxSnapshot {
        return mutex.withLock {
            val prefs = context.getSharedPreferences(prefsName(), Context.MODE_PRIVATE)
            val raw = prefs.getString(SNAPSHOT_KEY, null)?.takeIf { it.isNotBlank() }
                ?: return@withLock TraktMutationOutboxSnapshot()
            val root = runCatching {
                JsonParser.parseString(raw).asJsonObject
            }.getOrNull()
            if (root == null || root.schemaVersion() > SCHEMA_VERSION) {
                return@withLock TraktMutationOutboxSnapshot()
            }
            deserializeSnapshot(root.objectOrNull(JSON_SNAPSHOT))
        }
    }

    suspend fun write(snapshot: TraktMutationOutboxSnapshot) {
        mutex.withLock {
            val prefs = context.getSharedPreferences(prefsName(), Context.MODE_PRIVATE)
            val payload = JsonObject().apply {
                addProperty(JSON_SCHEMA_VERSION, SCHEMA_VERSION)
                add(JSON_SNAPSHOT, snapshot.toJson())
            }
            prefs.edit()
                .putString(SNAPSHOT_KEY, payload.toString())
                .commit()
        }
    }

    suspend fun clear() {
        mutex.withLock {
            val prefs = context.getSharedPreferences(prefsName(), Context.MODE_PRIVATE)
            prefs.edit().remove(SNAPSHOT_KEY).commit()
        }
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
            gson.fromJson(obj, TraktMutationEnvelope::class.java)
                .copy(profileId = obj.intOrNull("profileId") ?: 1)
                .sanitizedOrNull()
        }.getOrNull()
    }

    private fun TraktMutationEnvelope.sanitizedOrNull(): TraktMutationEnvelope? {
        val cleanId = (id as String?)?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val cleanAdapterKey = (adapterKey as String?)?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val cleanMutationKind = (mutationKind as String?)?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val cleanPriority = (priority as TraktMutationPriorityBucket?) ?: return null
        val cleanState = (state as TraktMutationLifecycleState?) ?: return null

        return copy(
            id = cleanId,
            profileId = profileId.coerceAtLeast(1),
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
