package com.nexio.tv.data.trakt.outbox

import android.content.Context
import com.google.gson.Gson
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TraktMutationOutboxStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val PREFS_NAME = "trakt_mutation_outbox"
        private const val SNAPSHOT_KEY = "snapshot"
        private const val SCHEMA_VERSION = 1
    }

    private data class PersistedState(
        val schemaVersion: Int = SCHEMA_VERSION,
        val snapshot: TraktMutationOutboxSnapshot = TraktMutationOutboxSnapshot()
    )

    private val gson = Gson()
    private val mutex = Mutex()

    suspend fun read(): TraktMutationOutboxSnapshot {
        return mutex.withLock {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val raw = prefs.getString(SNAPSHOT_KEY, null)?.takeIf { it.isNotBlank() }
                ?: return@withLock TraktMutationOutboxSnapshot()
            val persisted = runCatching {
                gson.fromJson(raw, PersistedState::class.java)
            }.getOrNull()
            if (persisted == null || persisted.schemaVersion > SCHEMA_VERSION) {
                return@withLock TraktMutationOutboxSnapshot()
            }
            persisted.snapshot
        }
    }

    suspend fun write(snapshot: TraktMutationOutboxSnapshot) {
        mutex.withLock {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val payload = PersistedState(snapshot = snapshot)
            prefs.edit()
                .putString(SNAPSHOT_KEY, gson.toJson(payload))
                .commit()
        }
    }

    suspend fun clear() {
        mutex.withLock {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().remove(SNAPSHOT_KEY).commit()
        }
    }
}
