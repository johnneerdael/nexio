package com.nexio.tv.core.metadata

import com.nexio.tv.BuildConfig

enum class MetadataCredentialSource {
    CUSTOM,
    BUILT_IN,
    MISSING
}

data class MetadataProviderCredential(
    val apiKey: String,
    val pin: String = "",
    val source: MetadataCredentialSource
) {
    val missing: Boolean get() = source == MetadataCredentialSource.MISSING || apiKey.isBlank()
    val cacheToken: String get() = "${source.name.lowercase()}:${apiKey.hashCode()}:${pin.hashCode()}"
}

object MetadataProviderConfig {
    const val DEFAULT_TMDB_API_URL = "https://api.themoviedb.org/3/"
    const val DEFAULT_TVDB_API_URL = "https://api4.thetvdb.com/v4/"

    fun tmdbBaseUrl(): String = normalizeBaseUrl(BuildConfig.TMDB_API_URL, DEFAULT_TMDB_API_URL)

    fun tvdbBaseUrl(): String = normalizeBaseUrl(BuildConfig.TVDB_API_URL, DEFAULT_TVDB_API_URL)

    fun builtInTmdbApiKey(): String = BuildConfig.TMDB_API_KEY.trim()

    fun builtInTvdbApiKey(): String = BuildConfig.TVDB_API_KEY.trim()

    fun normalizeBaseUrl(raw: String?, fallback: String): String {
        val value = raw?.trim()?.takeIf { it.isNotBlank() } ?: fallback
        return if (value.endsWith("/")) value else "$value/"
    }

    fun resolveCredential(
        customApiKey: String,
        builtInApiKey: String,
        pin: String = ""
    ): MetadataProviderCredential {
        val custom = customApiKey.trim()
        if (custom.isNotBlank()) {
            return MetadataProviderCredential(
                apiKey = custom,
                pin = pin.trim(),
                source = MetadataCredentialSource.CUSTOM
            )
        }

        val builtIn = builtInApiKey.trim()
        if (builtIn.isNotBlank()) {
            return MetadataProviderCredential(
                apiKey = builtIn,
                pin = "",
                source = MetadataCredentialSource.BUILT_IN
            )
        }

        return MetadataProviderCredential(
            apiKey = "",
            pin = "",
            source = MetadataCredentialSource.MISSING
        )
    }
}
