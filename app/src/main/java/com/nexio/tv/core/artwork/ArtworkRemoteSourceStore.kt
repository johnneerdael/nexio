package com.nexio.tv.core.artwork

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

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
                val type = object : TypeToken<Map<String, String>>() {}.type
                val restored = gson.fromJson<Map<String, String>>(file.readText(), type).orEmpty()
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
        tmp.writeText(gson.toJson(sourcesByHash.toSortedMap()))
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
    }
}

internal fun String.isPremiumProviderRawUrl(): Boolean =
    contains("api.ratingposterdb.com", ignoreCase = true) ||
        contains("api.top-posters.com", ignoreCase = true)
