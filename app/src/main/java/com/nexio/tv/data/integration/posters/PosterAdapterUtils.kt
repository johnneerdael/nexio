package com.nexio.tv.data.integration.posters

import com.nexio.tv.core.artwork.ArtworkExternalIdSelector
import com.nexio.tv.core.artwork.ArtworkType
import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.core.metadata.router.MetadataMediaKind
import com.nexio.tv.core.metadata.router.MetadataPrimaryProvider
import com.nexio.tv.core.metadata.router.MetadataRoute
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.ProviderIds

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

internal fun MetadataRoute.premiumPosterStableContentId(provider: IntegrationProvider): String? =
    ArtworkExternalIdSelector()
        .selectIds(
            provider = provider,
            imageType = ArtworkType.POSTER,
            mediaKind = mediaKind,
            providerIds = targetIds.toProviderIds()
        )
        .firstOrNull()
        ?.let { "${it.idType}:${it.mediaId}" }

private fun Map<MetadataPrimaryProvider, String>.toProviderIds(): ProviderIds =
    ProviderIds(
        imdb = imdbTargetId(this[MetadataPrimaryProvider.IMDB]),
        tmdb = numericTargetId(this[MetadataPrimaryProvider.TMDB], "tmdb"),
        tvdb = numericTargetId(this[MetadataPrimaryProvider.TVDB], "tvdb"),
        trakt = numericTargetId(this[MetadataPrimaryProvider.TRAKT], "trakt"),
        kitsu = numericTargetId(this[MetadataPrimaryProvider.KITSU], "kitsu")
    )

private fun imdbTargetId(raw: String?): String? {
    val value = raw?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val candidate = when {
        value.startsWith("imdb:", ignoreCase = true) -> value.substringAfter(':').trim()
        ':' in value -> return null
        else -> value
    }
    return candidate.takeIf { it.matches(IMDB_ID_REGEX) }
}

private fun numericTargetId(raw: String?, prefix: String): String? {
    val value = raw?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val candidate = when {
        value.startsWith("$prefix:", ignoreCase = true) -> value.substringAfter(':').trim()
        ':' in value -> return null
        else -> value
    }
    return candidate.takeIf { it.matches(NUMERIC_ID_REGEX) }
}

private val IMDB_ID_REGEX = Regex("tt\\d+", RegexOption.IGNORE_CASE)
private val NUMERIC_ID_REGEX = Regex("\\d+")
