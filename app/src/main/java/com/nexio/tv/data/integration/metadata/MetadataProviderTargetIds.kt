package com.nexio.tv.data.integration.metadata

internal object MetadataProviderTargetIds {
    fun tmdbInt(raw: String?): Int? =
        providerValue(raw, "tmdb")?.toIntOrNull()

    fun tvdbInt(raw: String?): Int? =
        providerValue(raw, "tvdb")?.toIntOrNull()

    fun kitsu(raw: String?): String? =
        providerValue(raw, "kitsu")

    fun mal(raw: String?): String? =
        providerValue(raw, "mal")

    fun anilist(raw: String?): String? =
        providerValue(raw, "anilist")

    fun anidb(raw: String?): String? =
        providerValue(raw, "anidb")

    /**
     * Imdb is special: the canonical form is `ttNNNNNN` (no colon), but Stremio addons
     * also send `imdb:tt0111161`. Return the bare `tt...` string in both cases.
     */
    fun imdb(raw: String?): String? {
        val trimmed = raw?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return when {
            trimmed.startsWith("tt", ignoreCase = true) -> trimmed
            trimmed.startsWith("imdb:", ignoreCase = true) -> {
                val tail = trimmed.substringAfter(':').takeIf { it.isNotBlank() }
                if (tail != null && tail.startsWith("tt", ignoreCase = true)) tail else null
            }
            else -> null
        }
    }

    private fun providerValue(raw: String?, expectedPrefix: String): String? {
        val value = raw
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val prefix = value.substringBefore(':', missingDelimiterValue = "")
        if (prefix.isEmpty()) return value
        if (!prefix.equals(expectedPrefix, ignoreCase = true)) return null
        return value.substringAfter(':').takeIf { it.isNotBlank() }
    }
}
