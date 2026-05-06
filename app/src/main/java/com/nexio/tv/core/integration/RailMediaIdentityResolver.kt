package com.nexio.tv.core.integration

import com.nexio.tv.data.local.integration.ExternalIdEntity
import com.nexio.tv.data.local.integration.MediaIdentityEntity
import com.nexio.tv.domain.model.LibraryEntry
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.WatchProgress
import javax.inject.Inject
import javax.inject.Singleton

data class ResolvedRailMediaIdentity(
    val mediaIdentity: MediaIdentityEntity,
    val externalIds: List<ExternalIdEntity>
)

/**
 * Temporary compatibility adapter for rail cache ownership keys.
 *
 * Canonical identity ownership belongs to StableIdBundleResolver. This adapter may normalize
 * already-observed rail identifiers for cache key stability only. It must not perform network
 * identity bridging and must not be injected into UI, ViewModel, player, or screensaver code.
 *
 * Expiration: remove after MetaPreview/RailItemPreview carry StableIdBundle directly.
 */
@Singleton
class RailMediaIdentityResolver @Inject constructor() {
    fun fromPreview(
        preview: MetaPreview,
        updatedAtEpochMs: Long = System.currentTimeMillis()
    ): ResolvedRailMediaIdentity =
        resolve(
            mediaType = preview.apiType,
            rawId = preview.id,
            title = preview.name,
            year = extractYear(preview.releaseInfo),
            updatedAtEpochMs = updatedAtEpochMs
        )

    fun fromLibraryEntry(
        entry: LibraryEntry,
        updatedAtEpochMs: Long = System.currentTimeMillis()
    ): ResolvedRailMediaIdentity =
        resolve(
            mediaType = entry.type,
            rawId = entry.id,
            title = entry.name,
            year = extractYear(entry.releaseInfo),
            imdbId = entry.imdbId,
            tmdbId = entry.tmdbId?.toString(),
            traktId = entry.traktId?.toString(),
            updatedAtEpochMs = updatedAtEpochMs
        )

    fun fromWatchProgress(
        progress: WatchProgress,
        title: String?,
        year: Int?,
        updatedAtEpochMs: Long = System.currentTimeMillis()
    ): ResolvedRailMediaIdentity =
        resolve(
            mediaType = progress.contentType,
            rawId = progress.contentId,
            title = title ?: progress.name,
            year = year,
            traktId = progress.traktShowId?.toString() ?: progress.traktMovieId?.toString(),
            updatedAtEpochMs = updatedAtEpochMs
        )

    fun fromRawContent(
        mediaType: String,
        rawId: String,
        title: String?,
        year: Int?,
        imdbId: String? = null,
        tmdbId: String? = null,
        traktId: String? = null,
        updatedAtEpochMs: Long = System.currentTimeMillis()
    ): ResolvedRailMediaIdentity =
        resolve(
            mediaType = mediaType,
            rawId = rawId,
            title = title,
            year = year,
            imdbId = imdbId,
            tmdbId = tmdbId,
            traktId = traktId,
            updatedAtEpochMs = updatedAtEpochMs
        )

    private fun resolve(
        mediaType: String,
        rawId: String,
        title: String?,
        year: Int?,
        imdbId: String? = null,
        tmdbId: String? = null,
        traktId: String? = null,
        updatedAtEpochMs: Long
    ): ResolvedRailMediaIdentity {
        val raw = rawId.trim()
        val normalizedMediaType = mediaType.trim().lowercase()
        val parsedIds = parseIds(raw)
        val canonicalImdb = imdbId?.trim().takeUnless { it.isNullOrBlank() } ?: parsedIds.imdb
        val canonicalTmdb = tmdbId?.trim().takeUnless { it.isNullOrBlank() } ?: parsedIds.tmdb
        val canonicalTrakt = traktId?.trim().takeUnless { it.isNullOrBlank() } ?: parsedIds.trakt
        val mediaKey = when {
            canonicalImdb != null -> "$normalizedMediaType:imdb:$canonicalImdb"
            canonicalTmdb != null -> "$normalizedMediaType:tmdb:$canonicalTmdb"
            canonicalTrakt != null -> "$normalizedMediaType:trakt:$canonicalTrakt"
            else -> "$normalizedMediaType:raw:$raw"
        }
        val externalIds = buildList {
            canonicalImdb?.let { add(externalId(mediaKey, "IMDB", it)) }
            canonicalTmdb?.let { add(externalId(mediaKey, "TMDB", it)) }
            canonicalTrakt?.let { add(externalId(mediaKey, "TRAKT", it)) }
        }

        return ResolvedRailMediaIdentity(
            mediaIdentity = MediaIdentityEntity(
                mediaKey = mediaKey,
                mediaType = normalizedMediaType,
                title = title?.trim()?.ifBlank { null },
                year = year,
                updatedAtEpochMs = updatedAtEpochMs
            ),
            externalIds = externalIds
        )
    }

    private fun externalId(mediaKey: String, provider: String, externalId: String): ExternalIdEntity =
        ExternalIdEntity(
            key = "$mediaKey:$provider:TITLE:$externalId",
            mediaKey = mediaKey,
            provider = provider,
            externalId = externalId,
            idType = "TITLE"
        )

    private fun parseIds(rawId: String): ParsedRailIds {
        val raw = rawId.trim()
        if (raw.isEmpty()) return ParsedRailIds()
        if (raw.startsWith("tt", ignoreCase = true)) {
            return ParsedRailIds(imdb = raw.substringBefore(':'))
        }

        val segments = raw.split(':')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (segments.isEmpty()) return ParsedRailIds()

        if (segments.first().equals("tmdb", ignoreCase = true)) {
            return ParsedRailIds(tmdb = segments.getOrNull(1)?.takeIf { it.isNotBlank() })
        }
        if (segments.first().equals("trakt", ignoreCase = true)) {
            return ParsedRailIds(trakt = segments.getOrNull(1)?.takeIf { it.isNotBlank() })
        }
        if (segments.first().equals("imdb", ignoreCase = true)) {
            return ParsedRailIds(imdb = segments.getOrNull(1)?.takeIf { it.isNotBlank() })
        }

        val providerSegmentIndex = segments.indexOfFirst { segment ->
            segment.equals("imdb", ignoreCase = true) ||
                segment.equals("tmdb", ignoreCase = true) ||
                segment.equals("trakt", ignoreCase = true)
        }
        if (providerSegmentIndex >= 0) {
            val provider = segments[providerSegmentIndex].lowercase()
            val value = segments.getOrNull(providerSegmentIndex + 1)?.takeIf { it.isNotBlank() }
            return when (provider) {
                "imdb" -> ParsedRailIds(imdb = value)
                "tmdb" -> ParsedRailIds(tmdb = value)
                "trakt" -> ParsedRailIds(trakt = value)
                else -> ParsedRailIds()
            }
        }

        val imdbFromSegments = segments.firstOrNull { it.startsWith("tt", ignoreCase = true) }
        if (imdbFromSegments != null) {
            return ParsedRailIds(imdb = imdbFromSegments)
        }

        return ParsedRailIds(
            trakt = segments.firstOrNull()?.takeIf { segment ->
                segment.toIntOrNull() != null
            }
        )
    }

    private fun extractYear(value: String?): Int? =
        Regex("(\\d{4})").find(value.orEmpty())?.groupValues?.getOrNull(1)?.toIntOrNull()

    private data class ParsedRailIds(
        val imdb: String? = null,
        val tmdb: String? = null,
        val trakt: String? = null
    )
}
