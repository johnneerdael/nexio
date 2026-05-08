package com.nexio.tv.core.tmdb

import android.content.Context
import android.util.Log
import com.nexio.tv.core.integration.IntegrationLoadResult
import com.nexio.tv.core.locale.AppLocaleResolver
import com.nexio.tv.core.poster.PosterRatingsUrlResolver
import com.nexio.tv.data.integration.tmdb.TmdbIntegrationProvider
import com.nexio.tv.data.local.MetadataDiskCacheStore
import com.nexio.tv.data.remote.api.TmdbEpisode
import com.nexio.tv.data.remote.api.TmdbImage
import com.nexio.tv.data.remote.api.TmdbPersonCreditCast
import com.nexio.tv.data.remote.api.TmdbPersonCreditCrew
import com.nexio.tv.data.remote.api.TmdbRecommendationResult
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.MetaCastMember
import com.nexio.tv.domain.model.MetaCompany
import com.nexio.tv.domain.model.MetaCompanyKind
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.MetaReview
import com.nexio.tv.domain.model.PersonDetail
import com.nexio.tv.domain.model.PosterShape
import com.nexio.tv.domain.model.TitleRatingSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import dagger.hilt.android.qualifiers.ApplicationContext

private const val TAG = "TmdbMetadataService"

@Singleton
class TmdbMetadataService @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val posterRatingsUrlResolver: PosterRatingsUrlResolver,
    private val metadataDiskCacheStore: MetadataDiskCacheStore,
    private val tmdbIntegrationProvider: TmdbIntegrationProvider
) {
    // In-memory caches
    private val enrichmentCache = ConcurrentHashMap<String, TmdbEnrichment>()
    private val episodeSeasonCache = ConcurrentHashMap<String, Map<Int, TmdbEpisodeEnrichment>>()
    private val episodeSeasonInFlight = ConcurrentHashMap<String, CompletableDeferred<Map<Int, TmdbEpisodeEnrichment>?>>()
    private val personCache = ConcurrentHashMap<String, PersonDetail>()
    private val moreLikeThisCache = ConcurrentHashMap<String, List<MetaPreview>>()
    private val reviewsCache = ConcurrentHashMap<String, List<MetaReview>>()

    suspend fun fetchEnrichment(
        tmdbId: String,
        contentType: ContentType,
        language: String? = null
    ): TmdbEnrichment? =
        withContext(Dispatchers.IO) {
            val normalizedLanguage = normalizeTmdbLanguage(language ?: currentTmdbLanguageTag())
            val activePosterProvider = posterRatingsUrlResolver.getActiveProvider()
            tmdbIntegrationProvider.fetchEnrichment(
                tmdbId = tmdbId,
                contentType = contentType,
                normalizedLanguage = normalizedLanguage,
                activePosterProvider = activePosterProvider
            )
        }

    /**
     * Fetches the raw episode list for a single season from TMDB.
     * Returns episodes in season order with [TmdbEpisode.airDate] populated so
     * callers can apply [com.nexio.tv.data.repository.AirDateGate.isAired].
     */
    suspend fun fetchSeasonEpisodes(
        tvId: Int,
        seasonNumber: Int,
        language: String? = null
    ): List<TmdbEpisode> = withContext(Dispatchers.IO) {
        val normalizedLanguage = normalizeTmdbLanguage(language ?: currentTmdbLanguageTag())
        val apiKey = requireApiKey() ?: return@withContext emptyList()

        try {
            when (val seasonResponse = tmdbIntegrationProvider.loadTvSeasonEpisodes(
                tvId = tvId,
                seasonNumber = seasonNumber,
                apiKey = apiKey,
                normalizedLanguage = normalizedLanguage
            )) {
                is IntegrationLoadResult.Success -> seasonResponse.value
                is IntegrationLoadResult.NetworkError -> {
                    Log.w(
                        TAG,
                        "fetchSeasonEpisodes failed for tvId=$tvId season=$seasonNumber: ${seasonResponse.throwable.message}"
                    )
                    emptyList()
                }
                is IntegrationLoadResult.HttpError -> emptyList()
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "fetchSeasonEpisodes failed for tvId=$tvId season=$seasonNumber: ${e.message}")
            emptyList()
        }
    }

    suspend fun fetchEpisodeEnrichment(
        tmdbId: String,
        seasonNumbers: List<Int>,
        language: String? = null
    ): Map<Pair<Int, Int>, TmdbEpisodeEnrichment> = withContext(Dispatchers.IO) {
        val normalizedLanguage = normalizeTmdbLanguage(language ?: currentTmdbLanguageTag())
        val apiKey = requireApiKey() ?: return@withContext emptyMap()

        val numericId = tmdbId.toIntOrNull() ?: return@withContext emptyMap()
        val distinctSeasons = seasonNumbers.distinct().sorted()
        if (distinctSeasons.isEmpty()) return@withContext emptyMap()

        val cachedSeasonData = mutableMapOf<Int, Map<Int, TmdbEpisodeEnrichment>>()
        val missingSeasons = mutableListOf<Int>()

        distinctSeasons.forEach { season ->
            val cacheKey = episodeSeasonCacheKey(tmdbId, season, normalizedLanguage)
            val cached = episodeSeasonCache[cacheKey]
            if (cached != null) {
                cachedSeasonData[season] = cached
            } else {
                missingSeasons += season
            }
        }

        val fetchedSeasonData = if (missingSeasons.isNotEmpty()) {
            val semaphore = Semaphore(MAX_CONCURRENT_SEASON_REQUESTS)
            coroutineScope {
                missingSeasons.map { season ->
                    async {
                        semaphore.withPermit {
                            season to fetchSeasonEpisodeEnrichment(
                                tmdbId = tmdbId,
                                numericId = numericId,
                                seasonNumber = season,
                                apiKey = apiKey,
                                language = normalizedLanguage
                            )
                        }
                    }
                }.awaitAll().toMap()
            }
        } else {
            emptyMap()
        }

        buildMap {
            distinctSeasons.forEach { season ->
                val seasonEpisodes = cachedSeasonData[season] ?: fetchedSeasonData[season] ?: return@forEach
                seasonEpisodes.forEach { (episodeNumber, enrichment) ->
                    put(season to episodeNumber, enrichment)
                }
            }
        }
    }

    private suspend fun fetchSeasonEpisodeEnrichment(
        tmdbId: String,
        numericId: Int,
        seasonNumber: Int,
        apiKey: String,
        language: String
    ): Map<Int, TmdbEpisodeEnrichment>? {
        val cacheKey = episodeSeasonCacheKey(tmdbId, seasonNumber, language)
        episodeSeasonCache[cacheKey]?.let { return it }
        episodeSeasonInFlight[cacheKey]?.let { return it.await() }

        val deferred = CompletableDeferred<Map<Int, TmdbEpisodeEnrichment>?>()
        val existingDeferred = episodeSeasonInFlight.putIfAbsent(cacheKey, deferred)
        if (existingDeferred != null) {
            return existingDeferred.await()
        }

        return try {
            when (val seasonResponse = tmdbIntegrationProvider.loadTvSeasonEpisodes(
                tvId = numericId,
                seasonNumber = seasonNumber,
                apiKey = apiKey,
                normalizedLanguage = language
            )) {
                is IntegrationLoadResult.Success -> {
                    val seasonData = seasonResponse.value
                        .mapNotNull { episode ->
                            val episodeNumber = episode.episodeNumber ?: return@mapNotNull null
                            episodeNumber to episode.toEnrichment()
                        }
                        .toMap()
                    episodeSeasonCache[cacheKey] = seasonData
                    deferred.complete(seasonData)
                    seasonData
                }

                is IntegrationLoadResult.NetworkError -> {
                    Log.w(
                        TAG,
                        "Failed to fetch TMDB season $seasonNumber: ${seasonResponse.throwable.message}"
                    )
                    deferred.complete(null)
                    null
                }

                is IntegrationLoadResult.HttpError -> {
                    Log.w(
                        TAG,
                        "Failed to fetch TMDB season $seasonNumber: HTTP ${seasonResponse.statusCode}"
                    )
                    deferred.complete(null)
                    null
                }
            }
        } catch (e: CancellationException) {
            deferred.completeExceptionally(e)
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch TMDB season $seasonNumber: ${e.message}")
            deferred.complete(null)
            null
        } finally {
            episodeSeasonInFlight.remove(cacheKey, deferred)
        }
    }

    private fun episodeSeasonCacheKey(
        tmdbId: String,
        seasonNumber: Int,
        language: String
    ): String = "$tmdbId:$seasonNumber:$language"

    suspend fun fetchMoreLikeThis(
        tmdbId: String,
        contentType: ContentType,
        language: String? = null,
        maxItems: Int = 12
    ): List<MetaPreview> = withContext(Dispatchers.IO) {
        val normalizedLanguage = normalizeTmdbLanguage(language ?: currentTmdbLanguageTag())
        val activePosterProvider = posterRatingsUrlResolver.getActiveProvider()
        val providerToken = posterProviderCacheToken(activePosterProvider)
        val cacheKey = "$tmdbId:${contentType.name}:$normalizedLanguage:more_like:$providerToken"
        moreLikeThisCache[cacheKey]?.let { return@withContext it }
        requireApiKey() ?: return@withContext emptyList()

        val tmdbType = when (contentType) {
            ContentType.SERIES, ContentType.TV -> "tv"
            else -> "movie"
        }

        val includeImageLanguage = buildString {
            append(normalizedLanguage.substringBefore("-"))
            append(",")
            append(normalizedLanguage)
            append(",en,null")
        }

        try {
            val recommendationResults = when (val result = tmdbIntegrationProvider.loadMoreLikeThis(
                tmdbId = tmdbId,
                contentType = contentType,
                normalizedLanguage = normalizedLanguage,
                maxItems = maxItems
            )) {
                is IntegrationLoadResult.Success -> result.value
                is IntegrationLoadResult.HttpError -> {
                    Log.w(
                        TAG,
                        "Failed to fetch recommendations for $tmdbId: HTTP ${result.statusCode}"
                    )
                    return@withContext emptyList()
                }
                is IntegrationLoadResult.NetworkError -> {
                    Log.w(
                        TAG,
                        "Failed to fetch recommendations for $tmdbId: ${result.throwable.message}"
                    )
                    return@withContext emptyList()
                }
            }

            val items = coroutineScope {
                recommendationResults.map { rec ->
                    async {
                        val recTmdbType = when (rec.mediaType?.trim()?.lowercase()) {
                            "tv" -> "tv"
                            "movie" -> "movie"
                            else -> tmdbType
                        }
                        val recContentType = if (recTmdbType == "tv") ContentType.SERIES else ContentType.MOVIE
                        val title = rec.title?.takeIf { it.isNotBlank() }
                            ?: rec.name?.takeIf { it.isNotBlank() }
                            ?: rec.originalTitle?.takeIf { it.isNotBlank() }
                            ?: rec.originalName?.takeIf { it.isNotBlank() }
                            ?: return@async null

                        val localizedBackdropPath = when (val imageResult = tmdbIntegrationProvider.loadRecommendationImages(
                            tmdbId = rec.id,
                            tmdbType = recTmdbType,
                            includeImageLanguage = includeImageLanguage
                        )) {
                            is IntegrationLoadResult.Success -> {
                                selectBestLocalizedImagePath(
                                    images = imageResult.value.backdrops.orEmpty(),
                                    normalizedLanguage = normalizedLanguage
                                )
                            }
                            is IntegrationLoadResult.HttpError -> null
                            is IntegrationLoadResult.NetworkError -> null
                        }

                        val backdrop = buildImageUrl(localizedBackdropPath ?: rec.backdropPath, size = "w1280")
                        val fallbackPoster = buildImageUrl(rec.posterPath, size = "w780")

                        val releaseInfo = if (recTmdbType == "tv") {
                            val startYear = rec.firstAirDate?.take(4)
                            if (startYear != null) {
                                val details = when (val detailsResult = tmdbIntegrationProvider.loadDetails(
                                    tmdbId = rec.id,
                                    tmdbType = "tv",
                                    normalizedLanguage = normalizedLanguage
                                )) {
                                    is IntegrationLoadResult.Success -> detailsResult.value
                                    is IntegrationLoadResult.HttpError -> null
                                    is IntegrationLoadResult.NetworkError -> null
                                }
                                val status = details?.status
                                val endYear = details?.lastAirDate?.take(4)
                                buildShowYearRange(startYear, endYear, status)
                            } else null
                        } else {
                            rec.releaseDate?.take(4)
                        }

                        val basePreview = MetaPreview(
                            id = "tmdb:${rec.id}",
                            type = recContentType,
                            name = title,
                            poster = backdrop ?: fallbackPoster,
                            posterShape = PosterShape.LANDSCAPE,
                            background = backdrop,
                            logo = null,
                            description = rec.overview?.takeIf { it.isNotBlank() },
                            releaseInfo = releaseInfo,
                            imdbRating = rec.voteAverage?.toFloat(),
                            genres = emptyList(),
                            language = rec.originalLanguage?.takeIf { it.isNotBlank() }
                        )
                        posterRatingsUrlResolver.apply(basePreview, activePosterProvider)
                    }
                }.awaitAll().filterNotNull()
            }

            moreLikeThisCache[cacheKey] = items
            items
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "Failed to fetch recommendations for $tmdbId: ${e.message}")
            emptyList()
        }
    }

    suspend fun fetchReviews(
        tmdbId: String,
        contentType: ContentType,
        language: String? = null,
        maxItems: Int = 20
    ): List<MetaReview> = withContext(Dispatchers.IO) {
        val normalizedLanguage = normalizeTmdbLanguage(language ?: currentTmdbLanguageTag())
        val cacheKey = "$tmdbId:${contentType.name}:$normalizedLanguage:reviews:$maxItems"
        reviewsCache[cacheKey]?.let { return@withContext it }
        requireApiKey() ?: return@withContext emptyList()

        try {
            val response = when (val result = tmdbIntegrationProvider.loadReviews(
                tmdbId = tmdbId,
                contentType = contentType,
                normalizedLanguage = normalizedLanguage,
                maxItems = maxItems
            )) {
                is IntegrationLoadResult.Success -> result.value
                is IntegrationLoadResult.HttpError -> {
                    Log.w(
                        TAG,
                        "Failed to fetch reviews for $tmdbId: HTTP ${result.statusCode}"
                    )
                    return@withContext emptyList()
                }
                is IntegrationLoadResult.NetworkError -> {
                    Log.w(
                        TAG,
                        "Failed to fetch reviews for $tmdbId: ${result.throwable.message}"
                    )
                    return@withContext emptyList()
                }
            }

            reviewsCache[cacheKey] = response
            response
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "Failed to fetch reviews for $tmdbId: ${e.message}")
            emptyList()
        }
    }

    private val collectionCache = ConcurrentHashMap<String, List<MetaPreview>>()

    suspend fun fetchMovieCollection(
        collectionId: Int,
        language: String? = null
    ): List<MetaPreview> = withContext(Dispatchers.IO) {
        val normalizedLanguage = normalizeTmdbLanguage(language ?: currentTmdbLanguageTag())
        val activePosterProvider = posterRatingsUrlResolver.getActiveProvider()
        val providerToken = posterProviderCacheToken(activePosterProvider)
        val cacheKey = "$collectionId:$normalizedLanguage:collection:$providerToken"
        collectionCache[cacheKey]?.let { return@withContext it }

        try {
            val rawParts = when (val result = tmdbIntegrationProvider.loadMovieCollection(
                collectionId = collectionId,
                normalizedLanguage = normalizedLanguage
            )) {
                is IntegrationLoadResult.Success -> result.value.parts.orEmpty()
                is IntegrationLoadResult.HttpError -> {
                    Log.w(
                        TAG,
                        "Failed to fetch collection for $collectionId: HTTP ${result.statusCode}"
                    )
                    return@withContext emptyList()
                }
                is IntegrationLoadResult.NetworkError -> {
                    Log.w(
                        TAG,
                        "Failed to fetch collection for $collectionId: ${result.throwable.message}"
                    )
                    return@withContext emptyList()
                }
            }
            
            // Show in release order
            val sortedParts = rawParts.sortedBy { it.releaseDate ?: "9999" }
            
            val includeImageLanguage = buildString {
                append(normalizedLanguage.substringBefore("-"))
                append(",")
                append(normalizedLanguage)
                append(",en,null")
            }

            val items = coroutineScope {
                sortedParts.map { part ->
                    async {
                        val title = part.title ?: return@async null

                        val localizedBackdropPath = when (val imageResult = tmdbIntegrationProvider.loadRecommendationImages(
                            tmdbId = part.id,
                            tmdbType = "movie",
                            includeImageLanguage = includeImageLanguage
                        )) {
                            is IntegrationLoadResult.Success -> {
                                selectBestLocalizedImagePath(
                                    images = imageResult.value.backdrops.orEmpty(),
                                    normalizedLanguage = normalizedLanguage
                                )
                            }
                            is IntegrationLoadResult.HttpError -> null
                            is IntegrationLoadResult.NetworkError -> null
                        }

                        val backdrop = buildImageUrl(localizedBackdropPath ?: part.backdropPath, size = "w1280")
                        val fallbackPoster = buildImageUrl(part.posterPath, size = "w780")
                        val releaseInfo = part.releaseDate?.take(4)

                        val basePreview = MetaPreview(
                            id = "tmdb:${part.id}",
                            type = ContentType.MOVIE,
                            name = title,
                            poster = backdrop ?: fallbackPoster,
                            posterShape = PosterShape.LANDSCAPE,
                            background = backdrop,
                            logo = null,
                            description = part.overview?.takeIf { it.isNotBlank() },
                            releaseInfo = releaseInfo,
                            imdbRating = part.voteAverage?.toFloat(),
                            genres = emptyList()
                        )
                        posterRatingsUrlResolver.apply(basePreview, activePosterProvider)
                    }
                }.awaitAll().filterNotNull()
            }
            collectionCache[cacheKey] = items
            items
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch collection for $collectionId: ${e.message}")
            emptyList()
        }
    }

    private fun buildShowYearRange(startYear: String, endYear: String?, status: String?): String {
        val isEnded = status != null && status != "Returning Series" && status != "In Production"
        return when {
            isEnded && endYear != null && endYear != startYear -> "$startYear - $endYear"
            isEnded -> startYear
            else -> "$startYear - "
        }
    }

    private fun buildImageUrl(path: String?, size: String): String? {
        val clean = path?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return "https://image.tmdb.org/t/p/$size$clean"
    }

    private fun normalizeTmdbLanguage(language: String?): String {
        return language
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.replace('_', '-')
            ?: "en-US"
    }

    fun currentTmdbLanguageTag(): String {
        return AppLocaleResolver.resolveTmdbLanguageTag(appContext)
    }

    private suspend fun requireApiKey(): String? {
        val credential = tmdbIntegrationProvider.credential()
        if (credential.missing) {
            Log.w(TAG, "TMDB API key is missing; metadata request skipped")
            return null
        }
        return credential.apiKey
    }

    private fun posterProviderCacheToken(
        activeProvider: PosterRatingsUrlResolver.ActiveProvider?
    ): String {
        if (activeProvider == null) return "native"
        return "${activeProvider.provider.name}:${activeProvider.apiKey.hashCode()}"
    }

    private fun selectBestLocalizedImagePath(
        images: List<TmdbImage>,
        normalizedLanguage: String
    ): String? {
        if (images.isEmpty()) return null
        val languageCode = normalizedLanguage.substringBefore("-")
        return images
            .sortedWith(
                compareByDescending<TmdbImage> { it.iso6391 == normalizedLanguage }
                    .thenByDescending { it.iso6391 == languageCode }
                    .thenByDescending { it.iso6391 == "en" }
                    .thenByDescending { it.iso6391 == null }
            )
            .firstOrNull()
            ?.filePath
    }

    suspend fun fetchPersonDetail(
        personId: Int,
        preferCrewCredits: Boolean? = null
    ): PersonDetail? =
        withContext(Dispatchers.IO) {
            val cacheKey = "$personId:${preferCrewCredits?.toString() ?: "auto"}"
            personCache[cacheKey]?.let { return@withContext it }
            val apiKey = requireApiKey() ?: return@withContext null

            try {
                val (person, credits) = coroutineScope {
                    val personDeferred = async {
                        tmdbIntegrationProvider.loadPersonDetails(personId = personId)
                    }
                    val creditsDeferred = async {
                        tmdbIntegrationProvider.loadPersonCombinedCredits(personId = personId)
                    }
                    val personResult = personDeferred.await()
                    val creditsResult = creditsDeferred.await()
                    val person = when (personResult) {
                        is IntegrationLoadResult.Success -> personResult.value
                        else -> null
                    }
                    val credits = when (creditsResult) {
                        is IntegrationLoadResult.Success -> creditsResult.value
                        else -> null
                    }
                    Pair(person, credits)
                }

                if (person == null) return@withContext null

                val preferCrewFilmography = preferCrewCredits ?: shouldPreferCrewCredits(person.knownForDepartment)

                val castMovieCredits = mapMovieCreditsFromCast(credits?.cast.orEmpty())
                val crewMovieCredits = mapMovieCreditsFromCrew(credits?.crew.orEmpty())
                val movieCredits = when {
                    preferCrewFilmography && crewMovieCredits.isNotEmpty() -> crewMovieCredits
                    castMovieCredits.isNotEmpty() -> castMovieCredits
                    else -> crewMovieCredits
                }

                val castTvCredits = mapTvCreditsFromCast(credits?.cast.orEmpty())
                val crewTvCredits = mapTvCreditsFromCrew(credits?.crew.orEmpty())
                val tvCredits = when {
                    preferCrewFilmography && crewTvCredits.isNotEmpty() -> crewTvCredits
                    castTvCredits.isNotEmpty() -> castTvCredits
                    else -> crewTvCredits
                }

                val detail = PersonDetail(
                    tmdbId = person.id,
                    name = person.name ?: "Unknown",
                    biography = person.biography?.takeIf { it.isNotBlank() },
                    birthday = person.birthday?.takeIf { it.isNotBlank() },
                    deathday = person.deathday?.takeIf { it.isNotBlank() },
                    placeOfBirth = person.placeOfBirth?.takeIf { it.isNotBlank() },
                    profilePhoto = buildImageUrl(person.profilePath, "w500"),
                    knownFor = person.knownForDepartment?.takeIf { it.isNotBlank() },
                    movieCredits = movieCredits,
                    tvCredits = tvCredits
                )
                personCache[cacheKey] = detail
                detail
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch person detail: ${e.message}", e)
                null
            }
        }

    private fun shouldPreferCrewCredits(knownForDepartment: String?): Boolean {
        val department = knownForDepartment?.trim()?.lowercase() ?: return false
        if (department.isBlank()) return false
        return department != "acting" && department != "actors"
    }

    fun clearCache() {
        enrichmentCache.clear()
        episodeSeasonCache.clear()
        episodeSeasonInFlight.clear()
        personCache.clear()
        moreLikeThisCache.clear()
        reviewsCache.clear()
        collectionCache.clear()
        Log.d(TAG, "Metadata cache cleared")
    }

    suspend fun findPersonIdByExactName(name: String): Int? =
        withContext(Dispatchers.IO) {
            val query = name.trim()
            if (query.isBlank()) return@withContext null
            val apiKey = requireApiKey() ?: return@withContext null

            try {
                val top = when (val result = tmdbIntegrationProvider.searchPeople(query = query)) {
                    is IntegrationLoadResult.Success -> result.value.results.orEmpty().firstOrNull()
                    else -> null
                } ?: return@withContext null
                val topName = top.name?.trim().orEmpty()
                if (topName.isBlank()) return@withContext null
                if (!namesMatchExactly(query, topName)) return@withContext null
                top.id
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.w(TAG, "Failed to search TMDB person by name '$query': ${e.message}")
                null
            }
        }

    suspend fun findCompanyIdByExactName(name: String): Int? =
        withContext(Dispatchers.IO) {
            val query = name.trim()
            if (query.isBlank()) return@withContext null
            val apiKey = requireApiKey() ?: return@withContext null

            try {
                val top = when (val result = tmdbIntegrationProvider.searchCompanies(query = query)) {
                    is IntegrationLoadResult.Success -> result.value.results.orEmpty().firstOrNull()
                    else -> null
                } ?: return@withContext null
                val topName = top.name?.trim().orEmpty()
                if (topName.isBlank()) return@withContext null
                if (!namesMatchExactly(query, topName)) return@withContext null
                top.id
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.w(TAG, "Failed to search TMDB company by name '$query': ${e.message}")
                null
            }
        }

    private fun mapMovieCreditsFromCast(cast: List<TmdbPersonCreditCast>): List<MetaPreview> {
        val seenMovieIds = mutableSetOf<Int>()
        return cast
            .filter { it.mediaType == "movie" && it.posterPath != null }
            .sortedByDescending { it.voteAverage ?: 0.0 }
            .mapNotNull { credit ->
                if (!seenMovieIds.add(credit.id)) return@mapNotNull null
                val title = credit.title ?: credit.name ?: return@mapNotNull null
                val year = credit.releaseDate?.take(4)
                MetaPreview(
                    id = "tmdb:${credit.id}",
                    type = ContentType.MOVIE,
                    name = title,
                    poster = buildImageUrl(credit.posterPath, "w500"),
                    posterShape = PosterShape.POSTER,
                    background = buildImageUrl(credit.backdropPath, "w1280"),
                    logo = null,
                    description = credit.overview?.takeIf { it.isNotBlank() },
                    releaseInfo = year,
                    imdbRating = credit.voteAverage?.toFloat(),
                    genres = emptyList()
                )
            }
    }

    private fun mapMovieCreditsFromCrew(crew: List<TmdbPersonCreditCrew>): List<MetaPreview> {
        val seenMovieIds = mutableSetOf<Int>()
        return crew
            .filter { it.mediaType == "movie" && it.posterPath != null }
            .sortedByDescending { it.voteAverage ?: 0.0 }
            .mapNotNull { credit ->
                if (!seenMovieIds.add(credit.id)) return@mapNotNull null
                val title = credit.title ?: credit.name ?: return@mapNotNull null
                val year = credit.releaseDate?.take(4)
                MetaPreview(
                    id = "tmdb:${credit.id}",
                    type = ContentType.MOVIE,
                    name = title,
                    poster = buildImageUrl(credit.posterPath, "w500"),
                    posterShape = PosterShape.POSTER,
                    background = buildImageUrl(credit.backdropPath, "w1280"),
                    logo = null,
                    description = credit.overview?.takeIf { it.isNotBlank() },
                    releaseInfo = year,
                    imdbRating = credit.voteAverage?.toFloat(),
                    genres = emptyList()
                )
            }
    }

    private fun mapTvCreditsFromCast(cast: List<TmdbPersonCreditCast>): List<MetaPreview> {
        val seenTvIds = mutableSetOf<Int>()
        return cast
            .filter { it.mediaType == "tv" && it.posterPath != null }
            .sortedByDescending { it.voteAverage ?: 0.0 }
            .mapNotNull { credit ->
                if (!seenTvIds.add(credit.id)) return@mapNotNull null
                val title = credit.name ?: credit.title ?: return@mapNotNull null
                val year = credit.firstAirDate?.take(4)
                MetaPreview(
                    id = "tmdb:${credit.id}",
                    type = ContentType.SERIES,
                    name = title,
                    poster = buildImageUrl(credit.posterPath, "w500"),
                    posterShape = PosterShape.POSTER,
                    background = buildImageUrl(credit.backdropPath, "w1280"),
                    logo = null,
                    description = credit.overview?.takeIf { it.isNotBlank() },
                    releaseInfo = year,
                    imdbRating = credit.voteAverage?.toFloat(),
                    genres = emptyList()
                )
            }
    }

    private fun mapTvCreditsFromCrew(crew: List<TmdbPersonCreditCrew>): List<MetaPreview> {
        val seenTvIds = mutableSetOf<Int>()
        return crew
            .filter { it.mediaType == "tv" && it.posterPath != null }
            .sortedByDescending { it.voteAverage ?: 0.0 }
            .mapNotNull { credit ->
                if (!seenTvIds.add(credit.id)) return@mapNotNull null
                val title = credit.name ?: credit.title ?: return@mapNotNull null
                val year = credit.firstAirDate?.take(4)
                MetaPreview(
                    id = "tmdb:${credit.id}",
                    type = ContentType.SERIES,
                    name = title,
                    poster = buildImageUrl(credit.posterPath, "w500"),
                    posterShape = PosterShape.POSTER,
                    background = buildImageUrl(credit.backdropPath, "w1280"),
                    logo = null,
                    description = credit.overview?.takeIf { it.isNotBlank() },
                    releaseInfo = year,
                    imdbRating = credit.voteAverage?.toFloat(),
                    genres = emptyList()
                )
            }
    }

    private fun namesMatchExactly(left: String, right: String): Boolean {
        return normalizePersonName(left) == normalizePersonName(right)
    }

    private fun normalizePersonName(value: String): String {
        val normalized = Normalizer.normalize(value, Normalizer.Form.NFKD)
        return buildString(normalized.length) {
            normalized.forEach { ch ->
                if (Character.getType(ch) != Character.NON_SPACING_MARK.toInt()) {
                    append(ch)
                }
            }
        }
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
    }
}

