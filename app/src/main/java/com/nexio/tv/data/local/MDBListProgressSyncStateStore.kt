package com.nexio.tv.data.local

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import com.nexio.tv.core.profile.ProfileManager
import com.nexio.tv.domain.model.WatchProgress
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

data class MDBListProgressSyncState(
    val lastWatchedSyncAt: String? = null,
    val rows: List<WatchProgress> = emptyList()
)

@Singleton
open class MDBListProgressSyncStateStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val profileManager: ProfileManager
) {
    private val gson = Gson()
    private val rowListType = object : TypeToken<List<WatchProgress>>() {}.type

    open fun read(profileId: Int = activeProfileId()): MDBListProgressSyncState {
        val file = snapshotFileFor(profileId)
        if (!file.exists()) return MDBListProgressSyncState()
        return runCatching { readSnapshot(file) }
            .onFailure { error ->
                Log.w(TAG, "Failed to read MDBList progress sync state", error)
                clear(profileId)
            }
            .getOrDefault(MDBListProgressSyncState())
    }

    open fun write(state: MDBListProgressSyncState, profileId: Int = activeProfileId()) {
        runCatching {
            val target = snapshotFileFor(profileId)
            target.parentFile?.mkdirs()
            val temp = File(target.parentFile, "${target.name}.tmp")
            FileOutputStream(temp).use { fos ->
                BufferedWriter(OutputStreamWriter(fos, Charsets.UTF_8)).use { bw ->
                    JsonWriter(bw).use { writer ->
                        writer.beginObject()
                        writer.name("schemaVersion").value(SCHEMA_VERSION)
                        writer.name("lastWatchedSyncAt").value(state.lastWatchedSyncAt)
                        writer.name("rows")
                        gson.toJson(state.rows, rowListType, writer)
                        writer.endObject()
                    }
                }
            }
            Files.move(
                temp.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        }.onFailure { error ->
            Log.w(TAG, "Failed to write MDBList progress sync state", error)
        }
    }

    open fun clear(profileId: Int = activeProfileId()) {
        runCatching { snapshotFileFor(profileId).takeIf { it.exists() }?.delete() }
            .onFailure { error -> Log.w(TAG, "Failed to clear MDBList progress sync state", error) }
    }

    private fun activeProfileId(): Int = profileManager.activeProfileId.value.takeIf { it in 1..4 } ?: 1

    private fun snapshotFileFor(profileId: Int): File =
        File(File(context.filesDir, SNAPSHOT_DIR), "p$profileId.json")

    private fun readSnapshot(file: File): MDBListProgressSyncState {
        var schemaVersion = -1
        var lastWatchedSyncAt: String? = null
        var rows: List<WatchProgress> = emptyList()
        FileInputStream(file).use { fis ->
            BufferedReader(InputStreamReader(fis, Charsets.UTF_8)).use { br ->
                JsonReader(br).use { reader ->
                    if (reader.peek() == JsonToken.NULL) {
                        reader.nextNull()
                        return MDBListProgressSyncState()
                    }
                    reader.beginObject()
                    while (reader.hasNext()) {
                        when (reader.nextName()) {
                            "schemaVersion" -> schemaVersion = reader.nextInt()
                            "lastWatchedSyncAt" -> lastWatchedSyncAt = if (reader.peek() == JsonToken.NULL) {
                                reader.nextNull()
                                null
                            } else {
                                reader.nextString()
                            }
                            "rows" -> rows = gson.fromJson(reader, rowListType) ?: emptyList()
                            else -> reader.skipValue()
                        }
                    }
                    reader.endObject()
                }
            }
        }
        return if (schemaVersion == SCHEMA_VERSION) {
            MDBListProgressSyncState(lastWatchedSyncAt = lastWatchedSyncAt, rows = rows)
        } else {
            MDBListProgressSyncState()
        }
    }

    private companion object {
        private const val TAG = "MDBListProgressState"
        private const val SNAPSHOT_DIR = "mdblist-progress-sync-v1"
        private const val SCHEMA_VERSION = 1
    }
}
