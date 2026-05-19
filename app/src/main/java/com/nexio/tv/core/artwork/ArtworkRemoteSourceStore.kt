package com.nexio.tv.core.artwork

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.SortedMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

interface ArtworkRemoteSourceStore {
    fun put(
        normalizedUrlHash: String,
        source: SensitiveArtworkUrl
    )

    fun get(normalizedUrlHash: String): SensitiveArtworkUrl?
}

object NoopArtworkRemoteSourceStore : ArtworkRemoteSourceStore {
    override fun put(
        normalizedUrlHash: String,
        source: SensitiveArtworkUrl
    ) = Unit

    override fun get(normalizedUrlHash: String): SensitiveArtworkUrl? = null
}

class FileBackedArtworkRemoteSourceStore(
    private val file: File,
    private val gson: Gson,
    private val writeDebounceMs: Long = 0L
) : ArtworkRemoteSourceStore {
    private val lock = Any()
    private val loadLock = Any()
    @Volatile
    private var loaded = false
    private var sourcesByHash: MutableMap<String, String> = mutableMapOf()
    private val flushExecutor: ScheduledExecutorService? =
        if (writeDebounceMs > 0L) {
            Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "ArtworkRemoteSourceFlush").apply { isDaemon = true }
            }
        } else {
            null
        }
    private var pendingFlush: ScheduledFuture<*>? = null
    private var dirty = false

    override fun put(
        normalizedUrlHash: String,
        source: SensitiveArtworkUrl
    ) {
        if (normalizedUrlHash.isBlank()) return
        ensureLoaded()?.let(::writeSnapshotToFile)
        val snapshotToWrite = synchronized(lock) {
            if (source.value.isPremiumProviderRawUrl()) {
                sourcesByHash.remove(normalizedUrlHash)
            } else {
                sourcesByHash[normalizedUrlHash] = source.value
            }
            schedulePersistLocked()
        }
        snapshotToWrite?.let(::writeSnapshotToFile)
    }

    override fun get(normalizedUrlHash: String): SensitiveArtworkUrl? {
        ensureLoaded()?.let(::writeSnapshotToFile)
        return synchronized(lock) {
            sourcesByHash[normalizedUrlHash]
                ?.takeUnless { it.isPremiumProviderRawUrl() }
                ?.let(SensitiveArtworkUrl::of)
        }
    }

    private fun ensureLoaded(): Map<String, String>? {
        if (loaded) return null
        return synchronized(loadLock) {
            if (loaded) return@synchronized null
            val loadedSources = loadSourcesFromFile()
            synchronized(lock) {
                sourcesByHash = loadedSources.sources
                loaded = true
                if (loadedSources.cleanedInvalidEntries) {
                    schedulePersistLocked()
                } else {
                    null
                }
            }
        }
    }

    private fun loadSourcesFromFile(): LoadedSources =
        runCatching {
            if (!file.exists()) {
                LoadedSources(mutableMapOf(), cleanedInvalidEntries = false)
            } else {
                // CLAUDE.md hard rule #3: stream JSON from disk; do not
                // materialize the whole cache as a String while playback is live.
                val type = object : TypeToken<Map<String, String>>() {}.type
                val restored: Map<String, String> = FileInputStream(file).use { fis ->
                    BufferedReader(InputStreamReader(fis, Charsets.UTF_8)).use { br ->
                        JsonReader(br).use { reader ->
                            gson.fromJson<Map<String, String>>(reader, type)
                        }
                    }
                }.orEmpty()
                val filtered = restored
                    .filterKeys { it.isNotBlank() }
                    .filterValues { !it.isPremiumProviderRawUrl() }
                    .toMutableMap()
                LoadedSources(
                    sources = filtered,
                    cleanedInvalidEntries = filtered.size != restored.size
                )
            }
        }.getOrDefault(LoadedSources(mutableMapOf(), cleanedInvalidEntries = false))

    internal fun flushPendingWritesForTest() {
        val snapshotToWrite = synchronized(lock) {
            pendingFlush?.cancel(false)
            pendingFlush = null
            if (!dirty) return
            dirty = false
            sourcesByHash.toMap()
        }
        writeSnapshotToFile(snapshotToWrite)
    }

    private fun schedulePersistLocked(): Map<String, String>? {
        dirty = true
        if (writeDebounceMs <= 0L) {
            dirty = false
            return sourcesByHash.toMap()
        }
        val currentFlush = pendingFlush
        if (currentFlush != null && !currentFlush.isDone && !currentFlush.isCancelled) return null
        pendingFlush = flushExecutor?.schedule(
            ::executeScheduledFlush,
            writeDebounceMs,
            TimeUnit.MILLISECONDS
        )
        return null
    }

    private fun executeScheduledFlush() {
        val snapshotToWrite = synchronized(lock) {
            pendingFlush = null
            if (!dirty) return
            dirty = false
            sourcesByHash.toMap()
        }
        writeSnapshotToFile(snapshotToWrite)
    }

    private fun writeSnapshotToFile(snapshot: Map<String, String>) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile ?: File("."), "${file.name}.tmp")
        val sorted: SortedMap<String, String> = snapshot.toSortedMap()
        FileOutputStream(tmp).use { fos ->
            BufferedWriter(OutputStreamWriter(fos, Charsets.UTF_8)).use { bw ->
                JsonWriter(bw).use { writer ->
                    gson.toJson(sorted, REMOTE_SOURCE_MAP_TYPE, writer)
                }
            }
        }
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
    }

    private companion object {
        private val REMOTE_SOURCE_MAP_TYPE =
            object : TypeToken<SortedMap<String, String>>() {}.type
    }

    private data class LoadedSources(
        val sources: MutableMap<String, String>,
        val cleanedInvalidEntries: Boolean
    )
}

internal fun String.isPremiumProviderRawUrl(): Boolean =
    contains("api.ratingposterdb.com", ignoreCase = true) ||
        contains("api.top-posters.com", ignoreCase = true)
