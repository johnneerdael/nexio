package com.nexio.tv.data.integration.metadata

import com.nexio.tv.core.integration.TmdbApiShapes
import com.nexio.tv.core.metadata.router.MetadataCandidate
import com.nexio.tv.core.metadata.router.MetadataMediaKind
import com.nexio.tv.core.metadata.router.MetadataPrimaryProvider
import com.nexio.tv.core.metadata.router.MetadataProviderAdapter
import com.nexio.tv.core.metadata.router.MetadataRoute
import com.nexio.tv.core.metadata.router.ProviderPlanStep
import com.nexio.tv.core.metadata.router.ProviderStepResult
import com.nexio.tv.data.integration.tmdb.TmdbIntegrationProvider
import com.nexio.tv.domain.model.ContentType
import javax.inject.Inject

class TmdbMetadataProviderAdapter @Inject constructor(
    private val integrationProvider: TmdbIntegrationProvider
) : MetadataProviderAdapter {
    override val provider: MetadataPrimaryProvider = MetadataPrimaryProvider.TMDB

    override fun supports(step: ProviderPlanStep): Boolean = step.apiShapeId in tmdbShapes

    override suspend fun execute(route: MetadataRoute, step: ProviderPlanStep): ProviderStepResult {
        val tmdbId = route.targetIds[MetadataPrimaryProvider.TMDB]?.toIntOrNull()
            ?: return ProviderStepResult(step = step, candidate = emptyCandidate(this.provider))
        val language = route.language.orEmpty()
        val candidate = when (step.apiShapeId) {
            TmdbApiShapes.MOVIE_CORE ->
                integrationProvider.fetchMovieCore(tmdbId, language, activePosterProvider = null).toMetadataCandidate(this.provider)
            TmdbApiShapes.TV_CORE ->
                integrationProvider.fetchEnrichment(tmdbId.toString(), ContentType.TV, language, activePosterProvider = null)
                    .toMetadataCandidate(this.provider)
            TmdbApiShapes.SEASON_EPISODES -> {
                integrationProvider.fetchTvSeasonEpisodes(tmdbId, route.seasonNumber ?: 1, language)
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
        return ProviderStepResult(step = step, candidate = candidate.withCanonicalId(route))
    }

    private fun MetadataCandidate.withCanonicalId(route: MetadataRoute): MetadataCandidate =
        if (fields.isNotEmpty() || route.mediaKind in setOf(MetadataMediaKind.MOVIE, MetadataMediaKind.SERIES)) this else this

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
