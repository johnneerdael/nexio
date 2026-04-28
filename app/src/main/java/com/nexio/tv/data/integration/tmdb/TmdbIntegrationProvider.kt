package com.nexio.tv.data.integration.tmdb

import com.nexio.tv.core.integration.IntegrationCachePolicy
import com.nexio.tv.core.integration.IntegrationCacheOwnershipFactory
import com.nexio.tv.core.integration.IntegrationCallResult
import com.nexio.tv.core.integration.IntegrationCallSpec
import com.nexio.tv.core.integration.IntegrationCodec
import com.nexio.tv.core.integration.IntegrationFetchResult
import com.nexio.tv.core.integration.IntegrationLoadResult
import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.core.integration.RailMediaIdentityResolver
import com.nexio.tv.core.integration.IntegrationRuntime
import com.nexio.tv.core.integration.IntegrationSpec
import com.nexio.tv.core.integration.IntegrationWorkClass
import com.nexio.tv.core.integration.TmdbApiShapes
import com.nexio.tv.core.integration.credentialHash
import com.nexio.tv.core.integration.gsonCodec
import com.nexio.tv.core.integration.StringIntegrationCodec
import com.nexio.tv.core.integration.valueOrNull
import com.nexio.tv.core.metadata.MetadataApiKeyResolver
import com.nexio.tv.core.metadata.MetadataProviderCredential
import com.nexio.tv.core.poster.PosterRatingsUrlResolver
import com.nexio.tv.core.tmdb.TmdbEnrichment
import com.nexio.tv.core.tmdb.TmdbService
import com.nexio.tv.data.local.TmdbCatalogIds
import com.nexio.tv.data.local.TmdbCatalogPreferences
import com.nexio.tv.data.integration.metadata.LocalizationPolicy
import com.nexio.tv.data.remote.api.TmdbApi
import com.nexio.tv.data.remote.api.TmdbCollectionResponse
import com.nexio.tv.data.remote.api.TmdbCompanyDetailsResponse
import com.nexio.tv.data.remote.api.TmdbDiscoverResponse
import com.nexio.tv.data.remote.api.TmdbImage
import com.nexio.tv.data.remote.api.TmdbEpisode
import com.nexio.tv.data.remote.api.TmdbDetailsResponse
import com.nexio.tv.data.remote.api.TmdbExternalIdsResponse
import com.nexio.tv.data.remote.api.TmdbFindResponse
import com.nexio.tv.data.remote.api.TmdbImagesResponse
import com.nexio.tv.data.remote.api.TmdbRecommendationsResponse
import com.nexio.tv.data.remote.api.TmdbMediaResult
import com.nexio.tv.data.remote.api.TmdbPagedMediaResponse
import com.nexio.tv.data.remote.api.TmdbRecommendationResult
import com.nexio.tv.data.remote.api.TmdbReviewsResponse
import com.nexio.tv.data.remote.api.TmdbReviewResult
import com.nexio.tv.data.remote.api.TmdbSeasonResponse
import com.nexio.tv.data.remote.api.TmdbVideosResponse
import com.nexio.tv.data.remote.api.TmdbNetworkDetailsResponse
import com.nexio.tv.data.remote.api.TmdbPersonCreditsResponse
import com.nexio.tv.data.remote.api.TmdbPersonResponse
import com.nexio.tv.data.remote.api.TmdbPersonSearchResponse
import com.nexio.tv.data.remote.api.TmdbCompanySearchResponse
import com.nexio.tv.data.repository.TmdbDiscoveryClient
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.MetaCastMember
import com.nexio.tv.domain.model.MetaCompany
import com.nexio.tv.domain.model.MetaCompanyKind
import com.nexio.tv.domain.model.MetaReview
import com.nexio.tv.domain.model.MetaReviewSource
import java.util.Locale
import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton
import retrofit2.Response
import java.time.LocalDate
import kotlinx.coroutines.CancellationException

private const val TAG = "TmdbIntegrationProvider"

