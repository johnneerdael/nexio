package com.nexio.tv.data.integration.metadata

internal object MetadataProviderTargetIds {
    fun tmdbInt(raw: String?): Int? =
        providerValue(raw, "tmdb")?.toIntOrNull()

    fun tvdbInt(raw: String?): Int? =
        providerValue(raw, "tvdb")?.toIntOrNull()

    fun kitsu(raw: String?): String? =
        providerValue(raw, "kitsu")

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
