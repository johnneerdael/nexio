package com.nexio.tv.data.integration.posters

import com.nexio.tv.core.metadata.router.MetadataMediaKind
import com.nexio.tv.core.metadata.router.MetadataPrimaryProvider
import com.nexio.tv.core.metadata.router.MetadataRoute
import com.nexio.tv.domain.model.ContentType

/**
 * Shared utilities for poster provider adapters.
 *
 * F2-13-E: extracted from [RpdbMetadataProviderAdapter] and [TopPostersMetadataProviderAdapter]
 * to eliminate the duplicated private extension function in each adapter.
 */
internal fun MetadataMediaKind.toContentType(): ContentType = when (this) {
    MetadataMediaKind.MOVIE -> ContentType.MOVIE
    MetadataMediaKind.SERIES, MetadataMediaKind.ANIME -> ContentType.SERIES
    MetadataMediaKind.UNKNOWN -> ContentType.UNKNOWN
}

internal fun MetadataRoute.premiumPosterStableContentId(): String? =
    imdbStableId(targetIds[MetadataPrimaryProvider.IMDB])
        ?: numericProviderStableId(targetIds[MetadataPrimaryProvider.TMDB], "tmdb")
        ?: numericProviderStableId(targetIds[MetadataPrimaryProvider.TVDB], "tvdb")

private fun imdbStableId(raw: String?): String? {
    val value = raw
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?: return null
    val candidate = if (value.startsWith("imdb:", ignoreCase = true)) {
        value.substringAfter(':').trim()
    } else {
        value
    }
    return candidate.takeIf { it.matches(IMDB_ID_REGEX) }
}

private fun numericProviderStableId(raw: String?, prefix: String): String? {
    val value = raw
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?: return null
    val candidate = when {
        value.startsWith("$prefix:", ignoreCase = true) -> value.substringAfter(':').trim()
        ':' in value -> return null
        else -> value
    }
    return candidate
        .takeIf { it.matches(NUMERIC_ID_REGEX) }
        ?.let { "$prefix:$it" }
}

private val IMDB_ID_REGEX = Regex("tt\\d+", RegexOption.IGNORE_CASE)
private val NUMERIC_ID_REGEX = Regex("\\d+")
