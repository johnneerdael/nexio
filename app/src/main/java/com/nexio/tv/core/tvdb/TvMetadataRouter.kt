package com.nexio.tv.core.tvdb

import com.nexio.tv.core.tmdb.TmdbEnrichment
import com.nexio.tv.core.tmdb.TmdbEpisodeEnrichment
import com.nexio.tv.core.tmdb.TmdbMetadataService
import com.nexio.tv.core.tmdb.TmdbService
import com.nexio.tv.data.local.TmdbSettingsDataStore
import com.nexio.tv.data.local.TvdbSettingsDataStore
import com.nexio.tv.data.remote.api.TmdbEpisode
import com.nexio.tv.domain.model.ContentType
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

@Singleton
class TvMetadataRouter @Inject constructor(
    private val tvdbSettingsDataStore: TvdbSettingsDataStore,
    private val tmdbSettingsDataStore: TmdbSettingsDataStore,
    private val tvdbIdentityService: TvdbIdentityService,
    private val tvdbMetadataService: TvdbMetadataService,
    private val tmdbService: TmdbService,
    private val tmdbMetadataService: TmdbMetadataService
) {
    suspend fun fetchEnrichment(
        request: TvMetadataRequest
    ): TvMetadataDecision<TvMetadataEnrichment> {
        if (!request.contentType.isTv()) {
            return fetchTmdbEnrichment(request, diagnostics = emptyList(), reason = TvMetadataDecisionReason.TVDB_INACTIVE)
        }

        if (!tvdbSettingsDataStore.settings.first().isActive) {
            return fetchTmdbEnrichment(
                request = request,
                diagnostics = inactiveDiagnostics(request.contentId),
                reason = TvMetadataDecisionReason.TVDB_INACTIVE
            )
        }

        val identity = resolveTvdbIdentity(request.contentId, request.contentType)
        if (identity == null) {
            return fetchTmdbEnrichment(
                request = request,
                diagnostics = missingIdentityDiagnostics(request.contentId),
                reason = TvMetadataDecisionReason.TVDB_IDENTITY_MISSING
            )
        }

        val enrichment = tvdbMetadataService.fetchSeriesEnrichment(identity, request.language)
        if (enrichment != null) {
            val advancedDiagnostics = advancedSurfaceDiagnostics(enrichment, request.contentId)
            return TvMetadataDecision(
                provider = TvProvider.TVDB,
                reason = TvMetadataDecisionReason.TVDB_SUCCESS,
                value = enrichment,
                diagnostics = successDiagnostics(request.contentId) + advancedDiagnostics
            )
        }

        return fetchTmdbEnrichment(
            request = request,
            diagnostics = recordMissingDiagnostics(request.contentId),
            reason = TvMetadataDecisionReason.TVDB_RECORD_MISSING
        )
    }

    suspend fun fetchEpisodeEnrichment(
        request: TvMetadataRequest
    ): TvMetadataDecision<Map<Pair<Int, Int>, TvEpisodeMetadata>> {
        if (!request.contentType.isTv()) {
            return fetchTmdbEpisodeEnrichment(request, diagnostics = emptyList(), reason = TvMetadataDecisionReason.TVDB_INACTIVE)
        }

        if (!tvdbSettingsDataStore.settings.first().isActive) {
            return fetchTmdbEpisodeEnrichment(
                request = request,
                diagnostics = inactiveDiagnostics(request.contentId),
                reason = TvMetadataDecisionReason.TVDB_INACTIVE
            )
        }

        val identity = resolveTvdbIdentity(request.contentId, request.contentType)
        if (identity == null) {
            return fetchTmdbEpisodeEnrichment(
                request = request,
                diagnostics = missingIdentityDiagnostics(request.contentId),
                reason = TvMetadataDecisionReason.TVDB_IDENTITY_MISSING
            )
        }

        val episodes = tvdbMetadataService.fetchEpisodeEnrichment(identity, request.seasonNumbers, request.language)
        if (episodes.isNotEmpty()) {
            return TvMetadataDecision(
                provider = TvProvider.TVDB,
                reason = TvMetadataDecisionReason.TVDB_SUCCESS,
                value = episodes,
                diagnostics = successDiagnostics(request.contentId)
            )
        }

        return fetchTmdbEpisodeEnrichment(
            request = request,
            diagnostics = recordMissingDiagnostics(request.contentId),
            reason = TvMetadataDecisionReason.TVDB_RECORD_MISSING
        )
    }

    suspend fun fetchSeasonEpisodes(
        contentId: String,
        fallbackContentId: String?,
        seasonNumber: Int,
        language: String? = null
    ): TvMetadataDecision<List<TvSeasonEpisode>> {
        val request = TvMetadataRequest(
            contentId = contentId,
            fallbackContentId = fallbackContentId,
            contentType = ContentType.SERIES,
            language = language
        )

        if (!tvdbSettingsDataStore.settings.first().isActive) {
            return fetchTmdbSeasonEpisodes(
                request = request,
                seasonNumber = seasonNumber,
                diagnostics = inactiveDiagnostics(contentId),
                reason = TvMetadataDecisionReason.TVDB_INACTIVE
            )
        }

        val identity = resolveTvdbIdentity(contentId, ContentType.SERIES)
        if (identity == null) {
            return fetchTmdbSeasonEpisodes(
                request = request,
                seasonNumber = seasonNumber,
                diagnostics = missingIdentityDiagnostics(contentId),
                reason = TvMetadataDecisionReason.TVDB_IDENTITY_MISSING
            )
        }

        val episodes = tvdbMetadataService.fetchSeasonEpisodes(identity, seasonNumber, language)
        if (episodes.isNotEmpty()) {
            return TvMetadataDecision(
                provider = TvProvider.TVDB,
                reason = TvMetadataDecisionReason.TVDB_SUCCESS,
                value = episodes,
                diagnostics = successDiagnostics(contentId)
            )
        }

        return fetchTmdbSeasonEpisodes(
            request = request,
            seasonNumber = seasonNumber,
            diagnostics = recordMissingDiagnostics(contentId),
            reason = TvMetadataDecisionReason.TVDB_RECORD_MISSING
        )
    }

    private suspend fun fetchTmdbEnrichment(
        request: TvMetadataRequest,
        diagnostics: List<TvMetadataDiagnosticEvent>,
        reason: TvMetadataDecisionReason
    ): TvMetadataDecision<TvMetadataEnrichment> {
        if (!canUseTmdbFallback()) {
            return TvMetadataDecision(
                provider = TvProvider.TMDB,
                reason = reason,
                value = null,
                diagnostics = diagnostics
            )
        }

        val tmdbId = resolveTmdbId(request) ?: return TvMetadataDecision(
            provider = TvProvider.TMDB,
            reason = reason,
            value = null,
            diagnostics = diagnostics
        )
        val tmdb = tmdbMetadataService.fetchEnrichment(tmdbId, request.contentType, request.language)
        return TvMetadataDecision(
            provider = TvProvider.TMDB,
            reason = reason,
            value = tmdb?.toTvMetadataEnrichment(),
            diagnostics = diagnostics
        )
    }

    private suspend fun fetchTmdbEpisodeEnrichment(
        request: TvMetadataRequest,
        diagnostics: List<TvMetadataDiagnosticEvent>,
        reason: TvMetadataDecisionReason
    ): TvMetadataDecision<Map<Pair<Int, Int>, TvEpisodeMetadata>> {
        if (!canUseTmdbFallback()) {
            return TvMetadataDecision(
                provider = TvProvider.TMDB,
                reason = reason,
                value = emptyMap(),
                diagnostics = diagnostics
            )
        }

        val tmdbId = resolveTmdbId(request) ?: return TvMetadataDecision(
            provider = TvProvider.TMDB,
            reason = reason,
            value = emptyMap(),
            diagnostics = diagnostics
        )
        val tmdb = tmdbMetadataService.fetchEpisodeEnrichment(tmdbId, request.seasonNumbers, request.language)
        return TvMetadataDecision(
            provider = TvProvider.TMDB,
            reason = reason,
            value = tmdb.mapValues { (_, episode) -> episode.toTvEpisodeMetadata() },
            diagnostics = diagnostics
        )
    }

    private suspend fun fetchTmdbSeasonEpisodes(
        request: TvMetadataRequest,
        seasonNumber: Int,
        diagnostics: List<TvMetadataDiagnosticEvent>,
        reason: TvMetadataDecisionReason
    ): TvMetadataDecision<List<TvSeasonEpisode>> {
        if (!canUseTmdbFallback()) {
            return TvMetadataDecision(
                provider = TvProvider.TMDB,
                reason = reason,
                value = emptyList(),
                diagnostics = diagnostics
            )
        }

        val tmdbId = resolveTmdbId(request)?.toIntOrNull() ?: return TvMetadataDecision(
            provider = TvProvider.TMDB,
            reason = reason,
            value = emptyList(),
            diagnostics = diagnostics
        )
        val tmdb = tmdbMetadataService.fetchSeasonEpisodes(tmdbId, seasonNumber, request.language)
        return TvMetadataDecision(
            provider = TvProvider.TMDB,
            reason = reason,
            value = tmdb.map { episode -> episode.toTvSeasonEpisode(seasonNumber) },
            diagnostics = diagnostics
        )
    }

    private suspend fun canUseTmdbFallback(): Boolean {
        return tmdbSettingsDataStore.settings.first().isActive
    }

    private suspend fun resolveTmdbId(request: TvMetadataRequest): String? {
        val candidate = request.fallbackContentId?.takeIf { it.isNotBlank() } ?: request.contentId
        return tmdbService.ensureTmdbId(candidate, request.contentType.toApiString())
    }

    private suspend fun resolveTvdbIdentity(
        contentId: String,
        contentType: ContentType
    ): TvdbSeriesIdentity? {
        if (!contentType.isTv()) return null
        val trimmed = contentId.trim()
        if (trimmed.isBlank()) return null
        val normalized = trimmed.lowercase()

        return when {
            normalized.startsWith("tvdb:") -> {
                trimmed.substringAfter(':').toIntOrNull()?.let { tvdbIdentityService.resolveSeriesByTvdbId(it) }
            }
            normalized.startsWith("imdb:") -> {
                tvdbIdentityService.resolveSeriesByRemoteId(trimmed.substringAfter(':'), TvdbRemoteIdSource.IMDB)
            }
            trimmed.startsWith("tt", ignoreCase = true) -> {
                tvdbIdentityService.resolveSeriesByRemoteId(trimmed.substringBefore(':').substringBefore('/'), TvdbRemoteIdSource.IMDB)
            }
            normalized.startsWith("tmdb:") -> {
                val value = trimmed.substringAfter(':').removePrefix("series-").removePrefix("tv-").trim()
                tvdbIdentityService.resolveSeriesByRemoteId(value, TvdbRemoteIdSource.TMDB)
            }
            else -> tvdbIdentityService.resolveSeriesByRemoteId(trimmed, TvdbRemoteIdSource.OTHER)
        }
    }

    private fun inactiveDiagnostics(contentId: String): List<TvMetadataDiagnosticEvent> {
        return listOf(
            diagnostic(TvMetadataDecisionReason.TVDB_INACTIVE, contentId, fallbackProvider = TvProvider.TMDB),
            diagnostic(TvMetadataDecisionReason.TVDB_FALLBACK_TMDB, contentId, fallbackProvider = TvProvider.TMDB)
        )
    }

    private fun missingIdentityDiagnostics(contentId: String): List<TvMetadataDiagnosticEvent> {
        return listOf(
            diagnostic(TvMetadataDecisionReason.TVDB_IDENTITY_MISSING, contentId, fallbackProvider = TvProvider.TMDB),
            diagnostic(TvMetadataDecisionReason.TVDB_FALLBACK_TMDB, contentId, fallbackProvider = TvProvider.TMDB)
        )
    }

    private fun recordMissingDiagnostics(contentId: String): List<TvMetadataDiagnosticEvent> {
        return listOf(
            diagnostic(TvMetadataDecisionReason.TVDB_RECORD_MISSING, contentId, fallbackProvider = TvProvider.TMDB),
            diagnostic(TvMetadataDecisionReason.TVDB_FALLBACK_TMDB, contentId, fallbackProvider = TvProvider.TMDB)
        )
    }

    private fun advancedSurfaceDiagnostics(
        enrichment: TvMetadataEnrichment,
        contentId: String
    ): List<TvMetadataDiagnosticEvent> {
        val hasAdvanced = enrichment.castMembers.isNotEmpty() ||
            enrichment.productionCompanies.isNotEmpty() ||
            enrichment.networks.isNotEmpty() ||
            enrichment.genres.isNotEmpty() ||
            enrichment.ageRating != null
        val reason = if (hasAdvanced) {
            TvMetadataDecisionReason.TVDB_ADVANCED_SURFACE_SUCCESS
        } else {
            TvMetadataDecisionReason.TVDB_ADVANCED_SURFACE_MISSING
        }
        return listOf(diagnostic(reason, contentId, provider = TvProvider.TVDB))
    }

    private fun successDiagnostics(contentId: String): List<TvMetadataDiagnosticEvent> {
        return listOf(
            diagnostic(TvMetadataDecisionReason.TVDB_SUCCESS, contentId, provider = TvProvider.TVDB),
            diagnostic(
                TvMetadataDecisionReason.TMDB_TV_SKIPPED,
                contentId,
                provider = TvProvider.TVDB,
                fallbackProvider = TvProvider.TMDB
            )
        )
    }

    private fun diagnostic(
        reason: TvMetadataDecisionReason,
        contentId: String,
        provider: TvProvider? = null,
        fallbackProvider: TvProvider? = null
    ): TvMetadataDiagnosticEvent {
        return TvMetadataDiagnosticEvent(
            reason = reason,
            contentId = contentId,
            provider = provider,
            fallbackProvider = fallbackProvider
        )
    }

    private fun TmdbEnrichment.toTvMetadataEnrichment(): TvMetadataEnrichment {
        return TvMetadataEnrichment(
            seriesTvdbId = null,
            localizedTitle = localizedTitle,
            description = description,
            genres = genres,
            backdrop = backdrop,
            logo = logo,
            poster = poster,
            releaseInfo = releaseInfo,
            rating = rating,
            runtimeMinutes = runtimeMinutes,
            ageRating = ageRating,
            countries = countries,
            language = language
        )
    }

    private fun TmdbEpisodeEnrichment.toTvEpisodeMetadata(): TvEpisodeMetadata {
        return TvEpisodeMetadata(
            providerEpisodeId = tmdbEpisodeId?.let { "tmdb:$it" },
            title = title,
            overview = overview,
            thumbnail = thumbnail,
            airDate = airDate,
            runtimeMinutes = runtimeMinutes
        )
    }

    private fun TmdbEpisode.toTvSeasonEpisode(seasonNumber: Int): TvSeasonEpisode {
        val metadata = TvEpisodeMetadata(
            providerEpisodeId = id?.let { "tmdb:$it" },
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber,
            title = name?.trim()?.takeIf { it.isNotBlank() },
            overview = overview?.trim()?.takeIf { it.isNotBlank() },
            thumbnail = stillPath?.trim()?.takeIf { it.isNotBlank() }?.let { "https://image.tmdb.org/t/p/w500$it" },
            airDate = airDate?.trim()?.takeIf { it.isNotBlank() },
            runtimeMinutes = runtime
        )
        return TvSeasonEpisode(
            episodeNumber = episodeNumber,
            airDate = metadata.airDate,
            metadata = metadata
        )
    }

    private fun ContentType.isTv(): Boolean = this == ContentType.SERIES || this == ContentType.TV
}
