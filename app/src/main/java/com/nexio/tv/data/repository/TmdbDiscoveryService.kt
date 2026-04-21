package com.nexio.tv.data.repository

import com.nexio.tv.core.metadata.MetadataApiKeyResolver
import com.nexio.tv.core.metadata.MetadataProviderCredential
import com.nexio.tv.core.tmdb.TmdbService
import com.nexio.tv.data.local.TmdbCatalogIds
import com.nexio.tv.data.local.TmdbCatalogPreferences
import com.nexio.tv.data.remote.api.TmdbApi
import com.nexio.tv.data.remote.api.TmdbMediaResult
import com.nexio.tv.data.remote.api.TmdbPagedMediaResponse
import com.nexio.tv.domain.model.CatalogRow
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.PosterShape
import com.nexio.tv.domain.model.TitleRatingSource
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import retrofit2.Response
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RetrofitTmdbDiscoveryClient @Inject constructor(
    private val tmdbApi: TmdbApi,
    private val metadataApiKeyResolver: MetadataApiKeyResolver,
    private val tmdbService: TmdbService
) : TmdbDiscoveryClient {
    override suspend fun credential(): MetadataProviderCredential {
        return metadataApiKeyResolver.tmdbCredential()
    }

    override suspend fun searchMovies(
        query: String,
        preferences: TmdbCatalogPreferences
    ): List<TmdbMediaResult> {
        val credential = credential()
        if (credential.missing) return emptyList()
        return tmdbApi.searchMovies(
            apiKey = credential.apiKey,
            query = query,
            includeAdult = preferences.includeAdult
        ).mediaResults()
    }

    override suspend fun searchTv(
        query: String,
        preferences: TmdbCatalogPreferences
    ): List<TmdbMediaResult> {
        val credential = credential()
        if (credential.missing) return emptyList()
        return tmdbApi.searchTv(
            apiKey = credential.apiKey,
            query = query,
            includeAdult = preferences.includeAdult
        ).mediaResults()
    }

    override suspend fun fetchCatalog(
        catalogId: String,
        preferences: TmdbCatalogPreferences
    ): List<TmdbMediaResult> {
        val credential = credential()
        if (credential.missing) return emptyList()
        val apiKey = credential.apiKey
        val now = LocalDate.now()
        val today = now.toString()
        val currentYear = now.year
        val response = when (catalogId) {
            TmdbCatalogIds.TRENDING_MOVIES -> tmdbApi.getTrendingMovies(apiKey = apiKey)
            TmdbCatalogIds.TRENDING_SERIES -> tmdbApi.getTrendingTv(apiKey = apiKey)
            TmdbCatalogIds.LATEST_RELEASES_MOVIES -> tmdbApi.discoverMovies(
                apiKey = apiKey,
                includeAdult = preferences.includeAdult,
                sortBy = "release_date.desc",
                releaseDateLte = today,
                withReleaseType = if (preferences.hideUnreleasedDigital) "4" else null
            )
            TmdbCatalogIds.LATEST_RELEASES_SERIES -> tmdbApi.discoverTv(
                apiKey = apiKey,
                includeAdult = preferences.includeAdult,
                sortBy = "first_air_date.desc",
                firstAirDateLte = today
            )
            TmdbCatalogIds.POPULAR_MOVIES -> tmdbApi.getPopularMovies(apiKey = apiKey)
            TmdbCatalogIds.POPULAR_SERIES -> tmdbApi.getPopularTv(apiKey = apiKey)
            TmdbCatalogIds.YEAR_MOVIES -> tmdbApi.discoverMovies(
                apiKey = apiKey,
                includeAdult = preferences.includeAdult,
                sortBy = "popularity.desc",
                primaryReleaseYear = currentYear
            )
            TmdbCatalogIds.YEAR_SERIES -> tmdbApi.discoverTv(
                apiKey = apiKey,
                includeAdult = preferences.includeAdult,
                sortBy = "popularity.desc",
                firstAirDateYear = currentYear
            )
            TmdbCatalogIds.LANGUAGE_MOVIES -> tmdbApi.discoverMovies(
                apiKey = apiKey,
                includeAdult = preferences.includeAdult,
                sortBy = "popularity.desc",
                withOriginalLanguage = "en"
            )
            TmdbCatalogIds.LANGUAGE_SERIES -> tmdbApi.discoverTv(
                apiKey = apiKey,
                includeAdult = preferences.includeAdult,
                sortBy = "popularity.desc",
                withOriginalLanguage = "en"
            )
            else -> return emptyList()
        }
        return response.mediaResults()
    }

    override suspend fun imdbId(tmdbId: Int, contentType: ContentType): String? {
        val mediaType = when (contentType) {
            ContentType.SERIES, ContentType.TV -> "series"
            else -> "movie"
        }
        return tmdbService.tmdbToImdb(tmdbId, mediaType)
    }

    private fun Response<TmdbPagedMediaResponse>.mediaResults(): List<TmdbMediaResult> {
        if (!isSuccessful) return emptyList()
        return body()?.results.orEmpty()
    }
}

