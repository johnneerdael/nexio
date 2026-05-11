package com.nexio.tv.data.local

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import com.nexio.tv.core.locale.AppLocaleResolver
import com.nexio.tv.core.profile.ProfileManager
import com.nexio.tv.domain.model.ResolvedDisplayItem
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

/**
 * Phase 3.7 — file-backed streaming JSON persistence for the typed
 * authority's per-item state. Companion to [HomeCatalogSnapshotStore]:
 * the snapshot stores rail structure (legacy CatalogRow shape for now);
 * this store holds the typed [ResolvedDisplayItem] content. Both are
 * flushed in the same write-coordination call and read together at
 * cold-start; the repository's in-memory state is warmed from this file
 * before render so rails can be hydrated with typed content the moment
 * they paint.
 *
 * Per-profile + per-language file path under
 * `filesDir/resolved-display-v1/p<profileId>_<lang>.json`. Mirrors the
 * HomeCatalogSnapshotStore recipe: streaming JsonReader for reads,
 * streaming JsonWriter + atomic Files.move rename for writes.
 */
@Singleton
class ResolvedDisplaySnapshotStore private constructor(
    private val rootDir: () -> File,
    private val activeProfileId: () -> Int,
    private val currentLanguageTag: () -> String,
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        profileManager: ProfileManager,
    ) : this(
        rootDir = { File(context.filesDir, SNAPSHOT_DIR) },
        activeProfileId = { profileManager.activeProfileId.value },
        currentLanguageTag = { AppLocaleResolver.resolveEffectiveAppLanguageTag(context) },
    )

    companion object {
        private const val TAG = "ResolvedDisplayStore"
        private const val SNAPSHOT_DIR = "resolved-display-v1"
        private const val SCHEMA_VERSION = 1
        private val gson = Gson()
        private val mapType = object : TypeToken<Map<String, ResolvedDisplayItem>>() {}.type

        @JvmStatic
        fun forTesting(
            rootDir: File,
            activeProfileId: () -> Int,
            currentLanguageTag: () -> String = { "en" },
        ): ResolvedDisplaySnapshotStore = ResolvedDisplaySnapshotStore(
            rootDir = { rootDir },
            activeProfileId = activeProfileId,
            currentLanguageTag = currentLanguageTag,
        )
    }

    fun write(items: Map<String, ResolvedDisplayItem>, profileId: Int = activeProfileId()) {
        runCatching {
            val target = snapshotFileFor(profileId)
            target.parentFile?.mkdirs()
            val tempFile = File(target.parentFile, "${target.name}.tmp")
            FileOutputStream(tempFile).use { fos ->
                BufferedWriter(OutputStreamWriter(fos, Charsets.UTF_8)).use { bw ->
                    JsonWriter(bw).use { writer ->
                        writer.beginObject()
                        writer.name("schemaVersion").value(SCHEMA_VERSION)
                        writer.name("items")
                        gson.toJson(items, mapType, writer)
                        writer.endObject()
                    }
                }
            }
            Files.move(
                tempFile.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        }.onFailure { error ->
            Log.w(TAG, "Failed to write resolved display snapshot", error)
        }
    }

    fun read(profileId: Int = activeProfileId()): Map<String, ResolvedDisplayItem> {
        val file = snapshotFileFor(profileId)
        if (!file.exists()) return emptyMap()
        return runCatching {
            FileInputStream(file).use { fis ->
                BufferedReader(InputStreamReader(fis, Charsets.UTF_8)).use { br ->
                    JsonReader(br).use { reader ->
                        if (reader.peek() == JsonToken.NULL) {
                            reader.nextNull()
                            return@runCatching emptyMap<String, ResolvedDisplayItem>()
                        }
                        var schemaVersion = -1
                        var items: Map<String, ResolvedDisplayItem> = emptyMap()
                        reader.beginObject()
                        while (reader.hasNext()) {
                            when (reader.nextName()) {
                                "schemaVersion" -> {
                                    schemaVersion = reader.nextInt()
                                    if (schemaVersion > SCHEMA_VERSION) {
                                        return@runCatching emptyMap<String, ResolvedDisplayItem>()
                                    }
                                }
                                "items" -> {
                                    items = gson.fromJson(reader, mapType) ?: emptyMap()
                                }
                                else -> reader.skipValue()
                            }
                        }
                        reader.endObject()
                        items
                    }
                }
            }
        }.onFailure { error ->
            Log.w(TAG, "Failed to read resolved display snapshot", error)
        }.getOrDefault(emptyMap())
    }

    fun clear(profileId: Int = activeProfileId()) {
        runCatching {
            snapshotFileFor(profileId).takeIf { it.exists() }?.delete()
        }
    }

    private fun snapshotFileFor(profileId: Int): File {
        val parent = rootDir()
        if (!parent.exists()) parent.mkdirs()
        val sanitizedTag = currentLanguageTag()
            .lowercase()
            .replace(Regex("[^a-z0-9_-]"), "_")
            .ifBlank { "unknown" }
        return File(parent, "p${profileId.coerceAtLeast(1)}_${sanitizedTag}.json")
    }
}
