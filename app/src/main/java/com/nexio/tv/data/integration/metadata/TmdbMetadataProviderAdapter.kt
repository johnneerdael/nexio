package com.nexio.tv.data.integration.metadata

import com.nexio.tv.core.integration.TmdbApiShapes
import com.nexio.tv.core.metadata.router.MetadataCandidate
import com.nexio.tv.core.metadata.router.MetadataMediaKind
import com.nexio.tv.core.metadata.router.MetadataPrimaryProvider
import com.nexio.tv.core.metadata.router.MetadataProviderAdapter
import com.nexio.tv.core.metadata.router.MetadataRoute
import com.nexio.tv.core.metadata.router.ProviderPlanStep
import com.nexio.tv.core.metadata.router.ProviderStepResult
import com.nexio.tv.core.tvdb.TvEpisodeMetadata
import com.nexio.tv.data.remote.api.TmdbSeasonResponse
import com.nexio.tv.data.integration.tmdb.TmdbIntegrationProvider
import javax.inject.Inject

class TmdbMetadataProviderAdapter @Inject constructor(
    private val integrationProvider: TmdbIntegrationProvider
) : MetadataProviderAdapter {
    override val provider: MetadataPrimaryProvider = MetadataPrimaryProvider.TMDB

    override fun supports(step: ProviderPlanStep): Boolean = step.apiShapeId in tmdbShapes

    override suspend fun execute(route: MetadataRoute, step: ProviderPlanStep): ProviderStepResult {
        val tmdbId = MetadataProviderTargetIds.tmdbInt(route.targetIds[MetadataPrimaryProvider.TMDB])
            ?: return ProviderStepResult(step = step, candidate = emptyCandidate(this.provider))
        val language = route.language.orEmpty()
        val policy = LocalizationPolicy.tmdb(language)
        val seasonEpisodeMetadata = mutableMapOf<Pair<Int, Int>, TvEpisodeMetadata>()
        val candidate = when (step.apiShapeId) {
            TmdbApiShapes.MOVIE_CORE -> {
                val requested = integrationProvider.fetchMovieCore(
                    movieId = tmdbId,
                    normalizedLanguage = policy.requestedLanguage.providerCode,
                    activePosterProvider = null,
                    localizationPolicyVersion = policy.policyVersion
                )
                val english = if (policy.requestedIsFallback) {
                    null
                } else {
                    integrationProvider.fetchMovieCore(
                        movieId = tmdbId,
                        normalizedLanguage = policy.fallbackLanguage.providerCode,
                        activePosterProvider = null,
                        localizationPolicyVersion = policy.policyVersion
                    )
                }
                buildTmdbLocalizedCandidate(
                    provider = this.provider,
                    policy = policy,
                    requested = requested,
                    english = english
                )
            }
            TmdbApiShapes.TV_CORE ->
                buildTmdbLocalizedCandidate(
                    provider = this.provider,
                    policy = policy,
                    requested = integrationProvider.fetchTvCore(
                        tvId = tmdbId,
                        normalizedLanguage = policy.requestedLanguage.providerCode,
                        activePosterProvider = null,
                        localizationPolicyVersion = policy.policyVersion
                    ),
                    english = if (policy.requestedIsFallback) {
                        null
                    } else {
                        integrationProvider.fetchTvCore(
                            tvId = tmdbId,
                            normalizedLanguage = policy.fallbackLanguage.providerCode,
                            activePosterProvider = null,
                            localizationPolicyVersion = policy.policyVersion
                        )
                    },
                    sourceApiShapeId = TmdbApiShapes.TV_CORE
                )
            TmdbApiShapes.SEASON_EPISODES -> {
                val requested = integrationProvider.fetchTvSeasonEpisodes(
                    tvId = tmdbId,
                    seasonNumber = route.seasonNumber ?: 1,
                    normalizedLanguage = policy.requestedLanguage.providerCode,
                    localizationPolicyVersion = policy.policyVersion
                )
                val english = if (policy.requestedIsFallback) {
                    null
                } else {
                    integrationProvider.fetchTvSeasonEpisodes(
                        tvId = tmdbId,
                        seasonNumber = route.seasonNumber ?: 1,
                        normalizedLanguage = policy.fallbackLanguage.providerCode,
                        localizationPolicyVersion = policy.policyVersion
                    )
                }
                seasonEpisodeMetadata += requested.toEpisodeMetadata(english)
                emptyCandidate(this.provider)
            }
            TmdbApiShapes.MOVIE_VIDEOS -> {
                integrationProvider.fetchMovieVideos(tmdbId, language)
                emptyCandidate(this.provider)
            }
            TmdbApiShapes.TV_VIDEOS -> {
                integrationProvider.fetchTvVideos(tmdbId, language)
                emptyCandidate(this.provider)
            }
            TmdbApiShapes.MOVIE_REVIEWS -> {
                integrationProvider.fetchMovieReviews(tmdbId, language)
                emptyCandidate(this.provider)
            }
            TmdbApiShapes.TV_REVIEWS -> {
                integrationProvider.fetchTvReviews(tmdbId, language)
                emptyCandidate(this.provider)
            }
            TmdbApiShapes.MOVIE_RECOMMENDATIONS -> {
                integrationProvider.fetchMovieRecommendations(tmdbId, language)
                emptyCandidate(this.provider)
            }
            TmdbApiShapes.TV_RECOMMENDATIONS -> {
                integrationProvider.fetchTvRecommendations(tmdbId, language)
                emptyCandidate(this.provider)
            }
            else -> emptyCandidate(this.provider)
        }
        return ProviderStepResult(
            step = step,
            candidate = candidate.withCanonicalId(route),
            episodeMetadata = seasonEpisodeMetadata
        )
    }

    private fun MetadataCandidate.withCanonicalId(route: MetadataRoute): MetadataCandidate =
        if (fields.isNotEmpty() || route.mediaKind in setOf(MetadataMediaKind.MOVIE, MetadataMediaKind.SERIES)) this else this

    private fun TmdbSeasonResponse?.toEpisodeMetadata(
        english: TmdbSeasonResponse? = null
    ): Map<Pair<Int, Int>, TvEpisodeMetadata> {
        val season = this?.seasonNumber ?: english?.seasonNumber ?: return emptyMap()
        val primaryEpisodes = this?.episodes ?: english?.episodes ?: return emptyMap()
        val englishByNumber = english?.episodes.orEmpty().mapNotNull { episode ->
            episode.episodeNumber?.let { it to episode }
        }.toMap()
        return primaryEpisodes
            .mapNotNull { episode ->
                val number = episode.episodeNumber ?: return@mapNotNull null
                val englishEpisode = englishByNumber[number]
                (season to number) to TvEpisodeMetadata(
                    providerEpisodeId = episode.id?.let { "tmdb:$it" },
                    seasonNumber = season,
                    episodeNumber = number,
                    title = episode.name.cleanLocalizedValue() ?: englishEpisode?.name.cleanLocalizedValue(),
                    overview = episode.overview.cleanLocalizedValue() ?: englishEpisode?.overview.cleanLocalizedValue(),
                    thumbnail = episode.stillPath,
                    airDate = episode.airDate,
                    runtimeMinutes = episode.runtime
                )
            }
            .toMap()
    }

    private companion object {
        val tmdbShapes = setOf(
            TmdbApiShapes.MOVIE_CORE,
            TmdbApiShapes.TV_CORE,
            TmdbApiShapes.SEASON_EPISODES,
            TmdbApiShapes.MOVIE_VIDEOS,
            TmdbApiShapes.TV_VIDEOS,
            TmdbApiShapes.MOVIE_REVIEWS,
            TmdbApiShapes.TV_REVIEWS,
            TmdbApiShapes.MOVIE_RECOMMENDATIONS,
            TmdbApiShapes.TV_RECOMMENDATIONS
        )
    }
}