@Singleton
class TmdbDiscoveryService @Inject constructor(
    private val client: TmdbDiscoveryClient
) {
    private val snapshot = MutableStateFlow(TmdbDiscoverySnapshot())
    private val imdbLookupSemaphore = Semaphore(IMDB_LOOKUP_CONCURRENCY)

    fun observeSnapshot(): Flow<TmdbDiscoverySnapshot> = snapshot

    suspend fun search(
        query: String,
        preferences: TmdbCatalogPreferences
    ): List<CatalogRow> = coroutineScope {
        val trimmedQuery = query.trim()
        if (trimmedQuery.length < 2) return@coroutineScope emptyList()
        if (client.credential().missing) return@coroutineScope emptyList()

        val movieResults = async {
            runCatching { client.searchMovies(trimmedQuery, preferences) }
                .getOrDefault(emptyList())
        }
        val tvResults = async {
            runCatching { client.searchTv(trimmedQuery, preferences) }
                .getOrDefault(emptyList())
        }
        val items = mapResults(movieResults.await(), ContentType.MOVIE) +
            mapResults(tvResults.await(), ContentType.SERIES)
        if (items.isEmpty()) return@coroutineScope emptyList()

        listOf(
            CatalogRow(
                addonId = ADDON_ID,
                addonName = ADDON_NAME,
                addonBaseUrl = ADDON_BASE_URL,
                catalogId = SEARCH_CATALOG_ID,
                catalogName = SEARCH_CATALOG_NAME,
                type = ContentType.UNKNOWN,
                rawType = ContentType.UNKNOWN.toApiString("catalog"),
                items = items,
                hasMore = false,
                supportsSkip = false
            )
        )
    }

    suspend fun refreshCatalogs(
        preferences: TmdbCatalogPreferences,
        force: Boolean,
        catalogIds: Set<String>? = null
    ): TmdbDiscoverySnapshot = coroutineScope {
        val sanitized = preferences.sanitized()
        if (client.credential().missing) {
            val missingCredentialSnapshot = TmdbDiscoverySnapshot(
                updatedAtMs = System.currentTimeMillis(),
                includeAdult = sanitized.includeAdult,
                hideUnreleasedDigital = sanitized.hideUnreleasedDigital,
                catalogIdsWithCurrentPreferences = sanitized.enabledCatalogIds()
            )
            snapshot.value = missingCredentialSnapshot
            return@coroutineScope missingCredentialSnapshot
        }

        val requestedCatalogIds = catalogIds
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.toSet()
        val previous = snapshot.value
        val previousCurrentRows = previous.currentRowsFor(sanitized)
        if (!force && requestedCatalogIds != null && requestedCatalogIds.all { it in previousCurrentRows }) {
            return@coroutineScope previous
        }
        val enabledCatalogs = sanitized.catalogOrder
            .filter { it in sanitized.enabledCatalogs }
            .filter { requestedCatalogIds == null || it in requestedCatalogIds }
        val refreshedRows = enabledCatalogs
            .map { catalogId ->
                async {
                    val row = fetchCatalogRow(catalogId, sanitized)
                    catalogId to row
                }
            }
            .awaitAll()
            .mapNotNull { (catalogId, row) -> row?.let { catalogId to it } }
            .toMap()

        val rows = if (catalogIds == null) {
            refreshedRows
        } else {
            previousCurrentRows + refreshedRows
        }
        val previousCurrentCatalogIds = previousCurrentRows.keys
        val catalogIdsWithCurrentPreferences = if (catalogIds == null) {
            refreshedRows.keys
        } else {
            previousCurrentCatalogIds + refreshedRows.keys
        }
        val refreshed = TmdbDiscoverySnapshot(
            rowsByCatalog = rows,
            updatedAtMs = System.currentTimeMillis(),
            includeAdult = sanitized.includeAdult,
            hideUnreleasedDigital = sanitized.hideUnreleasedDigital,
            catalogIdsWithCurrentPreferences = catalogIdsWithCurrentPreferences
        )
        snapshot.value = refreshed
        refreshed
    }

    private suspend fun fetchCatalogRow(
        catalogId: String,
        preferences: TmdbCatalogPreferences
    ): CatalogRow? {
        val title = tmdbCatalogTitle(catalogId) ?: return null
        val contentType = catalogContentType(catalogId) ?: return null
        val results = runCatching { client.fetchCatalog(catalogId, preferences) }
            .getOrDefault(emptyList())
        val items = mapResults(results, contentType)
        return CatalogRow(
            addonId = ADDON_ID,
            addonName = ADDON_NAME,
            addonBaseUrl = ADDON_BASE_URL,
            catalogId = catalogId,
            catalogName = title,
            type = contentType,
            items = items,
            hasMore = false,
            supportsSkip = false
        )
    }

    private suspend fun mapResults(
        results: List<TmdbMediaResult>,
        contentType: ContentType
    ): List<MetaPreview> = coroutineScope {
        results.take(MAX_ITEMS_PER_SOURCE)
            .map { result ->
                async {
                    imdbLookupSemaphore.withPermit {
                        mapResult(result, contentType)
                    }
                }
            }
            .awaitAll()
            .filterNotNull()
    }

    private suspend fun mapResult(
        result: TmdbMediaResult,
        contentType: ContentType
    ): MetaPreview? {
        val title = firstNonBlank(
            result.title,
            result.name,
            result.originalTitle,
            result.originalName
        ) ?: return null
        val backdrop = tmdbImageUrl(result.backdropPath, "w1280")
        val poster = backdrop ?: tmdbImageUrl(result.posterPath, "w780")
        val imdbId = client.imdbId(result.id, contentType)
        return MetaPreview(
            id = imdbId ?: "tmdb:${result.id}",
            type = contentType,
            rawType = contentType.toApiString(),
            name = title,
            poster = poster,
            posterShape = if (backdrop != null) PosterShape.LANDSCAPE else PosterShape.POSTER,
            background = backdrop,
            logo = null,
            description = result.overview?.trim()?.takeIf { it.isNotBlank() },
            releaseInfo = firstNonBlank(result.releaseDate, result.firstAirDate),
            imdbRating = result.voteAverage?.toFloat(),
            ratingSource = TitleRatingSource.TMDB,
            genres = emptyList(),
            language = result.originalLanguage?.trim()?.takeIf { it.isNotBlank() }
        )
    }

    private fun catalogContentType(catalogId: String): ContentType? {
        return when (catalogId) {
            TmdbCatalogIds.TRENDING_MOVIES,
            TmdbCatalogIds.LATEST_RELEASES_MOVIES,
            TmdbCatalogIds.POPULAR_MOVIES,
            TmdbCatalogIds.YEAR_MOVIES,
            TmdbCatalogIds.LANGUAGE_MOVIES -> ContentType.MOVIE
            TmdbCatalogIds.TRENDING_SERIES,
            TmdbCatalogIds.LATEST_RELEASES_SERIES,
            TmdbCatalogIds.POPULAR_SERIES,
            TmdbCatalogIds.YEAR_SERIES,
            TmdbCatalogIds.LANGUAGE_SERIES -> ContentType.SERIES
            else -> null
        }
    }

    private fun firstNonBlank(vararg values: String?): String? {
        return values.firstNotNullOfOrNull { value ->
            value?.trim()?.takeIf { it.isNotBlank() }
        }
    }

    private fun tmdbImageUrl(path: String?, size: String): String? {
        val value = path?.trim()?.takeIf { it.isNotBlank() } ?: return null
        if (value.startsWith("http://") || value.startsWith("https://")) return value
        return "https://image.tmdb.org/t/p/$size${if (value.startsWith("/")) value else "/$value"}"
    }

    companion object {
        private const val ADDON_ID = "tmdb"
        private const val ADDON_NAME = "TMDB"
        private const val ADDON_BASE_URL = "https://api.themoviedb.org/3"
        private const val SEARCH_CATALOG_ID = "tmdb_search"
        private const val SEARCH_CATALOG_NAME = "TMDB Search"
        private const val MAX_ITEMS_PER_SOURCE = 20
        private const val IMDB_LOOKUP_CONCURRENCY = 6
    }
}
