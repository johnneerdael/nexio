package com.nexio.tv.data.repository

import com.nexio.tv.BuildConfig
import com.nexio.tv.core.metadata.router.resolver.Confidence
import com.nexio.tv.core.metadata.router.resolver.EpisodeRatingCandidate
import com.nexio.tv.core.metadata.router.resolver.RatingResolver
import com.nexio.tv.core.metadata.router.resolver.RatingResolution
import com.nexio.tv.core.metadata.router.resolver.SourceRole
import com.nexio.tv.core.tmdb.TmdbMetadataService
import com.nexio.tv.core.tmdb.TmdbService
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.Meta
import com.nexio.tv.ui.screens.detail.EpisodeRating
import com.nexio.tv.ui.screens.detail.EpisodeRatingSource
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

    suspend fun episodeRatingCandidates(
        meta: Meta,
        fallbackItemId: String,
        fallbackItemType: String,
        episodesBySeason: Map<Int, Set<Int>>
    ): List<EpisodeRatingCandidate> {
        if (episodesBySeason.isEmpty()) return emptyList()

        val candidates = mutableListOf<EpisodeRatingCandidate>()
        if (customImdbActiveProvider()) {
            customImdbEpisodeRatingsRepository.getEpisodeRatingsForMeta(
                meta = meta,
                fallbackItemId = fallbackItemId,
                fallbackItemType = fallbackItemType,
                episodesBySeason = episodesBySeason
            ).mapTo(candidates, SourceRole.CUSTOM_IMDB, "IMDB", Confidence.HIGH)
        }

        tmdbEpisodeRatings(meta, fallbackItemId, fallbackItemType, episodesBySeason)
            .mapTo(candidates, SourceRole.PRIMARY_PROVIDER, "TMDB", Confidence.MEDIUM)

        omdbEpisodeRatingsRepository.getEpisodeRatingsForMeta(
            meta = meta,
            fallbackItemId = fallbackItemId,
            fallbackItemType = fallbackItemType,
            episodesBySeason = episodesBySeason
        ).mapTo(candidates, SourceRole.OMDB, "OMDB", Confidence.HIGH)

        return candidates
    }

    suspend fun getEpisodeRatings(
        meta: Meta,
        fallbackItemId: String,
        fallbackItemType: String,
        episodesBySeason: Map<Int, Set<Int>>
    ): Map<Pair<Int, Int>, EpisodeRating> {
        return RatingResolver.resolveEpisodeRatings(
            episodeRatingCandidates(
                meta = meta,
                fallbackItemId = fallbackItemId,
                fallbackItemType = fallbackItemType,
                episodesBySeason = episodesBySeason
            )
        ).mapValues { (_, resolution) ->
            EpisodeRating(
                value = resolution.value,
                source = resolution.toEpisodeRatingSource()
            )
        }
    }

    private suspend fun tmdbEpisodeRatings(
        meta: Meta,
        fallbackItemId: String,
        fallbackItemType: String,
        episodesBySeason: Map<Int, Set<Int>>
    ): Map<Pair<Int, Int>, Double> {
        val seasonNumbers = episodesBySeason.keys.sorted()
        val tmdbLookupType = resolveTmdbContentType(meta, fallbackItemType).toApiString()
        val tmdbId = tmdbService.ensureTmdbId(meta.id, tmdbLookupType)
            ?: tmdbService.ensureTmdbId(fallbackItemId, fallbackItemType)

        return if (tmdbId != null) {
            tmdbMetadataService.fetchEpisodeEnrichment(
                tmdbId = tmdbId,
                seasonNumbers = seasonNumbers
            ).mapNotNull { (key, value) ->
                value.voteAverage?.takeIf { it > 0.0 }?.let { key to it }
            }.toMap()
        } else {
            emptyMap()
        }
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

    private fun Map<Pair<Int, Int>, Double>.mapTo(
        destination: MutableList<EpisodeRatingCandidate>,
        sourceRole: SourceRole,
        sourceProvider: String,
        confidence: Confidence
    ) {
        forEach { (key, rating) ->
            destination += EpisodeRatingCandidate(
                seasonNumber = key.first,
                episodeNumber = key.second,
                value = rating,
                sourceRole = sourceRole,
                sourceProvider = sourceProvider,
                confidence = confidence
            )
        }
    }

    private fun RatingResolution.toEpisodeRatingSource(): EpisodeRatingSource =
        when (sourceRole) {
            SourceRole.CUSTOM_IMDB -> EpisodeRatingSource.IMDB
            SourceRole.OMDB -> EpisodeRatingSource.OMDB
            SourceRole.MDBLIST, SourceRole.PRIMARY_PROVIDER, SourceRole.PREVIEW_FALLBACK -> sourceProvider.toEpisodeRatingSource()
        }

    private fun String.toEpisodeRatingSource(): EpisodeRatingSource =
        EpisodeRatingSource.entries.firstOrNull { it.name.equals(this, ignoreCase = true) }
            ?: EpisodeRatingSource.TMDB
}
