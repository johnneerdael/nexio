package com.nexio.tv.data.integration.metadata

import com.nexio.tv.core.integration.TmdbApiShapes
import com.nexio.tv.core.metadata.router.FieldOwner
import com.nexio.tv.core.metadata.router.FieldValue
import com.nexio.tv.core.metadata.router.MetadataCandidate
import com.nexio.tv.core.metadata.router.MetadataPrimaryProvider
import com.nexio.tv.core.metadata.router.MetadataProviderAdapter
import com.nexio.tv.core.metadata.router.MetadataRoute
import com.nexio.tv.core.metadata.router.ProviderPlanStep
import com.nexio.tv.core.metadata.router.ProviderStepResult
import com.nexio.tv.core.metadata.router.ResolvedField
import com.nexio.tv.core.metadata.router.ResolverType
import com.nexio.tv.data.integration.trailer.TrailerTmdbProvider
import com.nexio.tv.data.remote.api.TmdbVideoResult
import javax.inject.Inject
import javax.inject.Singleton

/**
 * TMDB trailer adapter. Delegates to [TrailerTmdbProvider] which carries cache + en-US fallback
 * semantics; emits a [ResolvedField.TRAILERS] candidate for the [TrailerResolver] to consume.
 *
 * The base TMDB adapter still claims `MOVIE_VIDEOS`/`TV_VIDEOS` from the legacy plan path —
 * this adapter is reached only after the orchestrator schedules trailer-aware dispatch
 * (follow-up tasks). Both adapters report `provider = TMDB`; the dispatcher must filter by
 * `supports(step)` keyed on the trailer adapter's narrower shape ownership.
 */
@Singleton
class TmdbTrailerMetadataAdapter @Inject constructor(
    private val trailerTmdbProvider: TrailerTmdbProvider
) : MetadataProviderAdapter {
    override val provider: MetadataPrimaryProvider = MetadataPrimaryProvider.TMDB

    override fun supports(step: ProviderPlanStep): Boolean = step.apiShapeId in TRAILER_SHAPES

    override suspend fun execute(route: MetadataRoute, step: ProviderPlanStep): ProviderStepResult {
        val tmdbId = MetadataProviderTargetIds.tmdbInt(route.targetIds[MetadataPrimaryProvider.TMDB])
            ?: return ProviderStepResult(step = step, candidate = emptyCandidate(this.provider))
        val apiKey = trailerTmdbProvider.getTmdbApiKey()
            ?: return ProviderStepResult(step = step, candidate = emptyCandidate(this.provider))
        val language = route.language?.takeIf { it.isNotBlank() } ?: TMDB_DEFAULT_LANGUAGE
        val videos: List<TmdbVideoResult> = when (step.apiShapeId) {
            TmdbApiShapes.MOVIE_VIDEOS -> trailerTmdbProvider.fetchMovieVideos(tmdbId, language, apiKey)
            TmdbApiShapes.TV_VIDEOS -> trailerTmdbProvider.fetchTvVideos(tmdbId, language, apiKey)
            TmdbApiShapes.SEASON_VIDEOS -> {
                val season = route.seasonNumber
                    ?: return ProviderStepResult(step = step, candidate = emptyCandidate(this.provider))
                trailerTmdbProvider.fetchSeasonVideos(tmdbId, season, language, apiKey)
            }
            else -> return ProviderStepResult(step = step, candidate = emptyCandidate(this.provider))
        }
        if (videos.isEmpty()) {
            return ProviderStepResult(step = step, candidate = emptyCandidate(this.provider))
        }
        return ProviderStepResult(
            step = step,
            candidate = MetadataCandidate(
                provider = this.provider,
                resolverType = ResolverType.TRAILERS,
                fields = mapOf(
                    ResolvedField.TRAILERS to FieldValue(videos, FieldOwner.TRAILERS)
                )
            )
        )
    }

    private companion object {
        const val TMDB_DEFAULT_LANGUAGE = "en-US"
        val TRAILER_SHAPES = setOf(
            TmdbApiShapes.MOVIE_VIDEOS,
            TmdbApiShapes.TV_VIDEOS,
            TmdbApiShapes.SEASON_VIDEOS
        )
    }
}
