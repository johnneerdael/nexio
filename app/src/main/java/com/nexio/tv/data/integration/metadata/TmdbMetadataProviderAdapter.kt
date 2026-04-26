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
import com.nexio.tv.domain.model.ContentType
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
                integrationProvider.fetchEnrichment(tmdbId.toString(), ContentType.TV, language, activePosterProvider = null)
                    .toMetadataCandidate(this.provider)
            TmdbApiShapes.SEASON_EPISODES -> {
                seasonEpisodeMetadata += integrationProvider
                    .fetchTvSeasonEpisodes(tmdbId, route.seasonNumber ?: 1, language)
                    .toEpisodeMetadata()
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

    private fun TmdbSeasonResponse?.toEpisodeMetadata(): Map<Pair<Int, Int>, TvEpisodeMetadata> {
        val season = this?.seasonNumber ?: return emptyMap()
        return episodes
            .orEmpty()
            .mapNotNull { episode ->
                val number = episode.episodeNumber ?: return@mapNotNull null
                (season to number) to TvEpisodeMetadata(
                    providerEpisodeId = episode.id?.let { "tmdb:$it" },
                    seasonNumber = season,
                    episodeNumber = number,
                    title = episode.name,
                    overview = episode.overview,
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
