package com.nexio.tv.data.integration.tvdb

import com.nexio.tv.core.integration.IntegrationCachePolicy
import com.nexio.tv.core.integration.IntegrationCacheOwnershipFactory
import com.nexio.tv.core.integration.IntegrationCallResult
import com.nexio.tv.core.integration.IntegrationCallSpec
import com.nexio.tv.core.integration.IntegrationFetchOptions
import com.nexio.tv.core.integration.IntegrationLoadResult
import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.core.integration.RailMediaIdentityResolver
import com.nexio.tv.core.integration.IntegrationRuntime
import com.nexio.tv.core.integration.IntegrationSpec
import com.nexio.tv.core.integration.IntegrationWorkClass
import com.nexio.tv.core.integration.TvdbApiShapes
import com.nexio.tv.core.integration.gsonCodec
import com.nexio.tv.core.integration.valueOrNull
import com.nexio.tv.core.tvdb.TvdbAuthService
import com.nexio.tv.core.tvdb.TvMetadataEnrichment
import com.nexio.tv.core.tvdb.TvEpisodeMetadata
import com.nexio.tv.core.tvdb.TvdbLanguageMapper
import com.nexio.tv.core.tvdb.TvdbReferenceKind
import com.nexio.tv.core.tvdb.TvdbRemoteIdSource
import com.nexio.tv.core.tvdb.TvdbSeriesIdentity
import com.nexio.tv.data.remote.api.TvdbApi
import com.nexio.tv.data.remote.api.TvdbLoginRequest
import com.nexio.tv.data.remote.api.TvdbLoginResponse
import com.nexio.tv.data.remote.api.TvdbPersonExtendedRecord
import com.nexio.tv.data.remote.api.TvdbRemoteIdSearchResponse
import com.nexio.tv.data.remote.api.TvdbSearchResponse
import com.nexio.tv.data.remote.api.TvdbSeriesBaseRecord
import com.nexio.tv.data.remote.api.TvdbSeriesEpisodesData
import com.nexio.tv.data.remote.api.TvdbSeriesExtendedRecord
import com.nexio.tv.data.remote.api.TvdbTranslationRecord
import com.nexio.tv.data.integration.metadata.LocalizationPolicy
import com.nexio.tv.data.integration.metadata.LocalizedEpisodeBundle
import com.nexio.tv.data.integration.metadata.TvdbEpisodeLocalization
import com.nexio.tv.data.integration.metadata.tvdbSeriesTranslationCacheKey
import com.nexio.tv.data.remote.api.TvdbUpdatesResponse
import com.nexio.tv.domain.model.ContentType
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton
import retrofit2.Response

