package com.nexio.tv.data.integration.metadata

import com.nexio.tv.core.integration.TmdbApiShapes
import com.nexio.tv.core.metadata.router.MetadataCandidate
import com.nexio.tv.core.metadata.router.MetadataLocalizationFieldTrace
import com.nexio.tv.core.metadata.router.MetadataMediaKind
import com.nexio.tv.core.metadata.router.MetadataPrimaryProvider
import com.nexio.tv.core.metadata.router.MetadataProviderAdapter
import com.nexio.tv.core.metadata.router.MetadataRoute
import com.nexio.tv.core.metadata.router.ProviderPlanStep
import com.nexio.tv.core.metadata.router.ProviderStepResult
import com.nexio.tv.core.metadata.router.ResolvedField
import com.nexio.tv.core.trace.TraceMetadataEvents
import com.nexio.tv.core.tvdb.TvEpisodeMetadata
import com.nexio.tv.data.remote.api.TmdbEpisode
import com.nexio.tv.data.remote.api.TmdbSeasonResponse
import com.nexio.tv.data.integration.tmdb.TmdbIntegrationProvider
import javax.inject.Inject

class TmdbMetadataProviderAdapter @Inject constructor(
    private val integrationProvider: TmdbIntegrationProvider,
    private val traceEvents: TraceMetadataEvents
) : MetadataProviderAdapter {
    override val provider: MetadataPrimaryProvider = MetadataPrimaryProvider.TMDB

    override fun supports(step: ProviderPlanStep): Boolean = step.apiShapeId in tmdbShapes

    override suspend fun execute(route: MetadataRoute, step: ProviderPlanStep): ProviderStepResult {
        val tmdbId = MetadataProviderTargetIds.tmdbInt(route.targetIds[MetadataPrimaryProvider.TMDB])
            ?: return ProviderStepResult(step = step, candidate = emptyCandidate(this.provider))
        val language = route.language.orEmpty()
        val policy = LocalizationPolicy.tmdb(language)
        // F-E-02: emit localization_plan after policy construction.
        traceEvents.emitLocalizationPlan(
            contentId = "tmdb:$tmdbId",
            provider = "TMDB",
            policyVersion = policy.policyVersion,
            requestedLanguage = policy.requestedLanguage.providerCode,
            fallbackLanguage = policy.fallbackLanguage.providerCode,
            requestedIsFallback = policy.requestedIsFallback,
            allowProviderFallbackForMissingLocalizedFields = policy.allowProviderFallbackForMissingLocalizedFields,
            perEpisodeFallbacksAttempted = 0,
            perEpisodeFallbacksAllowed = policy.maxPerEpisodeTranslationFallbacksPerRequest
        )
        val seasonEpisodeMetadata = mutableMapOf<Pair<Int, Int>, TvEpisodeMetadata>()
        val seasonEpisodeLocalization = mutableMapOf<Pair<Int, Int>, Map<ResolvedField, MetadataLocalizationFieldTrace>>()
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
                val localizedEpisodes = requested.toEpisodeMetadata(policy = policy, english = english)
                seasonEpisodeMetadata += localizedEpisodes.metadata
                seasonEpisodeLocalization += localizedEpisodes.localization
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
            episodeMetadata = seasonEpisodeMetadata,
            episodeLocalization = seasonEpisodeLocalization
        )
    }

    private fun MetadataCandidate.withCanonicalId(route: MetadataRoute): MetadataCandidate =
        if (fields.isNotEmpty() || route.mediaKind in setOf(MetadataMediaKind.MOVIE, MetadataMediaKind.SERIES)) this else this

    private fun TmdbSeasonResponse?.toEpisodeMetadata(
        policy: LocalizationPolicy,
        english: TmdbSeasonResponse? = null
    ): TmdbEpisodeLocalizationResult {
        // F-E-03: TMDB does not have per-episode localization decisions like TVDB. Episode language
        // is determined by the request-level `language` parameter; episodes either come back in the
        // requested locale or fall back to TMDB's response default. There is no per-(episode, field)
        // winner to emit via field_selected. The series-level localization decision is captured by
        // emitLocalizationPlan (see Task 4).
        val season = this?.seasonNumber ?: english?.seasonNumber ?: return TmdbEpisodeLocalizationResult()
        val primaryEpisodes = this?.episodes ?: english?.episodes ?: return TmdbEpisodeLocalizationResult()
        val englishByNumber = english?.episodes.orEmpty().mapNotNull { episode ->
            episode.episodeNumber?.let { it to episode }
        }.toMap()
        val localization = mutableMapOf<Pair<Int, Int>, Map<ResolvedField, MetadataLocalizationFieldTrace>>()
        val metadata = primaryEpisodes
            .mapNotNull { episode ->
                val number = episode.episodeNumber ?: return@mapNotNull null
                val englishEpisode = englishByNumber[number]
                val title = LocalizationResolver.selectField(
                    field = ResolvedField.TITLE,
                    policy = policy,
                    candidates = listOf(
                        episode.localizedCandidate(ResolvedField.TITLE, episode.name, policy.requestedLanguage, FallbackRole.LOCALIZED),
                        episode.localizedCandidate(ResolvedField.TITLE, englishEpisode?.name, policy.fallbackLanguage, FallbackRole.LANGUAGE_FALLBACK)
                    )
                )
                val overview = LocalizationResolver.selectField(
                    field = ResolvedField.OVERVIEW,
                    policy = policy,
                    candidates = listOf(
                        episode.localizedCandidate(ResolvedField.OVERVIEW, episode.overview, policy.requestedLanguage, FallbackRole.LOCALIZED),
                        episode.localizedCandidate(ResolvedField.OVERVIEW, englishEpisode?.overview, policy.fallbackLanguage, FallbackRole.LANGUAGE_FALLBACK)
                    )
                )
                localization[season to number] = buildMap {
                    title?.let { put(ResolvedField.TITLE, it.toMetadataTrace()) }
                    overview?.let { put(ResolvedField.OVERVIEW, it.toMetadataTrace()) }
                }
                (season to number) to TvEpisodeMetadata(
                    providerEpisodeId = episode.id?.let { "tmdb:$it" },
                    seasonNumber = season,
                    episodeNumber = number,
                    title = title?.value,
                    overview = overview?.value,
                    thumbnail = episode.stillPath,
                    airDate = episode.airDate,
                    runtimeMinutes = episode.runtime
                )
            }
            .toMap()
        return TmdbEpisodeLocalizationResult(metadata = metadata, localization = localization)
    }

    private fun TmdbEpisode.localizedCandidate(
        field: ResolvedField,
        value: String?,
        language: NormalizedLanguage,
        fallbackRole: FallbackRole
    ): LocalizedFieldCandidate =
        LocalizedFieldCandidate(
            field = field,
            value = value,
            language = language,
            provider = this@TmdbMetadataProviderAdapter.provider,
            sourceShape = TmdbApiShapes.SEASON_EPISODES,
            fallbackRole = fallbackRole
        )

    private data class TmdbEpisodeLocalizationResult(
        val metadata: Map<Pair<Int, Int>, TvEpisodeMetadata> = emptyMap(),
        val localization: Map<Pair<Int, Int>, Map<ResolvedField, MetadataLocalizationFieldTrace>> = emptyMap()
    )

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
