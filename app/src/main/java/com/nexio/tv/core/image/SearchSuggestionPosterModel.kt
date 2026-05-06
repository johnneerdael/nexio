package com.nexio.tv.core.image

import java.net.URI
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
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
class SearchSuggestionPosterRegistry @Inject constructor() {
    private val rawUrlsByKey = ConcurrentHashMap<String, String>()

    fun register(tconst: String, rawUrl: String): SearchSuggestionPosterModel? {
        val normalizedUrl = rawUrl.trim().takeIf { it.isValidRemoteArtworkUrl() } ?: return null
        val key = "search-suggestion:${tconst.trim()}:${normalizedUrl.sha256()}"
        rawUrlsByKey[key] = normalizedUrl
        return SearchSuggestionPosterModel(key = key, tconst = tconst)
    }

    fun resolve(model: SearchSuggestionPosterModel): String? =
        rawUrlsByKey[model.key]

    private fun String.isValidRemoteArtworkUrl(): Boolean {
        val uri = runCatching { URI(this) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return false
        if (scheme != "http" && scheme != "https") return false
        return !uri.host.isNullOrBlank()
    }

    private fun String.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }
}