@Singleton
class TvdbIntegrationProvider @Inject constructor(
    private val runtime: IntegrationRuntime,
    private val tvdbApi: TvdbApi,
    private val tvdbAuthService: TvdbAuthService,
    private val ownershipFactory: IntegrationCacheOwnershipFactory = IntegrationCacheOwnershipFactory(
        RailMediaIdentityResolver()
    )
) {
    suspend fun login(
        apiKey: String,
        pin: String
    ): IntegrationCallResult<TvdbLoginResponse> =
        runtime.call(
            IntegrationCallSpec(
                provider = IntegrationProvider.TVDB,
                apiShapeId = TvdbApiShapes.LOGIN,
                operationKey = "tvdb.login",
                workClass = IntegrationWorkClass.USER_VISIBLE,
                call = {
                    runCatching {
                        tvdbApi.login(
                            TvdbLoginRequest(
                                apikey = apiKey,
                                pin = pin.takeIf { it.isNotBlank() }
                            )
                        )
                    }.fold(
                        onSuccess = { response ->
                            if (!response.isSuccessful) {
                                IntegrationCallResult.HttpError(response.code())
                            } else {
                                response.body()?.let { IntegrationCallResult.Success(it) }
                                    ?: IntegrationCallResult.Missing
                            }
                        },
                        onFailure = { error ->
                            if (error is CancellationException) throw error
                            IntegrationCallResult.NetworkError(error)
                        }
                    )
                }
            )
        )

    suspend fun <T> runMaintenanceUpdate(
        triggerKey: String,
        codec: com.nexio.tv.core.integration.IntegrationCodec<T>,
        load: suspend () -> T?
    ): T? {
        val spec = IntegrationSpec(
            provider = IntegrationProvider.TVDB,
            apiShapeId = TvdbApiShapes.UPDATES,
            operationKey = "tvdb.run_maintenance_update",
            cacheKey = "tvdb:updates:$triggerKey",
            codec = codec,
            cachePolicy = IntegrationCachePolicy.Disabled,
            workClass = IntegrationWorkClass.MAINTENANCE,
            load = {
                load()?.let { IntegrationLoadResult.Success(it) }
                    ?: IntegrationLoadResult.HttpError(503, reason = "tvdb_maintenance_missing")
            }
        )
        return runtime.get(spec).valueOrNull()
    }

    suspend fun readCachedSeriesEnrichment(
        identity: TvdbSeriesIdentity,
        resolvedId: Int,
        normalizedLanguage: String,
        providerToken: String,
        load: suspend () -> IntegrationLoadResult<TvMetadataEnrichment>
    ): TvMetadataEnrichment? =
        fetchSeriesEnrichmentRuntime(
            identity = identity,
            resolvedId = resolvedId,
            normalizedLanguage = normalizedLanguage,
            providerToken = providerToken,
            cacheOnly = true,
            load = load
        )

    suspend fun fetchSeriesEnrichment(
        identity: TvdbSeriesIdentity,
        resolvedId: Int,
        normalizedLanguage: String,
        providerToken: String,
        load: suspend () -> IntegrationLoadResult<TvMetadataEnrichment>
    ): TvMetadataEnrichment? =
        fetchSeriesEnrichmentRuntime(
            identity = identity,
            resolvedId = resolvedId,
            normalizedLanguage = normalizedLanguage,
            providerToken = providerToken,
            cacheOnly = false,
            load = load
        )

    private suspend fun fetchSeriesEnrichmentRuntime(
        identity: TvdbSeriesIdentity,
        resolvedId: Int,
        normalizedLanguage: String,
        providerToken: String,
        cacheOnly: Boolean,
        load: suspend () -> IntegrationLoadResult<TvMetadataEnrichment>
    ): TvMetadataEnrichment? {
        val spec = IntegrationSpec(
            provider = IntegrationProvider.TVDB,
            apiShapeId = TvdbApiShapes.SERIES_EXTENDED,
            operationKey = "tvdb.fetch_series_enrichment",
            cacheKey = "tvdb:series:$resolvedId:$normalizedLanguage:$providerToken:enrichment",
            codec = gsonCodec<TvMetadataEnrichment>(),
            cachePolicy = IntegrationCachePolicy.CacheFirst(
                ttlMs = 7L * 24L * 60L * 60L * 1000L,
                staleAfterExpiryMs = 30L * 24L * 60L * 60L * 1000L
            ),
            ownership = ownershipFactory.media(
                mediaType = ContentType.SERIES.toApiString(),
                rawId = "tvdb:$resolvedId",
                title = identity.name,
                year = identity.year?.toIntOrNull(),
                imdbId = identity.remoteIds[TvdbRemoteIdSource.IMDB]?.firstOrNull(),
                tmdbId = identity.remoteIds[TvdbRemoteIdSource.TMDB]?.firstOrNull()
            ),
            workClass = IntegrationWorkClass.USER_VISIBLE,
            load = load
        )
        return runtime.get(spec, IntegrationFetchOptions(cacheOnly = cacheOnly)).valueOrNull()
    }

    suspend fun fetchSeriesExtended(
        tvdbId: Int,
        meta: String? = null,
        short: Boolean = false
    ): TvdbSeriesExtendedRecord? {
        return callAuthenticated(TvdbApiShapes.SERIES_EXTENDED, "tvdb.fetch_series_extended", IntegrationWorkClass.USER_VISIBLE) { authorization ->
            tvdbApi.getSeriesExtended(
                authorization = authorization,
                id = tvdbId,
                meta = meta,
                short = short
            )
        }?.data
    }

    internal suspend fun fetchSeriesExtendedWithinRuntimeLoad(
        tvdbId: Int,
        meta: String? = null,
        short: Boolean = false
    ): IntegrationLoadResult<TvdbSeriesExtendedRecord> {
        return when (
            val result = callAuthenticatedDirect { authorization ->
                tvdbApi.getSeriesExtended(
                    authorization = authorization,
                    id = tvdbId,
                    meta = meta,
                    short = short
                )
            }
        ) {
            is IntegrationLoadResult.Success ->
                result.value.data?.let { IntegrationLoadResult.Success(it) }
                    ?: IntegrationLoadResult.HttpError(404, reason = "tvdb_series_missing")
            is IntegrationLoadResult.HttpError -> result
            is IntegrationLoadResult.NetworkError -> result
        }
    }

    internal suspend fun fetchSeriesTranslationWithinRuntimeLoad(
        tvdbId: Int,
        language: String
    ): IntegrationLoadResult<TvdbTranslationRecord> {
        return when (
            val result = callAuthenticatedDirect { authorization ->
                tvdbApi.getSeriesTranslation(
                    authorization = authorization,
                    id = tvdbId,
                    language = language
                )
            }
        ) {
            is IntegrationLoadResult.Success ->
                result.value.data?.let { IntegrationLoadResult.Success(it) }
                    ?: IntegrationLoadResult.HttpError(404, reason = "tvdb_translation_missing")
            is IntegrationLoadResult.HttpError -> result
            is IntegrationLoadResult.NetworkError -> result
        }
    }

    suspend fun fetchSeriesBase(tvdbId: Int): TvdbSeriesBaseRecord? {
        return callAuthenticated(TvdbApiShapes.SERIES_BASE, "tvdb.fetch_series_base", IntegrationWorkClass.USER_VISIBLE) { authorization ->
            tvdbApi.getSeriesBase(authorization, tvdbId)
        }?.data
    }

    suspend fun fetchSeriesEpisodes(
        tvdbId: Int,
        seasonType: String,
        page: Int = 0,
        season: Int? = null
    ): TvdbSeriesEpisodesData? {
        val authorization = tvdbAuthService.bearerToken() ?: return null
        val spec = IntegrationSpec(
            provider = IntegrationProvider.TVDB,
            apiShapeId = TvdbApiShapes.SERIES_EPISODES_SEASON_TYPE,
            operationKey = "tvdb.series.episodes.season_type",
            cacheKey = "tvdb:series:$tvdbId:episodes:$seasonType:season:${season ?: "all"}:page:$page",
            codec = gsonCodec<TvdbSeriesEpisodesData>(),
            cachePolicy = IntegrationCachePolicy.CacheFirst(
                ttlMs = 24L * 60L * 60L * 1000L,
                staleAfterExpiryMs = 7L * 24L * 60L * 60L * 1000L
            ),
            workClass = IntegrationWorkClass.USER_VISIBLE,
            load = {
                val response = runCatching {
                    tvdbApi.getSeriesEpisodes(
                        authorization = authorization,
                        id = tvdbId,
                        seasonType = seasonType,
                        page = page,
                        season = season
                    )
                }.getOrElse {
                    if (it is CancellationException) throw it
                    return@IntegrationSpec IntegrationLoadResult.NetworkError(it)
                }
                if (!response.isSuccessful) {
                    IntegrationLoadResult.HttpError(response.code())
                } else {
                    response.body()?.data?.let { IntegrationLoadResult.Success(it) }
                        ?: IntegrationLoadResult.HttpError(404, reason = "tvdb_series_episodes_missing")
                }
            }
        )
        return runtime.get(spec).valueOrNull()
    }

    suspend fun fetchSeriesTranslation(
        tvdbId: Int,
        language: String,
        localizationPolicyVersion: Int = LocalizationPolicy.CURRENT_VERSION
    ): TvdbTranslationRecord? {
        val authorization = tvdbAuthService.bearerToken() ?: return null
        val spec = IntegrationSpec(
            provider = IntegrationProvider.TVDB,
            apiShapeId = TvdbApiShapes.SERIES_TRANSLATION,
            operationKey = "tvdb.series.translation",
            cacheKey = tvdbSeriesTranslationCacheKey(tvdbId, language, localizationPolicyVersion),
            codec = gsonCodec<TvdbTranslationRecord>(),
            cachePolicy = IntegrationCachePolicy.CacheFirst(
                ttlMs = 24L * 60L * 60L * 1000L,
                staleAfterExpiryMs = 7L * 24L * 60L * 60L * 1000L
            ),
            workClass = IntegrationWorkClass.USER_VISIBLE,
            load = {
                val response = runCatching {
                    tvdbApi.getSeriesTranslation(
                        authorization = authorization,
                        id = tvdbId,
                        language = language
                    )
                }.getOrElse {
                    if (it is CancellationException) throw it
                    return@IntegrationSpec IntegrationLoadResult.NetworkError(it)
                }
                if (!response.isSuccessful) {
                    IntegrationLoadResult.HttpError(response.code())
                } else {
                    response.body()?.data?.let { IntegrationLoadResult.Success(it) }
                        ?: IntegrationLoadResult.HttpError(404, reason = "tvdb_series_translation_missing")
                }
            }
        )
        return runtime.get(spec).valueOrNull()
    }

    suspend fun fetchSeriesEpisodesTranslated(
        tvdbId: Int,
        seasonType: String,
        language: String,
        page: Int = 0,
        season: Int? = null,
        localizationPolicyVersion: Int = LocalizationPolicy.CURRENT_VERSION
    ): TvdbSeriesEpisodesData? {
        val authorization = tvdbAuthService.bearerToken() ?: return null
        val spec = IntegrationSpec(
            provider = IntegrationProvider.TVDB,
            apiShapeId = TvdbApiShapes.SERIES_EPISODES_LANGUAGE,
            operationKey = "tvdb.series.episodes.language",
            cacheKey = "tvdb:series:$tvdbId:episodes:$seasonType:$language:season:${season ?: "all"}:page:$page:policy:$localizationPolicyVersion",
            codec = gsonCodec<TvdbSeriesEpisodesData>(),
            cachePolicy = IntegrationCachePolicy.CacheFirst(
                ttlMs = 24L * 60L * 60L * 1000L,
                staleAfterExpiryMs = 7L * 24L * 60L * 60L * 1000L
            ),
            workClass = IntegrationWorkClass.USER_VISIBLE,
            load = {
                val response = runCatching {
                    tvdbApi.getSeriesEpisodesTranslated(
                        authorization = authorization,
                        id = tvdbId,
                        seasonType = seasonType,
                        language = language,
                        page = page,
                        season = season
                    )
                }.getOrElse {
                    if (it is CancellationException) throw it
                    return@IntegrationSpec IntegrationLoadResult.NetworkError(it)
                }
                if (!response.isSuccessful) {
                    IntegrationLoadResult.HttpError(response.code())
                } else {
                    response.body()?.data?.let { IntegrationLoadResult.Success(it) }
                        ?: IntegrationLoadResult.HttpError(404, reason = "tvdb_series_episodes_language_missing")
                }
            }
        )
        return runtime.get(spec).valueOrNull()
    }

    suspend fun fetchLocalizedSeasonEpisodeMetadata(
        tvdbId: Int,
        seasonType: String,
        requestedLanguage: String?,
        season: Int? = null
    ): Map<Pair<Int, Int>, TvEpisodeMetadata> =
        fetchLocalizedSeasonEpisodeBundle(
            tvdbId = tvdbId,
            seasonType = seasonType,
            requestedLanguage = requestedLanguage,
            season = season
        ).episodes.mapValues { it.value.metadata }

    internal suspend fun fetchLocalizedSeasonEpisodeBundle(
        tvdbId: Int,
        seasonType: String,
        requestedLanguage: String?,
        season: Int? = null
    ): LocalizedEpisodeBundle {
        val policy = LocalizationPolicy.tvdb(requestedLanguage)
        val englishEpisodes = fetchSeriesEpisodesTranslated(
            tvdbId = tvdbId,
            seasonType = seasonType,
            language = policy.fallbackLanguage.providerCode,
            season = season,
            localizationPolicyVersion = policy.policyVersion
        )?.episodes.orEmpty()
        if (englishEpisodes.isEmpty()) {
            return TvdbEpisodeLocalization.mergeEnglishBaseBundle(
                policy = policy,
                englishEpisodes = emptyList(),
                localizedSeasonEpisodes = emptyList(),
                perEpisodeTranslations = emptyMap()
            )
        }

        if (policy.requestedIsFallback) {
            return TvdbEpisodeLocalization.mergeEnglishBaseBundle(
                policy = policy,
                englishEpisodes = englishEpisodes,
                localizedSeasonEpisodes = emptyList(),
                perEpisodeTranslations = emptyMap()
            )
        }

        val localizedEpisodes = fetchSeriesEpisodesTranslated(
            tvdbId = tvdbId,
            seasonType = seasonType,
            language = policy.requestedLanguage.providerCode,
            season = season,
            localizationPolicyVersion = policy.policyVersion
        )?.episodes.orEmpty()
        val perEpisodeTranslations = TvdbEpisodeLocalization
            .idsMissingLocalizedFields(policy, englishEpisodes, localizedEpisodes)
            .associateWith { episodeId ->
                fetchEpisodeTranslation(
                    episodeId = episodeId,
                    language = policy.requestedLanguage.providerCode,
                    localizationPolicyVersion = policy.policyVersion
                )
            }
            .mapNotNull { (episodeId, translation) ->
                translation?.let { episodeId to it }
            }
            .toMap()

        return TvdbEpisodeLocalization.mergeEnglishBaseBundle(
            policy = policy,
            englishEpisodes = englishEpisodes,
            localizedSeasonEpisodes = localizedEpisodes,
            perEpisodeTranslations = perEpisodeTranslations
        )
    }

    suspend fun fetchEpisodeTranslation(
        episodeId: Int,
        language: String,
        localizationPolicyVersion: Int = LocalizationPolicy.CURRENT_VERSION
    ): TvdbTranslationRecord? {
        val authorization = tvdbAuthService.bearerToken() ?: return null
        val spec = IntegrationSpec(
            provider = IntegrationProvider.TVDB,
            apiShapeId = TvdbApiShapes.EPISODE_TRANSLATION,
            operationKey = "tvdb.episode.translation",
            cacheKey = "tvdb:episode:$episodeId:translation:$language:policy:$localizationPolicyVersion",
            codec = gsonCodec<TvdbTranslationRecord>(),
            cachePolicy = IntegrationCachePolicy.CacheFirst(
                ttlMs = 24L * 60L * 60L * 1000L,
                staleAfterExpiryMs = 7L * 24L * 60L * 60L * 1000L
            ),
            workClass = IntegrationWorkClass.USER_VISIBLE,
            load = {
                val response = runCatching {
                    tvdbApi.getEpisodeTranslation(
                        authorization = authorization,
                        id = episodeId,
                        language = language
                    )
                }.getOrElse {
                    if (it is CancellationException) throw it
                    return@IntegrationSpec IntegrationLoadResult.NetworkError(it)
                }
                if (!response.isSuccessful) {
                    IntegrationLoadResult.HttpError(response.code())
                } else {
                    response.body()?.data?.let { IntegrationLoadResult.Success(it) }
                        ?: IntegrationLoadResult.HttpError(404, reason = "tvdb_episode_translation_missing")
                }
            }
        )
        return runtime.get(spec).valueOrNull()
    }

    suspend fun searchByRemoteId(remoteId: String): TvdbRemoteIdSearchResponse? {
        val authorization = tvdbAuthService.bearerToken() ?: return null
        val spec = IntegrationSpec(
            provider = IntegrationProvider.TVDB,
            apiShapeId = TvdbApiShapes.REMOTE_ID_LOOKUP,
            operationKey = "tvdb.remoteid.lookup",
            cacheKey = "tvdb:remoteid:$remoteId",
            codec = gsonCodec<TvdbRemoteIdSearchResponse>(),
            cachePolicy = IntegrationCachePolicy.CacheFirst(
                ttlMs = 24L * 60L * 60L * 1000L,
                staleAfterExpiryMs = 7L * 24L * 60L * 60L * 1000L
            ),
            workClass = IntegrationWorkClass.USER_VISIBLE,
            load = {
                val response = runCatching { tvdbApi.searchByRemoteId(authorization, remoteId) }
                    .getOrElse {
                        if (it is CancellationException) throw it
                        return@IntegrationSpec IntegrationLoadResult.NetworkError(it)
                    }
                if (!response.isSuccessful) {
                    IntegrationLoadResult.HttpError(response.code())
                } else {
                    response.body()?.let { IntegrationLoadResult.Success(it) }
                        ?: IntegrationLoadResult.HttpError(404, reason = "tvdb_remoteid_missing")
                }
            }
        )
        return runtime.get(spec).valueOrNull()
    }

    suspend fun searchSeries(remoteId: String): TvdbSearchResponse? {
        return callAuthenticated(TvdbApiShapes.SEARCH, "tvdb.search_series", IntegrationWorkClass.USER_VISIBLE) { authorization ->
            tvdbApi.search(
                authorization = authorization,
                remoteId = remoteId,
                type = "series"
            )
        }
    }

    suspend fun fetchPersonExtended(peopleId: Int): TvdbPersonExtendedRecord? {
        return callAuthenticated(TvdbApiShapes.PERSON_EXTENDED, "tvdb.fetch_person_extended", IntegrationWorkClass.USER_VISIBLE) { authorization ->
            tvdbApi.getPersonExtended(authorization, peopleId)
        }?.data
    }

    suspend fun fetchReferenceRecords(kind: TvdbReferenceKind): List<Any>? {
        return when (kind) {
            TvdbReferenceKind.ARTWORK_TYPES ->
                callAuthenticated(TvdbApiShapes.REFERENCE_ARTWORK_TYPES, "tvdb.fetch_reference_records", IntegrationWorkClass.MAINTENANCE) { tvdbApi.getArtworkTypes(it) }
                    ?.data?.map { it as Any }
            TvdbReferenceKind.ARTWORK_STATUSES ->
                callAuthenticated("tvdb.reference.artwork_statuses", "tvdb.fetch_reference_records", IntegrationWorkClass.MAINTENANCE) { tvdbApi.getArtworkStatuses(it) }
                    ?.data?.map { it as Any }
            TvdbReferenceKind.GENRES ->
                callAuthenticated(TvdbApiShapes.REFERENCE_GENRES, "tvdb.fetch_reference_records", IntegrationWorkClass.MAINTENANCE) { tvdbApi.getGenres(it) }
                    ?.data?.map { it as Any }
            TvdbReferenceKind.LANGUAGES ->
                callAuthenticated(TvdbApiShapes.REFERENCE_LANGUAGES, "tvdb.fetch_reference_records", IntegrationWorkClass.MAINTENANCE) { tvdbApi.getLanguages(it) }
                    ?.data?.map { it as Any }
            TvdbReferenceKind.SERIES_STATUSES ->
                callAuthenticated("tvdb.reference.series_statuses", "tvdb.fetch_reference_records", IntegrationWorkClass.MAINTENANCE) { tvdbApi.getSeriesStatuses(it) }
                    ?.data?.map { it as Any }
            TvdbReferenceKind.CONTENT_RATINGS ->
                callAuthenticated(TvdbApiShapes.REFERENCE_CONTENT_RATINGS, "tvdb.fetch_reference_records", IntegrationWorkClass.MAINTENANCE) { tvdbApi.getContentRatings(it) }
                    ?.data?.map { it as Any }
            TvdbReferenceKind.SEASON_TYPES ->
                callAuthenticated(TvdbApiShapes.REFERENCE_SEASON_TYPES, "tvdb.fetch_reference_records", IntegrationWorkClass.MAINTENANCE) { tvdbApi.getSeasonTypes(it) }
                    ?.data?.map { it as Any }
            TvdbReferenceKind.SOURCE_TYPES ->
                callAuthenticated("tvdb.reference.source_types", "tvdb.fetch_reference_records", IntegrationWorkClass.MAINTENANCE) { tvdbApi.getSourceTypes(it) }
                    ?.data?.map { it as Any }
            TvdbReferenceKind.ENTITY_TYPES ->
                callAuthenticated("tvdb.reference.entity_types", "tvdb.fetch_reference_records", IntegrationWorkClass.MAINTENANCE) { tvdbApi.getEntityTypes(it) }
                    ?.data?.map { it as Any }
            TvdbReferenceKind.COMPANY_TYPES ->
                callAuthenticated("tvdb.reference.company_types", "tvdb.fetch_reference_records", IntegrationWorkClass.MAINTENANCE) { tvdbApi.getCompanyTypes(it) }
                    ?.data?.map { it as Any }
        }
    }

    suspend fun fetchUpdates(
        since: Long,
        page: Int?
    ): TvdbUpdatesResponse? {
        return callAuthenticated(TvdbApiShapes.UPDATES, "tvdb.fetch_updates", IntegrationWorkClass.MAINTENANCE) { authorization ->
            tvdbApi.getUpdates(
                authorization = authorization,
                since = since,
                type = null,
                page = page
            )
        }
    }

    private suspend fun <T> callAuthenticated(
        apiShapeId: String,
        operationKey: String,
        workClass: IntegrationWorkClass,
        call: suspend (String) -> Response<T>
    ): T? {
        val authorization = tvdbAuthService.bearerToken() ?: return null
        return when (
            val result = runtime.call(
                IntegrationCallSpec(
                    provider = IntegrationProvider.TVDB,
                    apiShapeId = apiShapeId,
                    operationKey = operationKey,
                    workClass = workClass,
                    call = {
                        val response = runCatching { call(authorization) }
                            .getOrElse {
                                if (it is CancellationException) throw it
                                return@IntegrationCallSpec IntegrationCallResult.NetworkError(it)
                            }
                        if (!response.isSuccessful) {
                            IntegrationCallResult.HttpError(response.code())
                        } else {
                            response.body()?.let { IntegrationCallResult.Success(it) }
                                ?: IntegrationCallResult.Missing
                        }
                    }
                )
            )
        ) {
            is IntegrationCallResult.Success -> result.value
            else -> null
        }
    }

    private suspend fun <T> callAuthenticatedDirect(
        call: suspend (String) -> Response<T>
    ): IntegrationLoadResult<T> {
        val authorization = tvdbAuthService.bearerToken()
            ?: return IntegrationLoadResult.HttpError(401, reason = "tvdb_auth_missing")
        val response = runCatching { call(authorization) }
            .getOrElse {
                if (it is CancellationException) throw it
                return IntegrationLoadResult.NetworkError(it)
            }
        if (!response.isSuccessful) {
            return IntegrationLoadResult.HttpError(response.code())
        }
        return response.body()?.let { IntegrationLoadResult.Success(it) }
            ?: IntegrationLoadResult.HttpError(404, reason = "tvdb_body_missing")
    }
}
