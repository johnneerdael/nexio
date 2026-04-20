package com.nexio.tv.data.repository

import com.nexio.tv.BuildConfig
import com.nexio.tv.core.tmdb.TmdbMetadataService
import com.nexio.tv.core.tmdb.TmdbService
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.Meta
import com.nexio.tv.ui.screens.detail.EpisodeRating
import com.nexio.tv.ui.screens.detail.EpisodeRatingSource
import com.nexio.tv.ui.screens.detail.resolveEpisodeRatings
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EpisodeRatingsSelectionRepository @Inject constructor(
    private val customImdbEpisodeRatingsRepository: CustomImdbEpisodeRatingsRepository,
    private val tmdbService: TmdbService,
    private val tmdbMetadataService: TmdbMetadataService,
    private val omdbEpisodeRatingsRepository: OmdbEpisodeRatingsRepository
) {
    internal var customImdbActiveProvider: () -> Boolean = {
        BuildConfig.IMDB_API_URL.isNotBlank() && BuildConfig.IMDB_API_KEY.isNotBlank()
    }

    suspend fun getEpisodeRatings(
        meta: Meta,
        fallbackItemId: String,
        fallbackItemType: String,
        episodesBySeason: Map<Int, Set<Int>>
    ): Map<Pair<Int, Int>, EpisodeRating> {
        if (episodesBySeason.isEmpty()) return emptyMap()

        if (customImdbActiveProvider()) {
            return customImdbEpisodeRatingsRepository.getEpisodeRatingsForMeta(
                meta = meta,
                fallbackItemId = fallbackItemId,
                fallbackItemType = fallbackItemType,
                episodesBySeason = episodesBySeason
            ).mapValues { (_, rating) ->
                EpisodeRating(
                    value = rating,
                    source = EpisodeRatingSource.IMDB
                )
            }
        }

        val seasonNumbers = episodesBySeason.keys.sorted()
        val tmdbLookupType = resolveTmdbContentType(meta, fallbackItemType).toApiString()
        val tmdbId = tmdbService.ensureTmdbId(meta.id, tmdbLookupType)
            ?: tmdbService.ensureTmdbId(fallbackItemId, fallbackItemType)

        val tmdbRatings = if (tmdbId != null) {
            tmdbMetadataService.fetchEpisodeEnrichment(
                tmdbId = tmdbId,
                seasonNumbers = seasonNumbers
            ).mapNotNull { (key, value) ->
                value.voteAverage?.takeIf { it > 0.0 }?.let { key to it }
            }.toMap()
        } else {
            emptyMap()
        }

        val omdbRatings = omdbEpisodeRatingsRepository.getEpisodeRatingsForMeta(
            meta = meta,
            fallbackItemId = fallbackItemId,
            fallbackItemType = fallbackItemType,
            episodesBySeason = episodesBySeason
        )

        return resolveEpisodeRatings(tmdbRatings, omdbRatings)
    }

    private fun resolveTmdbContentType(meta: Meta, fallbackItemType: String): ContentType {
        parseApiTypeToContentType(fallbackItemType)?.let { return it }
        parseApiTypeToContentType(meta.apiType)?.let { return it }

        return when (meta.type) {
            ContentType.SERIES, ContentType.TV -> ContentType.SERIES
            ContentType.MOVIE -> ContentType.MOVIE
            else -> ContentType.MOVIE
        }
    }

    private fun parseApiTypeToContentType(apiType: String?): ContentType? {
        return when (apiType?.trim()?.lowercase().orEmpty()) {
            "movie", "film" -> ContentType.MOVIE
            "series", "tv", "show", "tvshow" -> ContentType.SERIES
            else -> null
        }
    }
}
