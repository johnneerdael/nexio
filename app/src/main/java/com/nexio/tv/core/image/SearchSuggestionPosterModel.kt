package com.nexio.tv.core.image

import coil.key.Keyer
import coil.request.Options
import java.net.URI
import java.security.MessageDigest
import java.util.LinkedHashMap
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

data class SearchSuggestionPosterModel(
    val key: String,
    val tconst: String
) {
    init {
        require(key.isNotBlank()) { "SearchSuggestionPosterModel key must not be blank" }
        require(tconst.isNotBlank()) { "SearchSuggestionPosterModel tconst must not be blank" }
    }
}

@Singleton
class SearchSuggestionPosterRegistry(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES
) {
    @Inject
    constructor() : this(DEFAULT_MAX_ENTRIES)

    init {
        require(maxEntries > 0) { "SearchSuggestionPosterRegistry maxEntries must be positive" }
    }

    private val rawUrlsByKey = object : LinkedHashMap<String, String>(maxEntries, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>): Boolean =
            size > maxEntries
    }

    val size: Int
        get() = synchronized(rawUrlsByKey) { rawUrlsByKey.size }

    fun register(tconst: String, rawUrl: String): SearchSuggestionPosterModel? {
        val normalizedUrl = rawUrl.trim().takeIf { it.isValidRemoteArtworkUrl() } ?: return null
        val key = "search-suggestion:${tconst.trim()}:${normalizedUrl.cacheIdentity().sha256()}"
        synchronized(rawUrlsByKey) {
            rawUrlsByKey[key] = normalizedUrl
        }
        return SearchSuggestionPosterModel(key = key, tconst = tconst)
    }

    fun resolve(model: SearchSuggestionPosterModel): String? =
        synchronized(rawUrlsByKey) { rawUrlsByKey[model.key] }

    fun retainOnly(models: Set<SearchSuggestionPosterModel>) {
        val retainedKeys = models.mapTo(mutableSetOf()) { it.key }
        synchronized(rawUrlsByKey) {
            rawUrlsByKey.keys.retainAll(retainedKeys)
        }
    }

    private fun String.isValidRemoteArtworkUrl(): Boolean {
        val uri = runCatching { URI(this) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return false
        if (scheme != "http" && scheme != "https") return false
        return !uri.host.isNullOrBlank()
    }

    private fun String.cacheIdentity(): String {
        val uri = URI(this)
        return URI(
            uri.scheme,
            uri.authority,
            uri.path,
            null,
            null
        ).toString()
    }

    private fun String.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private companion object {
        const val DEFAULT_MAX_ENTRIES = 64
    }
}

class SearchSuggestionPosterKeyer : Keyer<SearchSuggestionPosterModel> {
    override fun key(data: SearchSuggestionPosterModel, options: Options): String =
        data.key
}
