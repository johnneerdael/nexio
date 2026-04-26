package com.nexio.tv.core.tvdb

import android.util.Log
import com.nexio.tv.core.integration.IntegrationLoadResult
import com.nexio.tv.core.poster.PosterRatingsUrlResolver
import com.nexio.tv.data.integration.tvdb.TvdbIntegrationProvider
import com.nexio.tv.data.local.MetadataDiskCacheStore
import com.nexio.tv.data.local.TvdbMergeAliasStore
import com.nexio.tv.data.remote.api.TvdbAirsDays
import com.nexio.tv.data.remote.api.TvdbArtworkRecord
import com.nexio.tv.data.remote.api.TvdbEpisodeRecord
import com.nexio.tv.data.remote.api.TvdbRemoteId
import com.nexio.tv.data.remote.api.TvdbSeriesExtendedRecord
import com.nexio.tv.data.remote.api.TvdbTranslationRecord
import com.nexio.tv.domain.model.ContentType
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

private const val TAG = "TvdbMetadataService"
private const val SERIES_EXTENDED_RECORD_KIND = "series_extended"
private const val DEFAULT_SEASON_TYPE = "default"

@Singleton
class TvdbMetadataService @Inject constructor(
    private val provider: TvdbIntegrationProvider,
    private val posterRatingsUrlResolver: PosterRatingsUrlResolver,
    private val metadataDiskCacheStore: MetadataDiskCacheStore,
    private val seasonOrderMapper: TvdbSeasonOrderMapper,
    private val advancedMetadataMapper: TvdbAdvancedMetadataMapper,
    private val mergeAliasStore: TvdbMergeAliasStore,
    private val credentialHealth: TvdbCredentialHealth,
    private val diagnosticsRecorder: TvdbDiagnosticsRecorder
) {
    suspend fun fetchSeriesEnrichment(
        identity: TvdbSeriesIdentity,
        language: String? = null
    ): TvMetadataEnrichment? = withContext(Dispatchers.IO) {
        val normalizedLanguage = normalizeLanguage(language)
        val activeProvider = posterRatingsUrlResolver.getActiveProvider()
        val providerToken = posterProviderCacheToken(activeProvider)
        val resolvedId = resolveSeriesAlias(identity.tvdbId)
        val load: suspend () -> IntegrationLoadResult<TvMetadataEnrichment> = {
                fetchSeriesEnrichmentForRuntimeLoad(
                    identity = identity.copy(tvdbId = resolvedId),
                    language = language
                )
        }
        provider.readCachedSeriesEnrichment(
            identity = identity,
            resolvedId = resolvedId,
            normalizedLanguage = normalizedLanguage,
            providerToken = providerToken,
            load = load
        )
            ?: readSeriesLegacyDiskCache(
                resolvedId = resolvedId,
                originalId = identity.tvdbId,
                normalizedLanguage = normalizedLanguage,
                providerToken = providerToken,
                diagnosticMessage = null
            )
            ?: provider.fetchSeriesEnrichment(
                identity = identity,
                resolvedId = resolvedId,
                normalizedLanguage = normalizedLanguage,
                providerToken = providerToken,
                load = load
            )
            ?: readSeriesLegacyDiskCache(
                resolvedId = resolvedId,
                originalId = identity.tvdbId,
                normalizedLanguage = normalizedLanguage,
                providerToken = providerToken,
                diagnosticMessage = "Serving stale cached enrichment after runtime refresh failure"
            )
    }

    private suspend fun fetchSeriesEnrichmentDirect(
        identity: TvdbSeriesIdentity,
        language: String? = null,
        allowLegacyDiskFallback: Boolean = true,
        withinRuntimeLoad: Boolean = false,
        runtimeLoadFailure: ((IntegrationLoadResult<TvMetadataEnrichment>) -> Unit)? = null
    ): TvMetadataEnrichment? = withContext(Dispatchers.IO) {
        val normalizedLanguage = normalizeLanguage(language)
        val activeProvider = posterRatingsUrlResolver.getActiveProvider()
        val providerToken = posterProviderCacheToken(activeProvider)

        // D-02: Resolve merge alias before cache lookup or API fetch
        val resolvedId = resolveSeriesAlias(identity.tvdbId)

        // Read TVDB disk cache first (last-known-good path for D-07)
        val cached = if (allowLegacyDiskFallback) {
            readSeriesLegacyDiskCache(
                resolvedId = resolvedId,
                originalId = identity.tvdbId,
                normalizedLanguage = normalizedLanguage,
                providerToken = providerToken,
                diagnosticMessage = null
            )
        } else {
            null
        }

        // D-08: Check credential health before network call
        if (!credentialHealth.canCallTvdb()) {
            // Serve cached data with stale-cache diagnostic or return null
            if (cached != null) {
                recordDiagnostic(
                    TvdbReliabilityReason.STALE_CACHE_SERVED,
                    "tvdb_metadata_service",
                    tvdbId = resolvedId,
                    message = "Serving cached enrichment while credentials invalid"
                )
            }
            return@withContext cached
        }

        // If cache hit, return immediately
        if (cached != null) return@withContext cached

        val record = runCatching {
            if (withinRuntimeLoad) {
                when (val result = provider.fetchSeriesExtendedWithinRuntimeLoad(tvdbId = resolvedId)) {
                    is IntegrationLoadResult.Success -> result.value
                    is IntegrationLoadResult.HttpError -> {
                        runtimeLoadFailure?.invoke(result)
                        null
                    }
                    is IntegrationLoadResult.NetworkError -> {
                        runtimeLoadFailure?.invoke(result)
                        null
                    }
                }
            } else {
                provider.fetchSeriesExtended(tvdbId = resolvedId)
            }
        }.onFailure { error ->
            Log.w(TAG, "TVDB series metadata request failed reason=${error.javaClass.simpleName}")
            // D-07: On network failure, try to serve cached data from original ID
            if (allowLegacyDiskFallback && resolvedId != identity.tvdbId) {
                readSeriesLegacyDiskCache(
                    resolvedId = identity.tvdbId,
                    originalId = identity.tvdbId,
                    normalizedLanguage = normalizedLanguage,
                    providerToken = providerToken,
                    diagnosticMessage = "Serving cached enrichment from original ID during outage"
                )?.let { return@withContext it }
            }
        }.getOrNull()

        if (record == null) {
            // D-07: Serve any cached data on network failure before returning null
            // Re-check with resolved ID (may have been populated between first check and now)
            if (allowLegacyDiskFallback) {
                readSeriesLegacyDiskCache(
                    resolvedId = resolvedId,
                    originalId = identity.tvdbId,
                    normalizedLanguage = normalizedLanguage,
                    providerToken = providerToken,
                    diagnosticMessage = "Serving stale cached enrichment after network failure"
                )?.let { return@withContext it }
            }
            return@withContext null
        }

        val seasonOrderContext = seasonOrderMapper.buildSeriesOrderContext(record)
        if (seasonOrderContext != null) {
            Log.d(TAG, "tvdb_season_type_present contentId=tvdb:${resolvedId} defaultType=${seasonOrderContext.defaultSeasonTypeId}")
        }
        val preferredCountryCodes = listOfNotNull(
            record.originalCountry?.trim()?.takeIf { it.isNotBlank() },
            record.country?.trim()?.takeIf { it.isNotBlank() }
        ).distinct()
        val advancedMetadata = advancedMetadataMapper.mapAdvancedMetadata(record, preferredCountryCodes)
        val hasAdvancedSurface = advancedMetadata.castMembers.isNotEmpty() ||
            advancedMetadata.productionCompanies.isNotEmpty() ||
            advancedMetadata.networks.isNotEmpty() ||
            advancedMetadata.genres.isNotEmpty() ||
            advancedMetadata.ageRating != null
        if (hasAdvancedSurface) {
            Log.d(TAG, "tvdb_advanced_surface_success contentId=tvdb:${resolvedId}")
        } else {
            Log.d(TAG, "tvdb_advanced_surface_missing contentId=tvdb:${resolvedId}")
        }
        val baseEnrichment = record.toEnrichment(
            identity = identity.copy(tvdbId = resolvedId),
            activeProvider = activeProvider,
            seasonOrderContext = seasonOrderContext,
            advancedMetadata = advancedMetadata
        ) ?: return@withContext null
        val translatedOverview = fetchSeriesTranslationOverview(
            seriesId = resolvedId,
            language = normalizedLanguage,
            withinRuntimeLoad = withinRuntimeLoad,
            runtimeLoadFailure = runtimeLoadFailure
        )
        val enrichment = baseEnrichment.copy(
            description = translatedOverview ?: baseEnrichment.description
        )
        metadataDiskCacheStore.writeTvdbEnrichment(
            seriesId = resolvedId,
            recordKind = SERIES_EXTENDED_RECORD_KIND,
            languageTag = normalizedLanguage,
            providerToken = providerToken,
            enrichment = enrichment
        )
        enrichment
    }

    private suspend fun fetchSeriesEnrichmentForRuntimeLoad(
        identity: TvdbSeriesIdentity,
        language: String? = null
    ): IntegrationLoadResult<TvMetadataEnrichment> {
        var failure: IntegrationLoadResult<TvMetadataEnrichment>? = null
        val enrichment = fetchSeriesEnrichmentDirect(
            identity = identity,
            language = language,
            allowLegacyDiskFallback = false,
            withinRuntimeLoad = true,
            runtimeLoadFailure = { failure = it }
        )
        return failure
            ?: enrichment?.let { IntegrationLoadResult.Success(it) }
            ?: IntegrationLoadResult.HttpError(404, reason = "tvdb_series_missing")
    }

    private suspend fun readSeriesLegacyDiskCache(
        resolvedId: Int,
        originalId: Int,
        normalizedLanguage: String,
        providerToken: String,
        diagnosticMessage: String?
    ): TvMetadataEnrichment? {
        val resolvedCached = metadataDiskCacheStore.readTvdbEnrichment(
            seriesId = resolvedId,
            recordKind = SERIES_EXTENDED_RECORD_KIND,
            languageTag = normalizedLanguage,
            providerToken = providerToken
        )
        if (resolvedCached != null) {
            if (diagnosticMessage != null) {
                recordDiagnostic(
                    TvdbReliabilityReason.STALE_CACHE_SERVED,
                    "tvdb_metadata_service",
                    tvdbId = resolvedId,
                    message = diagnosticMessage
                )
            }
            return resolvedCached
        }

        if (originalId == resolvedId) return null

        val originalCached = metadataDiskCacheStore.readTvdbEnrichment(
            seriesId = originalId,
            recordKind = SERIES_EXTENDED_RECORD_KIND,
            languageTag = normalizedLanguage,
            providerToken = providerToken
        )
        if (originalCached != null && diagnosticMessage != null) {
            recordDiagnostic(
                TvdbReliabilityReason.STALE_CACHE_SERVED,
                "tvdb_metadata_service",
                tvdbId = originalId,
                message = diagnosticMessage
            )
        }
        return originalCached
    }

    suspend fun fetchEpisodeEnrichment(
        identity: TvdbSeriesIdentity,
        seasonNumbers: List<Int>,
        language: String? = null
    ): Map<Pair<Int, Int>, TvEpisodeMetadata> = withContext(Dispatchers.IO) {
        val distinctSeasons = seasonNumbers.distinct().sorted()
        if (distinctSeasons.isEmpty()) return@withContext emptyMap()

        // D-02: Resolve merge alias for episode fetches too
        val resolvedId = resolveSeriesAlias(identity.tvdbId)
        val resolvedIdentity = identity.copy(tvdbId = resolvedId)

        val seasonEpisodes = coroutineScope {
            distinctSeasons.map { seasonNumber ->
                async {
                    fetchSeasonEpisodes(
                        identity = resolvedIdentity,
                        seasonNumber = seasonNumber,
                        language = language
                    )
                }
            }.awaitAll().flatten()
        }

        seasonEpisodes
            .map { it.metadata }
            .mapNotNull { metadata ->
                val seasonNumber = metadata.seasonNumber ?: return@mapNotNull null
                val episodeNumber = metadata.episodeNumber ?: return@mapNotNull null
                (seasonNumber to episodeNumber) to metadata
            }
            .toMap()
    }

    suspend fun fetchSeasonEpisodes(
        identity: TvdbSeriesIdentity,
        seasonNumber: Int,
        language: String? = null
    ): List<TvSeasonEpisode> = withContext(Dispatchers.IO) {
        val normalizedLanguage = normalizeLanguage(language)

        val cached = metadataDiskCacheStore.readTvdbSeasonEpisodes(
            seriesId = identity.tvdbId,
            seasonType = DEFAULT_SEASON_TYPE,
            seasonNumber = seasonNumber,
            languageTag = normalizedLanguage
        )

        // D-08: Check credential health before network call
        if (!credentialHealth.canCallTvdb()) {
            if (cached != null) {
                recordDiagnostic(
                    TvdbReliabilityReason.STALE_CACHE_SERVED,
                    "tvdb_metadata_service",
                    tvdbId = identity.tvdbId,
                    message = "Serving cached season episodes while credentials invalid"
                )
            }
            return@withContext cached?.map { it.toSeasonEpisode() } ?: emptyList()
        }

        if (cached != null) {
            return@withContext cached.map { metadata -> metadata.toSeasonEpisode() }
        }

        val data = runCatching {
            provider.fetchSeriesEpisodes(
                tvdbId = identity.tvdbId,
                seasonType = DEFAULT_SEASON_TYPE,
                page = 0,
                season = seasonNumber
            )
        }.onFailure { error ->
            Log.w(TAG, "TVDB season metadata request failed reason=${error.javaClass.simpleName}")
        }.getOrNull()

        if (data == null) {
            // D-07: Serve stale cached episodes on network failure
            val staleCached = metadataDiskCacheStore.readTvdbSeasonEpisodes(
                seriesId = identity.tvdbId,
                seasonType = DEFAULT_SEASON_TYPE,
                seasonNumber = seasonNumber,
                languageTag = normalizedLanguage
            )
            if (staleCached != null) {
                recordDiagnostic(
                    TvdbReliabilityReason.STALE_CACHE_SERVED,
                    "tvdb_metadata_service",
                    tvdbId = identity.tvdbId,
                    message = "Serving stale cached season episodes after network failure"
                )
                return@withContext staleCached.map { it.toSeasonEpisode() }
            }
            return@withContext emptyList()
        }

        val records = data.episodes.orEmpty()
        val translatedOverviewsById = fetchTranslatedSeasonEpisodeOverviews(
            seriesId = identity.tvdbId,
            seasonNumber = seasonNumber,
            language = normalizedLanguage
        )
        val perEpisodeTranslatedOverviewsById = fetchPerEpisodeTranslationOverviews(
            episodeIds = records.mapNotNull { record -> record.id }
                .filterNot { episodeId -> episodeId in translatedOverviewsById },
            language = normalizedLanguage
        )
        val allTranslatedOverviewsById = translatedOverviewsById + perEpisodeTranslatedOverviewsById

        val mapped = records
            .map { record ->
                record.toEpisodeMetadata(
                    translatedOverview = record.id?.let { allTranslatedOverviewsById[it] }
                )
            }
            .filter { metadata -> metadata.seasonNumber == seasonNumber }
            .sortedWith(compareBy<TvEpisodeMetadata> { it.episodeNumber ?: Int.MAX_VALUE }.thenBy { it.providerEpisodeId })

        metadataDiskCacheStore.writeTvdbSeasonEpisodes(
            seriesId = identity.tvdbId,
            seasonType = DEFAULT_SEASON_TYPE,
            seasonNumber = seasonNumber,
            languageTag = normalizedLanguage,
            episodes = mapped
        )

        mapped.map { metadata -> metadata.toSeasonEpisode() }
    }

    private suspend fun fetchSeriesTranslationOverview(
        seriesId: Int,
        language: String,
        withinRuntimeLoad: Boolean = false,
        runtimeLoadFailure: ((IntegrationLoadResult<TvMetadataEnrichment>) -> Unit)? = null
    ): String? {
        if (language == "eng") return null
        return runCatching {
            if (withinRuntimeLoad) {
                when (val result = provider.fetchSeriesTranslationWithinRuntimeLoad(
                    tvdbId = seriesId,
                    language = language
                )) {
                    is IntegrationLoadResult.Success -> result.value
                    is IntegrationLoadResult.HttpError -> {
                        if (result.statusCode == 429 || result.statusCode >= 500) {
                            runtimeLoadFailure?.invoke(result)
                        }
                        null
                    }
                    is IntegrationLoadResult.NetworkError -> {
                        runtimeLoadFailure?.invoke(result)
                        null
                    }
                }
            } else {
                provider.fetchSeriesTranslation(
                    tvdbId = seriesId,
                    language = language
                )
            }
        }.onFailure { error ->
            Log.w(TAG, "TVDB series translation request failed reason=${error.javaClass.simpleName}")
        }.getOrNull()
            .overviewText()
    }

    private fun TvdbSeriesExtendedRecord.toEnrichment(
        identity: TvdbSeriesIdentity,
        activeProvider: PosterRatingsUrlResolver.ActiveProvider?,
        seasonOrderContext: com.nexio.tv.domain.model.TvdbSeasonOrderContext? = null,
        advancedMetadata: TvdbAdvancedMetadata? = null
    ): TvMetadataEnrichment? {
        val artwork = selectArtwork(artworks.orEmpty())
        val tvdbPoster = artwork.poster ?: image.trimmed()
        val poster = posterRatingsUrlResolver.resolvePosterUrl(
            originalPosterUrl = tvdbPoster,
            contentId = "tvdb:${identity.tvdbId}",
            contentType = ContentType.SERIES,
            activeProvider = activeProvider
        )
        val remoteIds = mergeRemoteIds(identity.remoteIds, remoteIds.orEmpty())
        val genres = genres.orEmpty().mapNotNull { it.name.trimmed() }
        val contentRatings = contentRatings.orEmpty().mapNotNull { it.name.trimmed() }
        val description = overview.trimmed()
        val title = name.trimmed()
        val countries = listOfNotNull(country.trimmed(), originalCountry.trimmed()).distinct()
        val originalNetworkName = originalNetwork?.name.trimmed()
        val latestNetworkName = latestNetwork?.name.trimmed()

        if (
            title == null && description == null && genres.isEmpty() && poster == null &&
            artwork.backdrop == null && artwork.logo == null && firstAired.trimmed() == null &&
            averageRuntime == null && contentRatings.isEmpty()
        ) {
            return null
        }

        return TvMetadataEnrichment(
            seriesTvdbId = identity.tvdbId,
            localizedTitle = title,
            description = description,
            genres = advancedMetadata?.genres?.takeIf { it.isNotEmpty() } ?: genres,
            backdrop = artwork.backdrop,
            logo = artwork.logo,
            poster = poster,
            releaseInfo = firstAired.trimmed(),
            rating = null,
            runtimeMinutes = averageRuntime,
            ageRating = advancedMetadata?.ageRating ?: contentRatings.firstOrNull(),
            countries = countries.takeIf { it.isNotEmpty() },
            language = originalLanguage.trimmed(),
            airsDays = airsDays.toMap(),
            airsTime = airsTime.trimmed(),
            averageRuntimeMinutes = averageRuntime,
            originalCountry = originalCountry.trimmed(),
            originalNetwork = originalNetworkName,
            latestNetwork = latestNetworkName,
            platformName = originalNetworkName ?: latestNetworkName,
            originalLanguage = originalLanguage.trimmed(),
            status = status?.name.trimmed(),
            aliases = aliases.orEmpty().mapNotNull { it.name.trimmed() },
            contentRatings = contentRatings,
            remoteIds = remoteIds,
            seasonOrderContext = seasonOrderContext,
            castMembers = advancedMetadata?.castMembers.orEmpty(),
            productionCompanies = advancedMetadata?.productionCompanies.orEmpty(),
            networks = advancedMetadata?.networks.orEmpty()
        )
    }

    private suspend fun fetchTranslatedSeasonEpisodeOverviews(
        seriesId: Int,
        seasonNumber: Int,
        language: String
    ): Map<Int, String> {
        if (language == "eng") return emptyMap()
        return runCatching {
            provider.fetchSeriesEpisodesTranslated(
                tvdbId = seriesId,
                seasonType = DEFAULT_SEASON_TYPE,
                language = language,
                page = 0,
                season = seasonNumber
            )
        }.onFailure { error ->
            Log.w(TAG, "TVDB translated season episodes request failed reason=${error.javaClass.simpleName}")
        }.getOrNull()
            ?.episodes
            .orEmpty()
            .mapNotNull { record ->
                val id = record.id ?: return@mapNotNull null
                val overview = record.overview.trimmed() ?: return@mapNotNull null
                id to overview
            }
            .toMap()
    }

    private suspend fun fetchPerEpisodeTranslationOverviews(
        episodeIds: List<Int>,
        language: String
    ): Map<Int, String> {
        if (language == "eng" || episodeIds.isEmpty()) return emptyMap()
        val translated = linkedMapOf<Int, String>()
        episodeIds.distinct().forEach { episodeId ->
            val overview = runCatching {
                provider.fetchEpisodeTranslation(
                    episodeId = episodeId,
                    language = language
                )
            }.onFailure { error ->
                Log.w(TAG, "TVDB episode translation request failed reason=${error.javaClass.simpleName}")
            }.getOrNull()
                .overviewText()
            if (overview != null) {
                translated[episodeId] = overview
            }
        }
        return translated
    }

    private fun TvdbEpisodeRecord.toEpisodeMetadata(
        translatedOverview: String? = null
    ): TvEpisodeMetadata {
        val base = TvEpisodeMetadata(
            providerEpisodeId = id?.let { "tvdb:$it" },
            seasonNumber = seasonNumber,
            episodeNumber = number,
            title = name.trimmed(),
            overview = translatedOverview ?: overview.trimmed(),
            thumbnail = image.trimmed(),
            airDate = aired.trimmed(),
            runtimeMinutes = runtime,
            absoluteNumber = absoluteNumber,
            airsAfterSeason = airsAfterSeason,
            airsBeforeSeason = airsBeforeSeason,
            airsBeforeEpisode = airsBeforeEpisode,
            linkedMovieTvdbId = linkedMovie,
            finaleType = finaleType.trimmed()
        )
        return base.copy(tvdbEpisodeOrder = seasonOrderMapper.mapEpisodeOrder(base))
    }

    private fun TvEpisodeMetadata.toSeasonEpisode(): TvSeasonEpisode {
        return TvSeasonEpisode(
            episodeNumber = episodeNumber,
            airDate = airDate,
            metadata = this
        )
    }

    private fun selectArtwork(artworks: List<TvdbArtworkRecord>): SelectedTvdbArtwork {
        val byQuality = artworks
            .filter { artwork -> artwork.image.trimmed() != null }
            .sortedByDescending { artwork -> artwork.score ?: 0.0 }

        return SelectedTvdbArtwork(
            poster = byQuality.firstImageForType(2),
            backdrop = byQuality.firstImageForType(3),
            logo = byQuality.firstImageForType(23)
        )
    }

    private fun List<TvdbArtworkRecord>.firstImageForType(type: Int): String? {
        return firstOrNull { artwork -> artwork.type == type }?.image.trimmed()
    }

    private fun mergeRemoteIds(
        identityRemoteIds: Map<TvdbRemoteIdSource, Set<String>>,
        recordRemoteIds: List<TvdbRemoteId>
    ): Map<String, Set<String>> {
        val grouped = linkedMapOf<String, MutableSet<String>>()

        identityRemoteIds.forEach { (source, values) ->
            val key = source.toMetadataKey()
            values.mapNotNullTo(grouped.getOrPut(key) { linkedSetOf() }) { it.trimmed() }
        }

        recordRemoteIds.forEach { remoteId ->
            val value = remoteId.id.trimmed() ?: return@forEach
            val source = normalizeTvdbRemoteIdSource(remoteId.sourceName).toMetadataKey()
            grouped.getOrPut(source) { linkedSetOf() }.add(value)
        }

        return grouped.mapValues { (_, values) -> values.toSet() }
    }

    private fun TvdbRemoteIdSource.toMetadataKey(): String = name.lowercase()

    private fun TvdbAirsDays?.toMap(): Map<String, Boolean> {
        if (this == null) return emptyMap()
        return linkedMapOf(
            "monday" to monday,
            "tuesday" to tuesday,
            "wednesday" to wednesday,
            "thursday" to thursday,
            "friday" to friday,
            "saturday" to saturday,
            "sunday" to sunday
        ).mapNotNull { (day, value) -> value?.let { day to it } }.toMap()
    }

    private fun posterProviderCacheToken(
        activeProvider: PosterRatingsUrlResolver.ActiveProvider?
    ): String {
        if (activeProvider == null) return "native"
        return "${activeProvider.provider.name}:${activeProvider.apiKey.hashCode()}"
    }

    private fun normalizeLanguage(language: String?): String {
        return TvdbLanguageMapper.normalize(language)
    }

    private fun String?.trimmed(): String? = this?.trim()?.takeIf { it.isNotBlank() }

    private fun TvdbTranslationRecord?.overviewText(): String? {
        return this?.overview.trimmed()
    }

    /**
     * D-02: Resolve merge alias for a series ID before cache lookup or API fetch.
     * When an alias exists, returns the merge target ID; otherwise returns the original ID.
     */
    private suspend fun resolveSeriesAlias(seriesId: Int): Int {
        val alias = runCatching { mergeAliasStore.resolveAlias("series", seriesId) }
            .getOrNull()
        return alias?.toId ?: seriesId
    }

    /**
     * Records a reliability diagnostic and emits structured log fields.
     * Does not include Authorization headers, API keys, PINs, bearer tokens,
     * raw response bodies, or full request URLs.
     */
    private suspend fun recordDiagnostic(
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
        Log.d(TAG, fields.entries.joinToString(", ") { "${it.key}=${it.value}" })
    }

    private data class SelectedTvdbArtwork(
        val poster: String?,
        val backdrop: String?,
        val logo: String?
    )
}