private fun preferredRegions(normalizedLanguage: String): List<String> {
    val fromLanguage = normalizedLanguage.substringAfter("-", "").uppercase(Locale.US).takeIf { it.length == 2 }
    return buildList {
        if (!fromLanguage.isNullOrBlank()) add(fromLanguage)
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

data class TmdbEnrichment(
    val localizedTitle: String?,
    val description: String?,
    val genres: List<String>,
    val backdrop: String?,
    val logo: String?,
    val poster: String?,
    val directorMembers: List<MetaCastMember>,
    val writerMembers: List<MetaCastMember>,
    val castMembers: List<MetaCastMember>,
    val releaseInfo: String?,
    val rating: Double?,
    val ratingSource: TitleRatingSource? = TitleRatingSource.TMDB,
    val runtimeMinutes: Int?,
    val director: List<String>,
    val writer: List<String>,
    val productionCompanies: List<MetaCompany>,
    val networks: List<MetaCompany>,
    val ageRating: String?,
    val countries: List<String>?,
    val language: String?,
    val collectionId: Int?,
    val collectionName: String?,
    val imdbId: String? = null,
    val tvdbId: Int? = null
)

data class TmdbEpisodeEnrichment(
    val tmdbEpisodeId: Int?,
    val voteAverage: Double?,
    val title: String?,
    val overview: String?,
    val thumbnail: String?,
    val airDate: String?,
    val runtimeMinutes: Int?
)

private const val MAX_CONCURRENT_SEASON_REQUESTS = 3

private fun TmdbEpisode.toEnrichment(): TmdbEpisodeEnrichment {
    val title = name?.takeIf { it.isNotBlank() }
    val overview = overview?.takeIf { it.isNotBlank() }
    val thumbnail = stillPath?.takeIf { it.isNotBlank() }?.let { "https://image.tmdb.org/t/p/w500$it" }
    val airDate = airDate?.takeIf { it.isNotBlank() }
    return TmdbEpisodeEnrichment(
        tmdbEpisodeId = id,
        voteAverage = voteAverage,
        title = title,
        overview = overview,
        thumbnail = thumbnail,
        airDate = airDate,
        runtimeMinutes = runtime
    )
}
