package com.nexio.tv.core.tvdb

import android.util.Log
import com.nexio.tv.core.anime.AnimeStremioId
import com.nexio.tv.core.anime.ContentMediaKind
import com.nexio.tv.core.anime.KitsuMetadataService
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

private const val ROUTER_TAG = "TvMetadataRouter"

@Singleton
class TvMetadataRouter @Inject constructor(
    private val tvdbSettingsDataStore: TvdbSettingsDataStore,
    private val tmdbSettingsDataStore: TmdbSettingsDataStore,
    private val tvdbIdentityService: TvdbIdentityService,
    private val tvdbMetadataService: TvdbMetadataService,
    private val tmdbService: TmdbService,
    private val tmdbMetadataService: TmdbMetadataService,
    private val credentialHealth: TvdbCredentialHealth,
    private val diagnosticsRecorder: TvdbDiagnosticsRecorder,
    private val kitsuMetadataService: KitsuMetadataService? = null
) {
    suspend fun fetchEnrichment(
        request: TvMetadataRequest
    ): TvMetadataDecision<TvMetadataEnrichment> {
        tryFetchKitsuEnrichment(request)?.let { return it }

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

        // D-08: Check credential health before new TVDB network calls
        if (!credentialHealth.canCallTvdb()) {
            return handleInvalidCredentialEnrichment(request)
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
            // D-09: Record field-level fallback reasons
            val fieldLevelDiagnostics = fieldLevelFallbackDiagnostics(enrichment, request.contentId)
            // Record TVDB_PROVIDER_CHOSEN and field-level diagnostics
            recordReliabilityDiagnostic(
                TvdbReliabilityReason.TVDB_PROVIDER_CHOSEN,
                "tv_metadata_router",
                message = "TVDB enrichment served for ${request.contentId}"
            )
            recordReliabilityDiagnostic(
                TvdbReliabilityReason.TMDB_TV_FETCH_SKIPPED,
                "tv_metadata_router",
                message = "TVDB success, TMDB TV metadata not fetched for ${request.contentId}"
            )
            return TvMetadataDecision(
                provider = TvProvider.TVDB,
                reason = TvMetadataDecisionReason.TVDB_SUCCESS,
                value = enrichment,
                diagnostics = successDiagnostics(request.contentId) + advancedDiagnostics + fieldLevelDiagnostics
            )
        }

        // D-07: TVDB record missing or fetch failed -> explicit TMDB fallback
        recordReliabilityDiagnostic(
            TvdbReliabilityReason.EXPLICIT_FALLBACK,
            "tv_metadata_router",
            message = "TVDB record unavailable, explicit TMDB fallback for ${request.contentId}"
        )
        return fetchTmdbEnrichment(
            request = request,
            diagnostics = recordMissingDiagnostics(request.contentId),
            reason = TvMetadataDecisionReason.TVDB_RECORD_MISSING
        )
    }

    suspend fun fetchEpisodeEnrichment(
        request: TvMetadataRequest
    ): TvMetadataDecision<Map<Pair<Int, Int>, TvEpisodeMetadata>> {
        tryFetchKitsuEpisodeEnrichment(request)?.let { return it }

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

        // D-08: Credential health gate
        if (!credentialHealth.canCallTvdb()) {
            return handleInvalidCredentialEpisodeEnrichment(request)
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

        // D-08: Credential health gate
        if (!credentialHealth.canCallTvdb()) {
            // TvdbMetadataService handles stale-cache serving with credential gating
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

    private suspend fun tryFetchKitsuEnrichment(
        request: TvMetadataRequest
    ): TvMetadataDecision<TvMetadataEnrichment>? {
        val animeId = firstAnimeId(request) ?: return null
        val mediaKind = request.contentType.toAnimeMediaKind()
        val enrichment = kitsuMetadataService?.fetchEnrichment(animeId, mediaKind) ?: return null
        return TvMetadataDecision(
            provider = TvProvider.KITSU,
            reason = TvMetadataDecisionReason.KITSU_SUCCESS,
            value = enrichment,
            diagnostics = listOf(
                diagnostic(TvMetadataDecisionReason.KITSU_SUCCESS, animeId, provider = TvProvider.KITSU)
            )
        )
    }

    private suspend fun tryFetchKitsuEpisodeEnrichment(
        request: TvMetadataRequest
    ): TvMetadataDecision<Map<Pair<Int, Int>, TvEpisodeMetadata>>? {
        val animeId = firstAnimeId(request) ?: return null
        val mediaKind = request.contentType.toAnimeMediaKind()
        val episodes = kitsuMetadataService?.fetchEpisodeEnrichment(animeId, mediaKind, request.seasonNumbers).orEmpty()
        if (episodes.isEmpty()) return null
        return TvMetadataDecision(
            provider = TvProvider.KITSU,
            reason = TvMetadataDecisionReason.KITSU_SUCCESS,
            value = episodes,
            diagnostics = listOf(
                diagnostic(TvMetadataDecisionReason.KITSU_SUCCESS, animeId, provider = TvProvider.KITSU)
            )
        )
    }

    private fun firstAnimeId(request: TvMetadataRequest): String? {
        val candidates = listOf(request.contentId, request.fallbackContentId)
        return candidates.firstOrNull { AnimeStremioId.parse(it) != null }
    }

    private fun ContentType.toAnimeMediaKind(): ContentMediaKind =
        if (this == ContentType.MOVIE) ContentMediaKind.MOVIE else ContentMediaKind.SERIES

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
        fallbackProvider: TvProvider? = null,
        detail: String? = null
    ): TvMetadataDiagnosticEvent {
        return TvMetadataDiagnosticEvent(
            reason = reason,
            contentId = contentId,
            provider = provider,
            fallbackProvider = fallbackProvider,
            detail = detail
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
            ratingSource = ratingSource,
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

    /**
     * D-08: Handle enrichment request when credentials are invalid.
     * Serve cached TVDB data with STALE_CACHE_SERVED + INVALID_CREDENTIALS,
     * or fall back to explicit TMDB fallback.
     */
    private suspend fun handleInvalidCredentialEnrichment(
        request: TvMetadataRequest
    ): TvMetadataDecision<TvMetadataEnrichment> {
        recordReliabilityDiagnostic(
            TvdbReliabilityReason.INVALID_CREDENTIALS,
            "tv_metadata_router",
            message = "Credentials invalid for ${request.contentId}"
        )

        // Try to get cached TVDB data via the service (which handles stale cache)
        val identity = resolveTvdbIdentity(request.contentId, request.contentType)
        if (identity != null) {
            val cached = tvdbMetadataService.fetchSeriesEnrichment(identity, request.language)
            if (cached != null) {
                recordReliabilityDiagnostic(
                    TvdbReliabilityReason.STALE_CACHE_SERVED,
                    "tv_metadata_router",
                    message = "Serving cached TVDB data while credentials invalid for ${request.contentId}"
                )
                return TvMetadataDecision(
                    provider = TvProvider.TVDB,
                    reason = TvMetadataDecisionReason.TVDB_SUCCESS,
                    value = cached,
                    diagnostics = listOf(
                        diagnostic(TvMetadataDecisionReason.TVDB_SUCCESS, request.contentId, provider = TvProvider.TVDB)
                    )
                )
            }
        }

        // No cached data available, explicit TMDB fallback
        recordReliabilityDiagnostic(
            TvdbReliabilityReason.EXPLICIT_FALLBACK,
            "tv_metadata_router",
            message = "No cached TVDB data, explicit TMDB fallback for ${request.contentId}"
        )
        return fetchTmdbEnrichment(
            request = request,
            diagnostics = listOf(
                diagnostic(TvMetadataDecisionReason.TVDB_RECORD_MISSING, request.contentId, fallbackProvider = TvProvider.TMDB),
                diagnostic(TvMetadataDecisionReason.TVDB_FALLBACK_TMDB, request.contentId, fallbackProvider = TvProvider.TMDB)
            ),
            reason = TvMetadataDecisionReason.TVDB_RECORD_MISSING
        )
    }

    /**
     * D-08: Handle episode enrichment request when credentials are invalid.
     */
    private suspend fun handleInvalidCredentialEpisodeEnrichment(
        request: TvMetadataRequest
    ): TvMetadataDecision<Map<Pair<Int, Int>, TvEpisodeMetadata>> {
        recordReliabilityDiagnostic(
            TvdbReliabilityReason.INVALID_CREDENTIALS,
            "tv_metadata_router",
            message = "Credentials invalid for episode enrichment ${request.contentId}"
        )

        // Try cached episode data
        val identity = resolveTvdbIdentity(request.contentId, request.contentType)
        if (identity != null) {
            val episodes = tvdbMetadataService.fetchEpisodeEnrichment(identity, request.seasonNumbers, request.language)
            if (episodes.isNotEmpty()) {
                return TvMetadataDecision(
                    provider = TvProvider.TVDB,
                    reason = TvMetadataDecisionReason.TVDB_SUCCESS,
                    value = episodes,
                    diagnostics = successDiagnostics(request.contentId)
                )
            }
        }

        return fetchTmdbEpisodeEnrichment(
            request = request,
            diagnostics = recordMissingDiagnostics(request.contentId),
            reason = TvMetadataDecisionReason.TVDB_RECORD_MISSING
        )
    }

    /**
     * D-09: Generate field-level fallback diagnostics for TVDB enrichment.
     * Records MISSING_AIRS_TIME, DATE_ONLY_GATING, POSTER_RATINGS_OVERRIDE
     * while keeping TVDB as the record provider.
     */
    private suspend fun fieldLevelFallbackDiagnostics(
        enrichment: TvMetadataEnrichment,
        contentId: String
    ): List<TvMetadataDiagnosticEvent> {
        val diagnostics = mutableListOf<TvMetadataDiagnosticEvent>()

        // D-09: Missing airsTime field-level diagnostic
        if (enrichment.airsTime == null) {
            if (enrichment.airsDays.isNotEmpty()) {
                // Has airsDays but no airsTime -> date-only gating
                recordReliabilityDiagnostic(
                    TvdbReliabilityReason.DATE_ONLY_GATING,
                    "tv_metadata_router",
                    tvdbId = enrichment.seriesTvdbId,
                    message = "TVDB has airsDays but no airsTime for $contentId"
                )
                diagnostics.add(
                    diagnostic(TvMetadataDecisionReason.TVDB_SUCCESS, contentId, provider = TvProvider.TVDB,
                        detail = "date_only_gating")
                )
            }
            // Always record missing_airs_time when airsTime is null
            recordReliabilityDiagnostic(
                TvdbReliabilityReason.MISSING_AIRS_TIME,
                "tv_metadata_router",
                tvdbId = enrichment.seriesTvdbId,
                message = "TVDB missing airsTime for $contentId"
            )
        }

        return diagnostics
    }

    /**
     * Records a reliability diagnostic and emits structured log fields.
     * Does not include Authorization headers, API keys, PINs, bearer tokens,
     * raw response bodies, or full request URLs.
     */
    private suspend fun recordReliabilityDiagnostic(
        reason: TvdbReliabilityReason,
        surface: String,
        tvdbId: Int? = null,
        message: String? = null
    ) {
        val diagnostic = TvdbReliabilityDiagnostic(
            reason = reason,
            surface = surface,
            tvdbId = tvdbId,
            message = message
        )
        runCatching { diagnosticsRecorder.record(diagnostic) }
        val fields = diagnostic.structuredLogFields()
        Log.d(ROUTER_TAG, fields.entries.joinToString(", ") { "${it.key}=${it.value}" })
    }

    private fun ContentType.isTv(): Boolean = this == ContentType.SERIES || this == ContentType.TV
}