@Singleton
class TmdbIntegrationProvider private constructor(
    private val runtime: IntegrationRuntime,
    private val tmdbApi: TmdbApi,
    private val tmdbCredentialProvider: suspend () -> MetadataProviderCredential,
    private val tmdbService: TmdbService?,
    private val ownershipFactory: IntegrationCacheOwnershipFactory
) : TmdbDiscoveryClient {

    @Inject
    constructor(
        runtime: IntegrationRuntime,
        tmdbApi: TmdbApi,
        metadataApiKeyResolver: MetadataApiKeyResolver,
        tmdbService: TmdbService?,
        ownershipFactory: IntegrationCacheOwnershipFactory
    ) : this(
        runtime = runtime,
        tmdbApi = tmdbApi,
        tmdbCredentialProvider = { metadataApiKeyResolver.tmdbCredential() },
        tmdbService = tmdbService,
        ownershipFactory = ownershipFactory
    )

    constructor(
        runtime: IntegrationRuntime,
        tmdbApi: TmdbApi,
        tmdbCredentialProvider: suspend () -> MetadataProviderCredential,
        ownershipFactory: IntegrationCacheOwnershipFactory = IntegrationCacheOwnershipFactory(
            RailMediaIdentityResolver()
        )
    ) : this(
        runtime = runtime,
        tmdbApi = tmdbApi,
        tmdbCredentialProvider = tmdbCredentialProvider,
        tmdbService = null,
        ownershipFactory = ownershipFactory
    )

    override suspend fun credential(): MetadataProviderCredential {
        return tmdbCredentialProvider()
    }

    override suspend fun searchMovies(
        query: String,
        preferences: TmdbCatalogPreferences
    ): List<TmdbMediaResult> {
        val credential = credential()
        if (credential.missing) return emptyList()
        val apiKey = credential.apiKey
        val includeAdult = preferences.includeAdult
        return fetchMediaResults(
            cacheKey = "tmdb:search:movie:${query.hashCode()}:adult=$includeAdult",
            apiShapeId = "tmdb.search.movie",
            operationKey = "tmdb.search_movies",
            call = { tmdbApi.searchMovies(apiKey = apiKey, query = query, includeAdult = includeAdult) }
        )
    }

    override suspend fun searchTv(
        query: String,
        preferences: TmdbCatalogPreferences
    ): List<TmdbMediaResult> {
        val credential = credential()
        if (credential.missing) return emptyList()
        val apiKey = credential.apiKey
        val includeAdult = preferences.includeAdult
        return fetchMediaResults(
            cacheKey = "tmdb:search:tv:${query.hashCode()}:adult=$includeAdult",
            apiShapeId = "tmdb.search.tv",
            operationKey = "tmdb.search_tv",
            call = { tmdbApi.searchTv(apiKey = apiKey, query = query, includeAdult = includeAdult) }
        )
    }

    override suspend fun fetchCatalog(
        catalogId: String,
        preferences: TmdbCatalogPreferences
    ): List<TmdbMediaResult> {
        val credential = credential()
        if (credential.missing) return emptyList()
        val apiKey = credential.apiKey
        val includeAdult = preferences.includeAdult
        val hideUnreleasedDigital = preferences.hideUnreleasedDigital
        val now = LocalDate.now()
        val today = now.toString()
        val currentYear = now.year
        return when (catalogId) {
            TmdbCatalogIds.TRENDING_MOVIES -> fetchMediaResults(
                cacheKey = "tmdb:catalog:trending_movies:adult=$includeAdult",
                apiShapeId = "tmdb.trending.movie",
                operationKey = "tmdb.trending_movies",
                call = { tmdbApi.getTrendingMovies(apiKey = apiKey) }
            )
            TmdbCatalogIds.TRENDING_SERIES -> fetchMediaResults(
                cacheKey = "tmdb:catalog:trending_series:adult=$includeAdult",
                apiShapeId = "tmdb.trending.tv",
                operationKey = "tmdb.trending_tv",
                call = { tmdbApi.getTrendingTv(apiKey = apiKey) }
            )
            TmdbCatalogIds.LATEST_RELEASES_MOVIES -> fetchMediaResults(
                cacheKey = "tmdb:catalog:latest_releases_movies:adult=$includeAdult:date=$today:unreleased=$hideUnreleasedDigital",
                apiShapeId = "tmdb.discover.movie",
                operationKey = "tmdb.discover_movies",
                call = {
                    tmdbApi.discoverMovies(
                        apiKey = apiKey,
                        includeAdult = includeAdult,
                        sortBy = "release_date.desc",
                        releaseDateLte = today,
                        withReleaseType = if (hideUnreleasedDigital) "4" else null
                    )
                }
            )
            TmdbCatalogIds.LATEST_RELEASES_SERIES -> fetchMediaResults(
                cacheKey = "tmdb:catalog:latest_releases_series:adult=$includeAdult:date=$today",
                apiShapeId = "tmdb.discover.tv",
                operationKey = "tmdb.discover_tv",
                call = {
                    tmdbApi.discoverTv(
                        apiKey = apiKey,
                        includeAdult = includeAdult,
                        sortBy = "first_air_date.desc",
                        firstAirDateLte = today
                    )
                }
            )
            TmdbCatalogIds.POPULAR_MOVIES -> fetchMediaResults(
                cacheKey = "tmdb:catalog:popular_movies:adult=$includeAdult",
                apiShapeId = "tmdb.popular.movie",
                operationKey = "tmdb.popular_movies",
                call = { tmdbApi.getPopularMovies(apiKey = apiKey) }
            )
            TmdbCatalogIds.POPULAR_SERIES -> fetchMediaResults(
                cacheKey = "tmdb:catalog:popular_series:adult=$includeAdult",
                apiShapeId = "tmdb.popular.tv",
                operationKey = "tmdb.popular_tv",
                call = { tmdbApi.getPopularTv(apiKey = apiKey) }
            )
            TmdbCatalogIds.YEAR_MOVIES -> fetchMediaResults(
                cacheKey = "tmdb:catalog:year_movies:adult=$includeAdult:year=$currentYear",
                apiShapeId = "tmdb.discover.movie",
                operationKey = "tmdb.discover_movies",
                call = {
                    tmdbApi.discoverMovies(
                        apiKey = apiKey,
                        includeAdult = includeAdult,
                        sortBy = "popularity.desc",
                        primaryReleaseYear = currentYear
                    )
                }
            )
            TmdbCatalogIds.YEAR_SERIES -> fetchMediaResults(
                cacheKey = "tmdb:catalog:year_series:adult=$includeAdult:year=$currentYear",
                apiShapeId = "tmdb.discover.tv",
                operationKey = "tmdb.discover_tv",
                call = {
                    tmdbApi.discoverTv(
                        apiKey = apiKey,
                        includeAdult = includeAdult,
                        sortBy = "popularity.desc",
                        firstAirDateYear = currentYear
                    )
                }
            )
            TmdbCatalogIds.LANGUAGE_MOVIES -> fetchMediaResults(
                cacheKey = "tmdb:catalog:language_movies:adult=$includeAdult:lang=en",
                apiShapeId = "tmdb.discover.movie",
                operationKey = "tmdb.discover_movies",
                call = {
                    tmdbApi.discoverMovies(
                        apiKey = apiKey,
                        includeAdult = includeAdult,
                        sortBy = "popularity.desc",
                        withOriginalLanguage = "en"
                    )
                }
            )
            TmdbCatalogIds.LANGUAGE_SERIES -> fetchMediaResults(
                cacheKey = "tmdb:catalog:language_series:adult=$includeAdult:lang=en",
                apiShapeId = "tmdb.discover.tv",
                operationKey = "tmdb.discover_tv",
                call = {
                    tmdbApi.discoverTv(
                        apiKey = apiKey,
                        includeAdult = includeAdult,
                        sortBy = "popularity.desc",
                        withOriginalLanguage = "en"
                    )
                }
            )
            else -> return emptyList()
        }
    }

    override suspend fun imdbId(tmdbId: Int, contentType: ContentType): String? {
        val mediaType = if (contentType == ContentType.SERIES || contentType == ContentType.TV) "series" else "movie"
        return tmdbService?.tmdbToImdb(tmdbId, mediaType)
    }

    suspend fun fetchEnrichment(
        tmdbId: String,
        contentType: ContentType,
        normalizedLanguage: String,
        activePosterProvider: PosterRatingsUrlResolver.ActiveProvider?
    ): TmdbEnrichment? {
        val providerToken = posterProviderCacheToken(activePosterProvider)
        val tmdbType = when (contentType) {
            ContentType.SERIES, ContentType.TV -> "tv"
            else -> "movie"
        }
        val spec = IntegrationSpec(
            provider = IntegrationProvider.TMDB,
            apiShapeId = if (tmdbType == "tv") TmdbApiShapes.TV_CORE else TmdbApiShapes.MOVIE_CORE,
            operationKey = "tmdb.fetch_enrichment",
            cacheKey = "tmdb:$tmdbType:$tmdbId:$normalizedLanguage:enrichment:$providerToken",
            codec = gsonCodec<TmdbEnrichment>(),
            cachePolicy = IntegrationCachePolicy.CacheFirst(
                ttlMs = 7L * 24L * 60L * 60L * 1000L,
                staleAfterExpiryMs = 30L * 24L * 60L * 60L * 1000L
            ),
            ownership = ownershipFactory.media(
                mediaType = contentType.toApiString(),
                rawId = "tmdb:$tmdbId",
                tmdbId = tmdbId
            ),
            workClass = IntegrationWorkClass.USER_VISIBLE,
            load = {
                loadTmdbEnrichment(
                    tmdbId = tmdbId,
                    tmdbType = tmdbType,
                    normalizedLanguage = normalizedLanguage,
                    activePosterProvider = activePosterProvider
                )
            }
        )
        return runtime.get(spec).valueOrNull()
    }

    suspend fun fetchMovieCore(
        movieId: Int,
        normalizedLanguage: String,
        activePosterProvider: PosterRatingsUrlResolver.ActiveProvider?,
        localizationPolicyVersion: Int = LocalizationPolicy.CURRENT_VERSION
    ): TmdbEnrichment? {
        val providerToken = posterProviderCacheToken(activePosterProvider)
        return runtime.get(
            IntegrationSpec(
                provider = IntegrationProvider.TMDB,
                apiShapeId = TmdbApiShapes.MOVIE_CORE,
                operationKey = "tmdb.movie.core:$movieId:$normalizedLanguage:policy:$localizationPolicyVersion",
                cacheKey = "tmdb:movie:$movieId:$normalizedLanguage:core:$providerToken:policy:$localizationPolicyVersion",
                codec = gsonCodec<TmdbEnrichment>(),
                cachePolicy = IntegrationCachePolicy.CacheFirst(
                    ttlMs = 7L * 24L * 60L * 60L * 1000L,
                    staleAfterExpiryMs = 30L * 24L * 60L * 60L * 1000L
                ),
                ownership = ownershipFactory.media(
                    mediaType = ContentType.MOVIE.toApiString(),
                    rawId = "tmdb:$movieId",
                    tmdbId = movieId.toString()
                ),
                workClass = IntegrationWorkClass.USER_VISIBLE,
                load = {
                    loadTmdbEnrichment(
                        tmdbId = movieId.toString(),
                        tmdbType = "movie",
                        normalizedLanguage = normalizedLanguage,
                        activePosterProvider = activePosterProvider
                    )
                }
            )
        ).valueOrNull()
    }

    suspend fun fetchTvCore(
        tvId: Int,
        normalizedLanguage: String,
        activePosterProvider: PosterRatingsUrlResolver.ActiveProvider?,
        localizationPolicyVersion: Int = LocalizationPolicy.CURRENT_VERSION
    ): TmdbEnrichment? {
        val providerToken = posterProviderCacheToken(activePosterProvider)
        return runtime.get(
            IntegrationSpec(
                provider = IntegrationProvider.TMDB,
                apiShapeId = TmdbApiShapes.TV_CORE,
                operationKey = "tmdb.tv.core:$tvId:$normalizedLanguage:policy:$localizationPolicyVersion",
                cacheKey = "tmdb:tv:$tvId:$normalizedLanguage:core:$providerToken:policy:$localizationPolicyVersion",
                codec = gsonCodec<TmdbEnrichment>(),
                cachePolicy = IntegrationCachePolicy.CacheFirst(
                    ttlMs = 7L * 24L * 60L * 60L * 1000L,
                    staleAfterExpiryMs = 30L * 24L * 60L * 60L * 1000L
                ),
                ownership = ownershipFactory.media(
                    mediaType = ContentType.TV.toApiString(),
                    rawId = "tmdb:$tvId",
                    tmdbId = tvId.toString()
                ),
                workClass = IntegrationWorkClass.USER_VISIBLE,
                load = {
                    loadTmdbEnrichment(
                        tmdbId = tvId.toString(),
                        tmdbType = "tv",
                        normalizedLanguage = normalizedLanguage,
                        activePosterProvider = activePosterProvider
                    )
                }
            )
        ).valueOrNull()
    }

    suspend fun fetchTvSeasonEpisodes(
        tvId: Int,
        seasonNumber: Int,
        normalizedLanguage: String,
        localizationPolicyVersion: Int = LocalizationPolicy.CURRENT_VERSION
    ): TmdbSeasonResponse? {
        val credential = credential()
        if (credential.missing) return null
        return runtime.get(
            IntegrationSpec(
                provider = IntegrationProvider.TMDB,
                apiShapeId = TmdbApiShapes.SEASON_EPISODES,
                operationKey = "tmdb.season.episodes:$tvId:$seasonNumber:$normalizedLanguage:policy:$localizationPolicyVersion",
                cacheKey = "tmdb:tv:$tvId:season:$seasonNumber:episodes:$normalizedLanguage:policy:$localizationPolicyVersion",
                codec = gsonCodec<TmdbSeasonResponse>(),
                cachePolicy = IntegrationCachePolicy.CacheFirst(24L * 60L * 60L * 1000L, 7L * 24L * 60L * 60L * 1000L),
                workClass = IntegrationWorkClass.USER_VISIBLE,
                load = {
                    loadResponse("tmdb.season.episodes") {
                        tmdbApi.getTvSeasonDetails(tvId, seasonNumber, credential.apiKey, normalizedLanguage)
                    }
                }
            )
        ).valueOrNull()
    }

    suspend fun fetchMovieVideos(movieId: Int, normalizedLanguage: String): TmdbVideosResponse? {
        val credential = credential()
        if (credential.missing) return null
        return runtime.get(
            IntegrationSpec(
                provider = IntegrationProvider.TMDB,
                apiShapeId = TmdbApiShapes.MOVIE_VIDEOS,
                operationKey = "tmdb.movie.videos",
                cacheKey = "tmdb:movie:$movieId:videos:$normalizedLanguage",
                codec = gsonCodec<TmdbVideosResponse>(),
                cachePolicy = IntegrationCachePolicy.CacheFirst(24L * 60L * 60L * 1000L, 7L * 24L * 60L * 60L * 1000L),
                workClass = IntegrationWorkClass.USER_VISIBLE,
                load = {
                    loadResponse("tmdb.movie.videos") {
                        tmdbApi.getMovieVideos(movieId, credential.apiKey, normalizedLanguage)
                    }
                }
            )
        ).valueOrNull()
    }

    suspend fun fetchTvVideos(tvId: Int, normalizedLanguage: String): TmdbVideosResponse? {
        val credential = credential()
        if (credential.missing) return null
        return runtime.get(
            IntegrationSpec(
                provider = IntegrationProvider.TMDB,
                apiShapeId = TmdbApiShapes.TV_VIDEOS,
                operationKey = "tmdb.tv.videos",
                cacheKey = "tmdb:tv:$tvId:videos:$normalizedLanguage",
                codec = gsonCodec<TmdbVideosResponse>(),
                cachePolicy = IntegrationCachePolicy.CacheFirst(24L * 60L * 60L * 1000L, 7L * 24L * 60L * 60L * 1000L),
                workClass = IntegrationWorkClass.USER_VISIBLE,
                load = {
                    loadResponse("tmdb.tv.videos") {
                        tmdbApi.getTvVideos(tvId, credential.apiKey, normalizedLanguage)
                    }
                }
            )
        ).valueOrNull()
    }

    suspend fun fetchTvDetails(tvId: Int, normalizedLanguage: String): TmdbDetailsResponse? {
        val credential = credential()
        if (credential.missing) return null
        return runtime.get(
            IntegrationSpec(
                provider = IntegrationProvider.TMDB,
                apiShapeId = TmdbApiShapes.TV_CORE,
                operationKey = "tmdb.tv.core",
                cacheKey = "tmdb:tv:$tvId:$normalizedLanguage:core",
                codec = gsonCodec<TmdbDetailsResponse>(),
                cachePolicy = IntegrationCachePolicy.CacheFirst(24L * 60L * 60L * 1000L, 7L * 24L * 60L * 60L * 1000L),
                workClass = IntegrationWorkClass.USER_VISIBLE,
                load = {
                    loadResponse("tmdb.tv.core") {
                        tmdbApi.getTvDetails(tvId, credential.apiKey, normalizedLanguage)
                    }
                }
            )
        ).valueOrNull()
    }

    suspend fun fetchSeasonVideos(
        tvId: Int,
        seasonNumber: Int,
        normalizedLanguage: String
    ): TmdbVideosResponse? {
        val credential = credential()
        if (credential.missing) return null
        return runtime.get(
            IntegrationSpec(
                provider = IntegrationProvider.TMDB,
                apiShapeId = TmdbApiShapes.SEASON_VIDEOS,
                operationKey = "tmdb.season.videos",
                cacheKey = "tmdb:tv:$tvId:season:$seasonNumber:videos:$normalizedLanguage",
                codec = gsonCodec<TmdbVideosResponse>(),
                cachePolicy = IntegrationCachePolicy.CacheFirst(24L * 60L * 60L * 1000L, 7L * 24L * 60L * 60L * 1000L),
                workClass = IntegrationWorkClass.USER_VISIBLE,
                load = {
                    loadResponse("tmdb.season.videos") {
                        tmdbApi.getTvSeasonVideos(tvId, seasonNumber, credential.apiKey, normalizedLanguage)
                    }
                }
            )
        ).valueOrNull()
    }

    suspend fun fetchMovieRecommendations(
        movieId: Int,
        normalizedLanguage: String,
        page: Int = 1
    ): TmdbRecommendationsResponse? {
        val credential = credential()
        if (credential.missing) return null
        return runtime.get(
            IntegrationSpec(
                provider = IntegrationProvider.TMDB,
                apiShapeId = TmdbApiShapes.MOVIE_RECOMMENDATIONS,
                operationKey = "tmdb.movie.recommendations",
                cacheKey = "tmdb:movie:$movieId:recommendations:$normalizedLanguage:page:$page",
                codec = gsonCodec<TmdbRecommendationsResponse>(),
                cachePolicy = IntegrationCachePolicy.CacheFirst(24L * 60L * 60L * 1000L, 7L * 24L * 60L * 60L * 1000L),
                workClass = IntegrationWorkClass.USER_VISIBLE,
                load = {
                    loadResponse("tmdb.movie.recommendations") {
                        tmdbApi.getMovieRecommendations(movieId, credential.apiKey, normalizedLanguage, page)
                    }
                }
            )
        ).valueOrNull()
    }

    suspend fun fetchTvRecommendations(
        tvId: Int,
        normalizedLanguage: String,
        page: Int = 1
    ): TmdbRecommendationsResponse? {
        val credential = credential()
        if (credential.missing) return null
        return runtime.get(
            IntegrationSpec(
                provider = IntegrationProvider.TMDB,
                apiShapeId = TmdbApiShapes.TV_RECOMMENDATIONS,
                operationKey = "tmdb.tv.recommendations",
                cacheKey = "tmdb:tv:$tvId:recommendations:$normalizedLanguage:page:$page",
                codec = gsonCodec<TmdbRecommendationsResponse>(),
                cachePolicy = IntegrationCachePolicy.CacheFirst(24L * 60L * 60L * 1000L, 7L * 24L * 60L * 60L * 1000L),
                workClass = IntegrationWorkClass.USER_VISIBLE,
                load = {
                    loadResponse("tmdb.tv.recommendations") {
                        tmdbApi.getTvRecommendations(tvId, credential.apiKey, normalizedLanguage, page)
                    }
                }
            )
        ).valueOrNull()
    }

    suspend fun fetchMovieReviews(
        movieId: Int,
        normalizedLanguage: String,
        page: Int = 1
    ): TmdbReviewsResponse? {
        val credential = credential()
        if (credential.missing) return null
        return runtime.get(
            IntegrationSpec(
                provider = IntegrationProvider.TMDB,
                apiShapeId = TmdbApiShapes.MOVIE_REVIEWS,
                operationKey = "tmdb.movie.reviews",
                cacheKey = "tmdb:movie:$movieId:reviews:$normalizedLanguage:page:$page",
                codec = gsonCodec<TmdbReviewsResponse>(),
                cachePolicy = IntegrationCachePolicy.CacheFirst(10L * 60L * 1000L, 60L * 60L * 1000L),
                workClass = IntegrationWorkClass.USER_VISIBLE,
                load = {
                    loadResponse("tmdb.movie.reviews") {
                        tmdbApi.getMovieReviews(movieId, credential.apiKey, normalizedLanguage, page)
                    }
                }
            )
        ).valueOrNull()
    }

    suspend fun fetchTvReviews(
        tvId: Int,
        normalizedLanguage: String,
        page: Int = 1
    ): TmdbReviewsResponse? {
        val credential = credential()
        if (credential.missing) return null
        return runtime.get(
            IntegrationSpec(
                provider = IntegrationProvider.TMDB,
                apiShapeId = TmdbApiShapes.TV_REVIEWS,
                operationKey = "tmdb.tv.reviews",
                cacheKey = "tmdb:tv:$tvId:reviews:$normalizedLanguage:page:$page",
                codec = gsonCodec<TmdbReviewsResponse>(),
                cachePolicy = IntegrationCachePolicy.CacheFirst(10L * 60L * 1000L, 60L * 60L * 1000L),
                workClass = IntegrationWorkClass.USER_VISIBLE,
                load = {
                    loadResponse("tmdb.tv.reviews") {
                        tmdbApi.getTvReviews(tvId, credential.apiKey, normalizedLanguage, page)
                    }
                }
            )
        ).valueOrNull()
    }

    suspend fun findByExternalId(
        externalId: String,
        externalSource: String = "imdb_id"
    ): TmdbFindResponse? {
        val credential = credential()
        if (credential.missing) return null

        return when (
            val result = runtime.call(
                IntegrationCallSpec(
                    provider = IntegrationProvider.TMDB,
                    apiShapeId = TmdbApiShapes.FIND_EXTERNAL_ID,
                    operationKey = "tmdb.find_by_external_id",
                    workClass = IntegrationWorkClass.USER_VISIBLE,
                    call = {
                        val response = runCatching {
                            tmdbApi.findByExternalId(
                                externalId = externalId,
                                apiKey = credential.apiKey,
                                externalSource = externalSource
                            )
                        }.getOrElse { exception ->
                            if (exception is CancellationException) throw exception
                            return@IntegrationCallSpec IntegrationCallResult.NetworkError(exception)
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

    suspend fun findTmdbIdByImdbId(imdbId: String, mediaType: String): Int? {
        val body = findByExternalId(imdbId, externalSource = "imdb_id") ?: return null
        return when (normalizeLookupMediaType(mediaType)) {
            "movie" -> body.movieResults?.firstOrNull()?.id
            "tv" -> body.tvResults?.firstOrNull()?.id
            else -> body.movieResults?.firstOrNull()?.id ?: body.tvResults?.firstOrNull()?.id
        }
    }

    suspend fun findImdbIdByTmdbId(tmdbId: Int, mediaType: String): String? {
        val credential = credential()
        if (credential.missing) return null

        val normalizedType = normalizeLookupMediaType(mediaType)
        val apiShapeId = when (normalizedType) {
            "tv" -> TmdbApiShapes.TV_CORE
            else -> TmdbApiShapes.MOVIE_CORE
        }
        val operationKey = "tmdb.external_ids.$normalizedType"

        return when (
            val result = runtime.call(
                IntegrationCallSpec(
                    provider = IntegrationProvider.TMDB,
                    apiShapeId = apiShapeId,
                    operationKey = operationKey,
                    workClass = IntegrationWorkClass.USER_VISIBLE,
                    call = {
                        val response = runCatching {
                            when (normalizedType) {
                                "tv" -> tmdbApi.getTvExternalIds(tmdbId, credential.apiKey)
                                else -> tmdbApi.getMovieExternalIds(tmdbId, credential.apiKey)
                            }
                        }.getOrElse { exception ->
                            if (exception is CancellationException) throw exception
                            return@IntegrationCallSpec IntegrationCallResult.NetworkError(exception)
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
            is IntegrationCallResult.Success<TmdbExternalIdsResponse> -> result.value.imdbId
            else -> null
        }
    }

    fun normalizeLookupMediaType(mediaType: String): String {
        return when (mediaType.lowercase(Locale.US)) {
            "series", "tv", "show", "tvshow" -> "tv"
            "movie", "film" -> "movie"
            else -> mediaType.lowercase(Locale.US)
        }
    }

    suspend fun loadTvSeasonEpisodes(
        tvId: Int,
        seasonNumber: Int,
        apiKey: String,
        normalizedLanguage: String
    ): IntegrationLoadResult<List<TmdbEpisode>> {
        return when (
            val result = runtime.get(
                IntegrationSpec(
                    provider = IntegrationProvider.TMDB,
                    apiShapeId = TmdbApiShapes.SEASON_EPISODES,
                    operationKey = "tmdb.season.episodes",
                    cacheKey = "tmdb:tv:$tvId:season:$seasonNumber:episodes:$normalizedLanguage",
                    codec = gsonCodec<List<TmdbEpisode>>(),
                    cachePolicy = IntegrationCachePolicy.CacheFirst(
                        ttlMs = 24L * 60L * 60L * 1000L,
                        staleAfterExpiryMs = 7L * 24L * 60L * 60L * 1000L
                    ),
                    workClass = IntegrationWorkClass.USER_VISIBLE,
                    load = {
                        try {
                            val response = tmdbApi.getTvSeasonDetails(
                                tvId = tvId,
                                seasonNumber = seasonNumber,
                                apiKey = apiKey,
                                language = normalizedLanguage
                            )
                            if (!response.isSuccessful) {
                                IntegrationLoadResult.HttpError(response.code(), reason = "tmdb_tv_season_fetch_failed")
                            } else {
                                IntegrationLoadResult.Success(response.body()?.episodes.orEmpty())
                            }
                        } catch (exception: Exception) {
                            if (exception is CancellationException) throw exception
                            IntegrationLoadResult.NetworkError(exception)
                        }
                    }
                )
            )
        ) {
            is IntegrationFetchResult.Fresh -> IntegrationLoadResult.Success(result.value)
            is IntegrationFetchResult.Updated -> IntegrationLoadResult.Success(result.value)
            is IntegrationFetchResult.Stale -> IntegrationLoadResult.Success(result.value)
            IntegrationFetchResult.Missing -> IntegrationLoadResult.HttpError(404, reason = "tmdb_tv_season_fetch_missing")
        }
    }

    suspend fun loadPersonDetails(personId: Int): IntegrationLoadResult<TmdbPersonResponse> {
        val credential = credential()
        if (credential.missing) {
            return IntegrationLoadResult.HttpError(statusCode = 401, reason = "tmdb_api_key_missing")
        }
        val result = runtime.call(
            IntegrationCallSpec(
                provider = IntegrationProvider.TMDB,
                apiShapeId = TmdbApiShapes.PERSON_DETAIL,
                operationKey = "tmdb.person.detail",
                workClass = IntegrationWorkClass.USER_VISIBLE,
                call = {
                    val response = runCatching {
                        tmdbApi.getPersonDetails(personId = personId, apiKey = credential.apiKey)
                    }.getOrElse { exception ->
                        if (exception is CancellationException) throw exception
                        return@IntegrationCallSpec IntegrationCallResult.NetworkError(exception)
                    }
                    if (!response.isSuccessful) {
                        IntegrationCallResult.HttpError(response.code(), reason = "tmdb_person_details")
                    } else {
                        response.body()?.let { IntegrationCallResult.Success(it) }
                            ?: IntegrationCallResult.HttpError(response.code(), reason = "tmdb_person_details:empty_body")
                    }
                }
            )
        )
        return result.toLoadResult(httpReason = "tmdb_person_details")
    }

    suspend fun loadPersonCombinedCredits(personId: Int): IntegrationLoadResult<TmdbPersonCreditsResponse> {
        val credential = credential()
        if (credential.missing) {
            return IntegrationLoadResult.HttpError(statusCode = 401, reason = "tmdb_api_key_missing")
        }
        val result = runtime.call(
            IntegrationCallSpec(
                provider = IntegrationProvider.TMDB,
                apiShapeId = TmdbApiShapes.PERSON_COMBINED_CREDITS,
                operationKey = "tmdb.person.combined_credits",
                workClass = IntegrationWorkClass.USER_VISIBLE,
                call = {
                    val response = runCatching {
                        tmdbApi.getPersonCombinedCredits(personId = personId, apiKey = credential.apiKey)
                    }.getOrElse { exception ->
                        if (exception is CancellationException) throw exception
                        return@IntegrationCallSpec IntegrationCallResult.NetworkError(exception)
                    }
                    if (!response.isSuccessful) {
                        IntegrationCallResult.HttpError(response.code(), reason = "tmdb_person_credits")
                    } else {
                        response.body()?.let { IntegrationCallResult.Success(it) }
                            ?: IntegrationCallResult.HttpError(response.code(), reason = "tmdb_person_credits:empty_body")
                    }
                }
            )
        )
        return result.toLoadResult(httpReason = "tmdb_person_credits")
    }

    suspend fun searchPeople(query: String): IntegrationLoadResult<TmdbPersonSearchResponse> {
        val credential = credential()
        if (credential.missing) {
            return IntegrationLoadResult.HttpError(statusCode = 401, reason = "tmdb_api_key_missing")
        }
        return loadResponse(
            request = "tmdb_search_people",
            call = {
                tmdbApi.searchPeople(
                    apiKey = credential.apiKey,
                    query = query,
                    includeAdult = false
                )
            }
        )
    }

    suspend fun searchCompanies(query: String): IntegrationLoadResult<TmdbCompanySearchResponse> {
        val credential = credential()
        if (credential.missing) {
            return IntegrationLoadResult.HttpError(statusCode = 401, reason = "tmdb_api_key_missing")
        }
        return loadResponse(
            request = "tmdb_search_companies",
            call = { tmdbApi.searchCompanies(apiKey = credential.apiKey, query = query) }
        )
    }

    suspend fun loadTmdbEnrichment(
        tmdbId: String,
        tmdbType: String,
        normalizedLanguage: String,
        activePosterProvider: PosterRatingsUrlResolver.ActiveProvider?
    ): IntegrationLoadResult<TmdbEnrichment> {
        val credential = credential()
        if (credential.missing) {
            return IntegrationLoadResult.HttpError(statusCode = 401, reason = "tmdb_api_key_missing")
        }
        val apiKey = credential.apiKey
        val numericId = tmdbId.toIntOrNull()
            ?: return IntegrationLoadResult.HttpError(statusCode = 400, reason = "invalid_tmdb_id")

        return try {
            val includeImageLanguage = buildString {
                append(normalizedLanguage.substringBefore("-"))
                append(",")
                append(normalizedLanguage)
                append(",en,null")
            }

            val response = when (tmdbType) {
                "tv" -> tmdbApi.getTvDetails(
                    tvId = numericId,
                    apiKey = apiKey,
                    language = normalizedLanguage,
                    appendToResponse = "credits,images,content_ratings",
                    includeImageLanguage = includeImageLanguage
                )

                else -> tmdbApi.getMovieDetails(
                    movieId = numericId,
                    apiKey = apiKey,
                    language = normalizedLanguage,
                    appendToResponse = "credits,images,release_dates",
                    includeImageLanguage = includeImageLanguage
                )
            }
            if (!response.isSuccessful) {
                return IntegrationLoadResult.HttpError(statusCode = response.code(), reason = "tmdb_enrichment_failed")
            }

            val details = response.body()
                ?: return IntegrationLoadResult.HttpError(response.code(), reason = "missing_body")

            val credits = details.credits
            val images = details.images
            val ageRating = when (tmdbType) {
                "tv" -> selectTvAgeRating(details.contentRatings?.results.orEmpty(), normalizedLanguage)
                else -> selectMovieAgeRating(details.releaseDates?.results.orEmpty(), normalizedLanguage)
            }

            val genres = details.genres?.mapNotNull { genre ->
                genre.name.trim().takeIf { name -> name.isNotBlank() }
            } ?: emptyList()
            val description = details.overview?.takeIf { it.isNotBlank() }
            val releaseInfo = details.releaseDate ?: details.firstAirDate
            val rating = details.voteAverage
            val runtime = details.runtime ?: details.episodeRunTime?.firstOrNull()
            val countries = details.productionCountries
                ?.mapNotNull { it.name?.trim()?.takeIf { name -> name.isNotBlank() } }
                ?.takeIf { it.isNotEmpty() }
                ?: details.originCountry?.takeIf { it.isNotEmpty() }
            val language = details.originalLanguage?.takeIf { it.isNotBlank() }
            val localizedTitle = (details.title ?: details.name)?.takeIf { it.isNotBlank() }
            val productionCompanies = details.productionCompanies
                .orEmpty()
                .mapNotNull { company ->
                    val name = company.name?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    MetaCompany(
                        tmdbId = company.id,
                        name = name,
                        logo = buildImageUrl(company.logoPath, size = "w300"),
                        kind = MetaCompanyKind.COMPANY,
                        provider = "tmdb",
                        providerId = company.id?.toString()
                    )
                }
            val networks = details.networks
                .orEmpty()
                .mapNotNull { network ->
                    val name = network.name?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    MetaCompany(
                        tmdbId = network.id,
                        name = name,
                        logo = buildImageUrl(network.logoPath, size = "w300"),
                        kind = MetaCompanyKind.NETWORK,
                        provider = "tmdb",
                        providerId = network.id?.toString()
                    )
                }
            val poster = if (activePosterProvider == null) {
                buildImageUrl(details.posterPath, size = "w500")
            } else {
                null
            }
            val backdrop = buildImageUrl(details.backdropPath, size = "w1280")

            val collectionId = details.belongsToCollection?.id
            val collectionName = details.belongsToCollection?.name

            val logoPath = images?.logos
                ?.sortedWith(
                    compareByDescending<TmdbImage> { it.iso6391 == normalizedLanguage.substringBefore("-") }
                        .thenByDescending { it.iso6391 == "en" }
                        .thenByDescending { it.iso6391 == null }
                )
                ?.firstOrNull()
                ?.filePath

            val logo = buildImageUrl(logoPath, size = "w500")

            val castMembers = credits?.cast
                .orEmpty()
                .mapNotNull { member ->
                    val name = member.name?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    MetaCastMember(
                        name = name,
                        character = member.character?.takeIf { it.isNotBlank() },
                        photo = buildImageUrl(member.profilePath, size = "w500"),
                        tmdbId = member.id,
                        provider = "tmdb",
                        providerId = member.id?.toString()
                    )
                }

            val creatorMembers = if (tmdbType == "tv") {
                details.createdBy
                    .orEmpty()
                    .mapNotNull { creator ->
                        val tmdbPersonId = creator.id ?: return@mapNotNull null
                        val name = creator.name?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                        MetaCastMember(
                            name = name,
                            character = "Creator",
                            photo = buildImageUrl(creator.profilePath, size = "w500"),
                            tmdbId = tmdbPersonId,
                            provider = "tmdb",
                            providerId = tmdbPersonId.toString()
                        )
                    }
                    .distinctBy { it.tmdbId ?: it.name.lowercase() }
            } else {
                emptyList()
            }

            val creator = if (tmdbType == "tv") {
                details.createdBy
                    .orEmpty()
                    .mapNotNull { it.name?.trim()?.takeIf { name -> name.isNotBlank() } }
            } else {
                emptyList()
            }

            val directorCrew = credits?.crew
                .orEmpty()
                .filter { it.job.equals("Director", ignoreCase = true) }

            val directorMembers = directorCrew
                .mapNotNull { member ->
                    val tmdbPersonId = member.id ?: return@mapNotNull null
                    val name = member.name?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    MetaCastMember(
                        name = name,
                        character = "Director",
                        photo = buildImageUrl(member.profilePath, size = "w500"),
                        tmdbId = tmdbPersonId,
                        provider = "tmdb",
                        providerId = tmdbPersonId.toString()
                    )
                }
                .distinctBy { it.tmdbId ?: it.name.lowercase() }

            val director = directorCrew
                .mapNotNull { it.name?.trim()?.takeIf { name -> name.isNotBlank() } }

            val writerCrew = credits?.crew
                .orEmpty()
                .filter { crew ->
                    val job = crew.job?.lowercase() ?: ""
                    job.contains("writer") || job.contains("screenplay")
                }

            val writerMembers = writerCrew
                .mapNotNull { member ->
                    val tmdbPersonId = member.id ?: return@mapNotNull null
                    val name = member.name?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    MetaCastMember(
                        name = name,
                        character = "Writer",
                        photo = buildImageUrl(member.profilePath, size = "w500"),
                        tmdbId = tmdbPersonId,
                        provider = "tmdb",
                        providerId = tmdbPersonId.toString()
                    )
                }
                .distinctBy { it.tmdbId ?: it.name.lowercase() }

            val writer = writerCrew
                .mapNotNull { it.name?.trim()?.takeIf { name -> name.isNotBlank() } }

            val hasCreator = creatorMembers.isNotEmpty() || creator.isNotEmpty()
            val hasDirector = directorMembers.isNotEmpty() || director.isNotEmpty()

            val exposedDirectorMembers = when {
                tmdbType == "tv" && hasCreator -> creatorMembers
                tmdbType != "tv" && hasDirector -> directorMembers
                else -> emptyList()
            }
            val exposedWriterMembers = when {
                tmdbType == "tv" && hasCreator -> emptyList()
                tmdbType != "tv" && hasDirector -> emptyList()
                else -> writerMembers
            }

            val exposedDirector = when {
                tmdbType == "tv" && hasCreator -> creator
                tmdbType != "tv" && hasDirector -> director
                else -> emptyList()
            }
            val exposedWriter = when {
                tmdbType == "tv" && hasCreator -> emptyList()
                tmdbType != "tv" && hasDirector -> emptyList()
                else -> writer
            }

            if (
                genres.isEmpty() && description == null && backdrop == null && logo == null &&
                poster == null && castMembers.isEmpty() && director.isEmpty() && writer.isEmpty() &&
                releaseInfo == null && rating == null && runtime == null && countries.isNullOrEmpty() && language == null &&
                productionCompanies.isEmpty() && networks.isEmpty() && ageRating == null
            ) {
                return IntegrationLoadResult.HttpError(statusCode = 404, reason = "empty_tmdb_enrichment")
            }

            IntegrationLoadResult.Success(
                TmdbEnrichment(
                    localizedTitle = localizedTitle,
                    description = description,
                    genres = genres,
                    backdrop = backdrop,
                    logo = logo,
                    poster = poster,
                    directorMembers = exposedDirectorMembers,
                    writerMembers = exposedWriterMembers,
                    castMembers = castMembers,
                    releaseInfo = releaseInfo,
                    rating = rating,
                    runtimeMinutes = runtime,
                    director = exposedDirector,
                    writer = exposedWriter,
                    productionCompanies = productionCompanies,
                    networks = networks,
                    ageRating = ageRating,
                    countries = countries,
                    language = language,
                    collectionId = collectionId,
                    collectionName = collectionName
                )
            )
        } catch (exception: Exception) {
            if (exception is CancellationException) throw exception
            Log.e(TAG, "Failed to fetch TMDB enrichment: ${exception.message}", exception)
            IntegrationLoadResult.NetworkError(exception)
        }
    }

    suspend fun loadMoreLikeThis(
        tmdbId: Int,
        tmdbType: String,
        normalizedLanguage: String
    ): IntegrationLoadResult<TmdbRecommendationsResponse> {
        val credential = credential()
        if (credential.missing) {
            return IntegrationLoadResult.HttpError(statusCode = 401, reason = "tmdb_api_key_missing")
        }
        val apiKey = credential.apiKey

        return when (tmdbType) {
            "tv" -> loadResponse(
                call = { tmdbApi.getTvRecommendations(tmdbId, apiKey, normalizedLanguage) },
                request = "tmdb_recommendations_tv"
            )

            else -> loadResponse(
                call = { tmdbApi.getMovieRecommendations(tmdbId, apiKey, normalizedLanguage) },
                request = "tmdb_recommendations_movie"
            )
        }
    }

    suspend fun loadMoreLikeThis(
        tmdbId: String,
        contentType: ContentType,
        normalizedLanguage: String
    ): IntegrationLoadResult<TmdbRecommendationsResponse> {
        val numericId = tmdbId.toIntOrNull()
            ?: return IntegrationLoadResult.HttpError(statusCode = 400, reason = "invalid_tmdb_id")

        val tmdbType = if (contentType == ContentType.SERIES || contentType == ContentType.TV) {
            "tv"
        } else {
            "movie"
        }

        return loadMoreLikeThis(
            tmdbId = numericId,
            tmdbType = tmdbType,
            normalizedLanguage = normalizedLanguage
        )
    }

    suspend fun loadMoreLikeThis(
        tmdbId: String,
        contentType: ContentType,
        normalizedLanguage: String,
        maxItems: Int
    ): IntegrationLoadResult<List<TmdbRecommendationResult>> {
        return when (val result = loadMoreLikeThis(
            tmdbId = tmdbId,
            contentType = contentType,
            normalizedLanguage = normalizedLanguage
        )) {
            is IntegrationLoadResult.Success -> IntegrationLoadResult.Success(
                filterRecommendationResults(
                    recommendationResults = result.value.results.orEmpty(),
                    normalizedLanguage = normalizedLanguage,
                    maxItems = maxItems
                )
            )
            is IntegrationLoadResult.HttpError -> result
            is IntegrationLoadResult.NetworkError -> result
        }
    }

    private fun filterRecommendationResults(
        recommendationResults: List<TmdbRecommendationResult>,
        normalizedLanguage: String,
        maxItems: Int
    ): List<TmdbRecommendationResult> {
        val languageCode = normalizedLanguage.substringBefore("-")
        val sortedResults = recommendationResults
            .filter { it.id > 0 }
            .sortedWith(
                compareByDescending<TmdbRecommendationResult> {
                    it.originalLanguage?.equals(languageCode, ignoreCase = true) == true
                }
                    .thenByDescending { it.voteCount ?: 0 }
                    .thenByDescending { it.voteAverage ?: 0.0 }
            )

        val qualityFilteredResults = sortedResults.filter { rec ->
            val voteCount = rec.voteCount ?: 0
            val voteAverage = rec.voteAverage ?: 0.0
            val localized = rec.originalLanguage?.equals(languageCode, ignoreCase = true) == true
            localized || voteCount >= 20 || voteAverage >= 6.0
        }
        return (if (qualityFilteredResults.isNotEmpty()) {
            qualityFilteredResults
        } else {
            sortedResults
        }).take(maxItems.coerceAtLeast(1))
    }

    suspend fun loadRecommendationImages(
        tmdbId: Int,
        tmdbType: String,
        includeImageLanguage: String
    ): IntegrationLoadResult<TmdbImagesResponse> {
        val credential = credential()
        if (credential.missing) {
            return IntegrationLoadResult.HttpError(statusCode = 401, reason = "tmdb_api_key_missing")
        }
        val apiKey = credential.apiKey
        return when (tmdbType) {
            "tv" -> loadResponse(
                call = { tmdbApi.getTvImages(tmdbId, apiKey, includeImageLanguage) },
                request = "tmdb_recommendation_images_tv"
            )

            else -> loadResponse(
                call = { tmdbApi.getMovieImages(tmdbId, apiKey, includeImageLanguage) },
                request = "tmdb_recommendation_images_movie"
            )
        }
    }

    suspend fun loadDetails(
        tmdbId: Int,
        tmdbType: String,
        normalizedLanguage: String
    ): IntegrationLoadResult<TmdbDetailsResponse> {
        val credential = credential()
        if (credential.missing) {
            return IntegrationLoadResult.HttpError(statusCode = 401, reason = "tmdb_api_key_missing")
        }
        val apiKey = credential.apiKey
        return when (tmdbType) {
            "tv" -> loadResponse(
                call = { tmdbApi.getTvDetails(tmdbId, apiKey, normalizedLanguage) },
                request = "tmdb_tv_details"
            )
            else -> loadResponse(
                call = { tmdbApi.getMovieDetails(tmdbId, apiKey, normalizedLanguage) },
                request = "tmdb_movie_details"
            )
        }
    }

    suspend fun loadReviews(
        tmdbId: Int,
        tmdbType: String,
        normalizedLanguage: String
    ): IntegrationLoadResult<TmdbReviewsResponse> {
        val credential = credential()
        if (credential.missing) {
            return IntegrationLoadResult.HttpError(statusCode = 401, reason = "tmdb_api_key_missing")
        }
        val apiKey = credential.apiKey

        return when (tmdbType) {
            "tv" -> loadResponse(
                call = { tmdbApi.getTvReviews(tmdbId, apiKey, normalizedLanguage) },
                request = "tmdb_reviews_tv"
            )

            else -> loadResponse(
                call = { tmdbApi.getMovieReviews(tmdbId, apiKey, normalizedLanguage) },
                request = "tmdb_reviews_movie"
            )
        }
    }

    suspend fun loadReviews(
        tmdbId: String,
        contentType: ContentType,
        normalizedLanguage: String
    ): IntegrationLoadResult<TmdbReviewsResponse> {
        val numericId = tmdbId.toIntOrNull()
            ?: return IntegrationLoadResult.HttpError(statusCode = 400, reason = "invalid_tmdb_id")

        val tmdbType = if (contentType == ContentType.SERIES || contentType == ContentType.TV) {
            "tv"
        } else {
            "movie"
        }

        return loadReviews(
            tmdbId = numericId,
            tmdbType = tmdbType,
            normalizedLanguage = normalizedLanguage
        )
    }

    suspend fun loadReviews(
        tmdbId: String,
        contentType: ContentType,
        normalizedLanguage: String,
        maxItems: Int
    ): IntegrationLoadResult<List<MetaReview>> {
        return when (val result = loadReviews(
            tmdbId = tmdbId,
            contentType = contentType,
            normalizedLanguage = normalizedLanguage
        )) {
            is IntegrationLoadResult.Success -> IntegrationLoadResult.Success(
                result.value.results
                    .orEmpty()
                    .mapNotNull { it.toMetaReview() }
                    .sortedWith(
                        compareByDescending<MetaReview> { it.updatedAt ?: it.createdAt ?: "" }
                            .thenByDescending { it.rating ?: -1.0 }
                    )
                    .take(maxItems.coerceAtLeast(1))
            )
            is IntegrationLoadResult.HttpError -> result
            is IntegrationLoadResult.NetworkError -> result
        }
    }

    suspend fun loadMovieCollection(
        collectionId: Int,
        normalizedLanguage: String
    ): IntegrationLoadResult<TmdbCollectionResponse> {
        val credential = credential()
        if (credential.missing) {
            return IntegrationLoadResult.HttpError(statusCode = 401, reason = "tmdb_api_key_missing")
        }
        return loadResponse(
            call = { tmdbApi.getCollectionDetails(collectionId, credential.apiKey, normalizedLanguage) },
            request = "tmdb_collection"
        )
    }

    suspend fun fetchCompanyDetails(entityId: Int): TmdbCompanyDetailsResponse? {
        return tmdbRuntimeGet(
            apiShapeId = TmdbApiShapes.COMPANY_DETAIL,
            operationKey = "tmdb.company.detail",
            cacheKey = "tmdb:company:$entityId:detail",
            codec = gsonCodec<TmdbCompanyDetailsResponse>(),
            load = { apiKey -> tmdbApi.getCompanyDetails(entityId, apiKey) }
        )
    }

    suspend fun discoverMoviesByCompany(entityId: Int, language: String): TmdbDiscoverResponse? {
        return tmdbRuntimeGet(
            apiShapeId = TmdbApiShapes.DISCOVER_MOVIE_BY_COMPANY,
            operationKey = "tmdb.discover.movie.by_company",
            cacheKey = "tmdb:discover:movie:company:$entityId:$language",
            codec = gsonCodec<TmdbDiscoverResponse>(),
            load = { apiKey ->
                tmdbApi.discoverMoviesByCompany(
                    apiKey = apiKey,
                    language = language,
                    companyIds = entityId.toString()
                )
            }
        )
    }

    suspend fun discoverTvByCompany(entityId: Int, language: String): TmdbDiscoverResponse? {
        return tmdbRuntimeGet(
            apiShapeId = TmdbApiShapes.DISCOVER_TV_BY_COMPANY_OR_NETWORK,
            operationKey = "tmdb.discover.tv.by_company",
            cacheKey = "tmdb:discover:tv:company:$entityId:$language",
            codec = gsonCodec<TmdbDiscoverResponse>(),
            load = { apiKey ->
                tmdbApi.discoverTvByCompany(
                    apiKey = apiKey,
                    language = language,
                    companyIds = entityId.toString()
                )
            }
        )
    }

    suspend fun fetchNetworkDetails(entityId: Int): TmdbNetworkDetailsResponse? {
        return tmdbRuntimeGet(
            apiShapeId = TmdbApiShapes.NETWORK_DETAIL,
            operationKey = "tmdb.network.detail",
            cacheKey = "tmdb:network:$entityId:detail",
            codec = gsonCodec<TmdbNetworkDetailsResponse>(),
            load = { apiKey -> tmdbApi.getNetworkDetails(entityId, apiKey) }
        )
    }

    suspend fun discoverTvByNetwork(entityId: Int, language: String): TmdbDiscoverResponse? {
        return tmdbRuntimeGet(
            apiShapeId = TmdbApiShapes.DISCOVER_TV_BY_COMPANY_OR_NETWORK,
            operationKey = "tmdb.discover.tv.by_network",
            cacheKey = "tmdb:discover:tv:network:$entityId:$language",
            codec = gsonCodec<TmdbDiscoverResponse>(),
            load = { apiKey ->
                tmdbApi.discoverTvByNetwork(
                    apiKey = apiKey,
                    language = language,
                    networkId = entityId
                )
            }
        )
    }

    private suspend fun <T : Any> tmdbRuntimeGet(
        apiShapeId: String,
        operationKey: String,
        cacheKey: String,
        codec: IntegrationCodec<T>,
        load: suspend (apiKey: String) -> Response<T>
    ): T? {
        val credential = credential()
        if (credential.missing) return null

        return runtime.get(
            IntegrationSpec(
                provider = IntegrationProvider.TMDB,
                apiShapeId = apiShapeId,
                operationKey = operationKey,
                cacheKey = cacheKey,
                codec = codec,
                cachePolicy = IntegrationCachePolicy.CacheFirst(
                    ttlMs = 24L * 60L * 60L * 1000L,
                    staleAfterExpiryMs = 7L * 24L * 60L * 60L * 1000L
                ),
                workClass = IntegrationWorkClass.USER_VISIBLE,
                load = {
                    loadResponse(
                        request = operationKey,
                        call = { load(credential.apiKey) }
                    )
                }
            )
        ).valueOrNull()
    }

    private fun <T> IntegrationCallResult<T>.toLoadResult(httpReason: String): IntegrationLoadResult<T> =
        when (this) {
            is IntegrationCallResult.Success -> IntegrationLoadResult.Success(value)
            is IntegrationCallResult.HttpError -> IntegrationLoadResult.HttpError(statusCode, reason = reason ?: httpReason)
            is IntegrationCallResult.NetworkError -> IntegrationLoadResult.NetworkError(throwable)
            IntegrationCallResult.Missing -> IntegrationLoadResult.HttpError(404, reason = "$httpReason:missing")
        }

    private suspend fun <T : Any> loadResponse(
        request: String,
        call: suspend () -> Response<T>
    ): IntegrationLoadResult<T> {
        return try {
            val response = call()
            if (!response.isSuccessful) {
                IntegrationLoadResult.HttpError(response.code(), reason = request)
            } else {
                response.body()?.let { IntegrationLoadResult.Success(it) }
                    ?: IntegrationLoadResult.HttpError(response.code(), reason = "$request:empty_body")
            }
        } catch (exception: Exception) {
            when (exception) {
                is CancellationException -> throw exception
                else -> IntegrationLoadResult.NetworkError(exception)
            }
        }
    }

    private suspend fun fetchMediaResults(
        cacheKey: String,
        apiShapeId: String,
        operationKey: String,
        call: suspend () -> Response<TmdbPagedMediaResponse>
    ): List<TmdbMediaResult> {
        val spec = IntegrationSpec(
            provider = IntegrationProvider.TMDB,
            apiShapeId = apiShapeId,
            operationKey = operationKey,
            cacheKey = cacheKey,
            codec = gsonCodec<TmdbPagedMediaResponse>(),
            cachePolicy = IntegrationCachePolicy.Disabled,
            workClass = IntegrationWorkClass.USER_VISIBLE,
            load = {
                runCatching { call() }
                    .fold(
                        onSuccess = { response ->
                            if (!response.isSuccessful) {
                                IntegrationLoadResult.HttpError(response.code())
                            } else {
                                IntegrationLoadResult.Success(response.body() ?: TmdbPagedMediaResponse())
                            }
                        },
                        onFailure = { exception ->
                            when (exception) {
                                is CancellationException -> throw exception
                                else -> IntegrationLoadResult.NetworkError(exception)
                            }
                        }
                    )
            }
        )

        return runtime.get(spec).valueOrNull()?.results.orEmpty()
    }

    suspend fun validateApiKey(apiKey: String): Boolean {
        val credentialHash = credentialHash(IntegrationProvider.TMDB, apiKey)
        val spec = IntegrationSpec(
            provider = IntegrationProvider.TMDB,
            apiShapeId = TmdbApiShapes.VALIDATE_KEY,
            operationKey = "tmdb.validate_api_key",
            cacheKey = "tmdb:validate:credentialHash:$credentialHash",
            codec = StringIntegrationCodec,
            cachePolicy = IntegrationCachePolicy.Disabled,
            workClass = IntegrationWorkClass.USER_VISIBLE,
            load = {
                runCatching { tmdbApi.getConfiguration(apiKey) }
                    .fold(
                        onSuccess = { IntegrationLoadResult.Success(it.isSuccessful.toString()) },
                        onFailure = { exception ->
                            when (exception) {
                                is CancellationException -> throw exception
                                else -> IntegrationLoadResult.NetworkError(exception)
                            }
                        }
                    )
            }
        )
        return runtime.get(spec).valueOrNull()?.toBoolean() == true
    }
}

private fun buildImageUrl(path: String?, size: String): String? {
    val clean = path?.trim()?.takeIf { it.isNotBlank() } ?: return null
    return "https://image.tmdb.org/t/p/$size$clean"
}

private fun posterProviderCacheToken(
    activeProvider: PosterRatingsUrlResolver.ActiveProvider?
): String {
    if (activeProvider == null) return "native"
    return "${activeProvider.provider.name}:${activeProvider.apiKey.hashCode()}"
}

private fun TmdbReviewResult.toMetaReview(): MetaReview? {
    val text = content?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val fallbackAuthor = author?.trim()?.takeIf { it.isNotBlank() } ?: "TMDB user"
    val authorName = authorDetails?.name?.trim()?.takeIf { it.isNotBlank() }
        ?: authorDetails?.username?.trim()?.takeIf { it.isNotBlank() }
        ?: fallbackAuthor

    return MetaReview(
        id = id,
        author = authorName,
        content = text,
        rating = authorDetails?.rating,
        createdAt = createdAt?.takeIf { it.isNotBlank() },
        updatedAt = updatedAt?.takeIf { it.isNotBlank() },
        url = url?.takeIf { it.isNotBlank() },
        source = MetaReviewSource.TMDB
    )
}

private fun preferredRegions(normalizedLanguage: String): List<String> {
    val fromLanguage = normalizedLanguage.substringAfter("-", "").uppercase(Locale.US).takeIf { it.length == 2 }
    return buildList {
        if (!fromLanguage.isNullOrBlank()) {
            add(fromLanguage)
        }
        add("US")
        add("GB")
    }.distinct()
}

private fun selectMovieAgeRating(
    countries: List<com.nexio.tv.data.remote.api.TmdbMovieReleaseDateCountry>,
    normalizedLanguage: String
): String? {
    val preferred = preferredRegions(normalizedLanguage)
    val byRegion = countries.associateBy { it.iso31661?.uppercase(Locale.US) }
    preferred.forEach { region ->
        val rating = byRegion[region]
            ?.releaseDates
            .orEmpty()
            .mapNotNull { it.certification?.trim() }
            .firstOrNull { it.isNotBlank() }
        if (!rating.isNullOrBlank()) return rating
    }
    return countries
        .asSequence()
        .flatMap { it.releaseDates.orEmpty().asSequence() }
        .mapNotNull { it.certification?.trim() }
        .firstOrNull { it.isNotBlank() }
}

private fun selectTvAgeRating(
    ratings: List<com.nexio.tv.data.remote.api.TmdbTvContentRatingItem>,
    normalizedLanguage: String
): String? {
    val preferred = preferredRegions(normalizedLanguage)
    val byRegion = ratings.associateBy { it.iso31661?.uppercase(Locale.US) }
    preferred.forEach { region ->
        val rating = byRegion[region]?.rating?.trim()
        if (!rating.isNullOrBlank()) return rating
    }
    return ratings
        .mapNotNull { it.rating?.trim() }
        .firstOrNull { it.isNotBlank() }
}
