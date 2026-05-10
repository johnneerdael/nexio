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
    private val gson: Gson
) : ArtworkRemoteSourceStore {
    private val lock = Any()
    private var loaded = false
    private var sourcesByHash: MutableMap<String, String> = mutableMapOf()

    override fun put(
        normalizedUrlHash: String,
        source: SensitiveArtworkUrl
    ) {
        if (normalizedUrlHash.isBlank()) return
        synchronized(lock) {
            ensureLoaded()
            if (source.value.isPremiumProviderRawUrl()) {
                sourcesByHash.remove(normalizedUrlHash)
            } else {
                sourcesByHash[normalizedUrlHash] = source.value
            }
            flush()
        }
    }

    override fun get(normalizedUrlHash: String): SensitiveArtworkUrl? =
        synchronized(lock) {
            ensureLoaded()
            sourcesByHash[normalizedUrlHash]
                ?.takeUnless { it.isPremiumProviderRawUrl() }
                ?.let(SensitiveArtworkUrl::of)
        }

    private fun ensureLoaded() {
        if (loaded) return
        sourcesByHash = runCatching {
            if (!file.exists()) {
                mutableMapOf()
            } else {
                // CLAUDE.md hard rule #3: streaming read, no file.readText() +
                // gson.fromJson(String, type). The previous overload wrapped the
                // entire file as a String (and a StringReader pinning it for the
                // parse) every time the cache loaded.
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
                if (filtered.size != restored.size) {
                    sourcesByHash = filtered
                    flush()
                }
                filtered
            }
        }.getOrDefault(mutableMapOf())
        loaded = true
    }

    private fun flush() {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile ?: File("."), "${file.name}.tmp")
        val sorted: SortedMap<String, String> = sourcesByHash.toSortedMap()
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
}

internal fun String.isPremiumProviderRawUrl(): Boolean =
    contains("api.ratingposterdb.com", ignoreCase = true) ||
        contains("api.top-posters.com", ignoreCase = true)
