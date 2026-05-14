package com.nexio.tv.data.local

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter
import com.nexio.tv.core.profile.ProfileManager
import com.nexio.tv.domain.model.LibraryEntry
import com.nexio.tv.domain.model.LibraryListTab
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
class MDBListLibrarySnapshotStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val profileManager: ProfileManager
) {
    data class Snapshot(
        val rows: List<LibraryEntry> = emptyList(),
        val tabs: List<LibraryListTab> = emptyList(),
        val selectedListKey: String? = null,
        val updatedAtMs: Long = 0L
    )

    private val gson = Gson()

    fun read(profileId: Int = profileManager.activeProfileId.value): Snapshot? {
        val file = snapshotFile(profileId)
        if (!file.exists()) return null
        return runCatching {
            FileInputStream(file).use { fis ->
                BufferedReader(InputStreamReader(fis, Charsets.UTF_8)).use { br ->
                    JsonReader(br).use { reader ->
                        gson.fromJson<Snapshot>(reader, Snapshot::class.java)
                    }
                }
            }
        }.onFailure { error ->
            Log.w(TAG, "Failed to read MDBList library snapshot", error)
        }.getOrNull()
    }

    fun write(snapshot: Snapshot, profileId: Int = profileManager.activeProfileId.value) {
        runCatching {
            val target = snapshotFile(profileId)
            target.parentFile?.mkdirs()
            val temp = File(target.parentFile, "${target.name}.tmp")
            FileOutputStream(temp).use { fos ->
                BufferedWriter(OutputStreamWriter(fos, Charsets.UTF_8)).use { bw ->
                    JsonWriter(bw).use { writer ->
                        gson.toJson(snapshot, Snapshot::class.java, writer)
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
            Log.w(TAG, "Failed to write MDBList library snapshot", error)
        }
    }

    private fun snapshotFile(profileId: Int): File {
        return File(File(context.filesDir, SNAPSHOT_DIR), "p${profileId}.json")
    }

    private companion object {
        private const val TAG = "MDBListLibraryStore"
        private const val SNAPSHOT_DIR = "mdblist-library-snapshot-v1"
    }
}
