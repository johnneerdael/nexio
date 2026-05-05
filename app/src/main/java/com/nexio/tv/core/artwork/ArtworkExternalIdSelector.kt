package com.nexio.tv.core.artwork

import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.core.metadata.router.MetadataMediaKind
import com.nexio.tv.domain.model.ProviderIds

data class ArtworkProviderExternalId(val idType: String, val mediaId: String)

data class EpisodeArtworkContext(val season: Int, val episode: Int) {
    val isValid: Boolean get() = season >= 1 && episode >= 1
    val episodePath: String get() = "S${season}E${episode}"
}

class ArtworkExternalIdSelector {
    fun selectIds(
        provider: IntegrationProvider,
        imageType: ArtworkType,
        mediaKind: MetadataMediaKind,
        providerIds: ProviderIds,
        episodeContext: EpisodeArtworkContext? = null
    ): List<ArtworkProviderExternalId> {
        if (imageType == ArtworkType.THUMBNAIL && episodeContext?.isValid != true) {
            return emptyList()
        }

        return when (provider) {
            IntegrationProvider.TOP_POSTERS -> selectTopPostersIds(
                imageType = imageType,
                mediaKind = mediaKind,
                providerIds = providerIds
            )
            IntegrationProvider.RPDB -> selectRpdbIds(
                imageType = imageType,
                mediaKind = mediaKind,
                providerIds = providerIds
            )
            else -> emptyList()
        }
    }

    private fun selectTopPostersIds(
        imageType: ArtworkType,
        mediaKind: MetadataMediaKind,
        providerIds: ProviderIds
    ): List<ArtworkProviderExternalId> {
        if (imageType != ArtworkType.POSTER && imageType != ArtworkType.THUMBNAIL) {
            return emptyList()
        }

        val animeIds = listOfNotNull(
            providerIds.kitsu.toId("kitsu", numeric = true),
            providerIds.mal.toId("mal", numeric = true),
            providerIds.anilist.toId("anilist", numeric = true),
            providerIds.anidb.toId("anidb", numeric = true)
        )
        if (animeIds.isNotEmpty()) {
            return animeIds + listOfNotNull(providerIds.imdb.toId("imdb", numeric = false))
        }

        return listOfNotNull(
            providerIds.tvdb.toId("tvdb", numeric = true),
            providerIds.tmdb.toTmdbId(mediaKind),
            providerIds.imdb.toId("imdb", numeric = false),
            providerIds.trakt.toId("trakt", numeric = true)
        )
    }

    private fun selectRpdbIds(
        imageType: ArtworkType,
        mediaKind: MetadataMediaKind,
        providerIds: ProviderIds
    ): List<ArtworkProviderExternalId> {
        if (imageType != ArtworkType.POSTER) return emptyList()

        return listOfNotNull(
            providerIds.imdb.toId("imdb", numeric = false),
            providerIds.tmdb.toTmdbId(mediaKind),
            providerIds.tvdb.toId("tvdb", numeric = true)?.let {
                ArtworkProviderExternalId("tvdb", "series-${it.mediaId}")
            }
        )
    }

    private fun String?.toTmdbId(mediaKind: MetadataMediaKind): ArtworkProviderExternalId? {
        val value = cleanedId("tmdb", numeric = false) ?: return null
        val mediaId = when {
            value.startsWith("movie-", ignoreCase = true) ||
                value.startsWith("series-", ignoreCase = true) -> value
            mediaKind == MetadataMediaKind.MOVIE -> "movie-$value"
            else -> "series-$value"
        }
        val numericPart = mediaId.substringAfter('-', missingDelimiterValue = "")
        return if (numericPart.matches(NUMERIC_ID_REGEX)) {
            ArtworkProviderExternalId("tmdb", mediaId)
        } else {
            null
        }
    }

    private fun String?.toId(idType: String, numeric: Boolean): ArtworkProviderExternalId? {
        val value = cleanedId(idType, numeric) ?: return null
        return if (idType == "imdb" && !value.matches(IMDB_ID_REGEX)) {
            null
        } else {
            ArtworkProviderExternalId(idType, value)
        }
    }

    private fun String?.cleanedId(idType: String, numeric: Boolean): String? {
        val raw = this?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val value = when {
            raw.startsWith("$idType:", ignoreCase = true) -> raw.substringAfter(':').trim()
            ':' in raw -> return null
            else -> raw
        }
        if (value.isBlank()) return null
        if (numeric && !value.matches(NUMERIC_ID_REGEX)) return null
        return value
    }

    private companion object {
        val IMDB_ID_REGEX = Regex("tt\\d+", RegexOption.IGNORE_CASE)
        val NUMERIC_ID_REGEX = Regex("\\d+")
    }
}
