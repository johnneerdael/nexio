package com.nexio.tv.data.repository

import com.nexio.tv.data.integration.railpreview.TmdbRailPreviewMapper
import com.nexio.tv.data.local.TmdbCatalogIds
import com.nexio.tv.data.local.TmdbCatalogPreferences
import com.nexio.tv.data.remote.api.TmdbMediaResult
import com.nexio.tv.data.remote.api.TmdbMultiSearchResult
import com.nexio.tv.domain.model.CatalogRow
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.PosterShape
import com.nexio.tv.domain.model.RailItemPreview
import com.nexio.tv.domain.model.RailPreviewCatalogRowRecord
import com.nexio.tv.domain.model.TitleRatingSource
import com.nexio.tv.domain.model.toMetaPreview
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TmdbDiscoveryService @Inject constructor(
    private val client: TmdbDiscoveryClient
) {
    private val snapshot = MutableStateFlow(TmdbDiscoverySnapshot())
    private val railPreviewMapper = TmdbRailPreviewMapper()
    private val imdbLookupSemaphore = Semaphore(IMDB_LOOKUP_CONCURRENCY)

    fun observeSnapshot(): Flow<TmdbDiscoverySnapshot> = snapshot

    suspend fun search(
        query: String,
        preferences: TmdbCatalogPreferences
    ): List<CatalogRow> = coroutineScope {
        val trimmedQuery = query.trim()
        if (trimmedQuery.length < 2) return@coroutineScope emptyList()
        if (client.credential().missing) return@coroutineScope emptyList()

        val pageJobs = (1..SEARCH_PAGES).map { page ->
            async {
                runCatchingMultiOrEmpty { client.searchMulti(trimmedQuery, page, preferences) }
            }
        }
        val items = mapMultiSearchResults(
            pageJobs.awaitAll()
                .flatten()
                .sortedByDescending { it.popularity ?: 0.0 }
                .take(SEARCH_MAX_ITEMS)
        )
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
        val previousCurrentRowRecords = previous.rowRecordsByCatalog.filterKeys { key ->
            key in previousCurrentRows
        }
        if (!force && requestedCatalogIds != null && requestedCatalogIds.all { it in previousCurrentRows }) {
            return@coroutineScope previous
        }
        val enabledCatalogs = sanitized.catalogOrder
            .filter { it in sanitized.enabledCatalogs }
            .filter { requestedCatalogIds == null || it in requestedCatalogIds }
        val refreshedRows = enabledCatalogs
            .map { catalogId ->
                async {
                    val row = fetchCatalogRowRecord(catalogId, sanitized)
                    catalogId to row
                }
            }
            .awaitAll()
            .mapNotNull { (catalogId, row) -> row?.let { catalogId to it } }
            .toMap()

        val rows = if (catalogIds == null) {
            refreshedRows
        } else {
            (previousCurrentRowRecords - requestedCatalogIds.orEmpty()) + refreshedRows
        }
        val previousCurrentCatalogIds = previousCurrentRows.keys
        val catalogIdsWithCurrentPreferences = if (catalogIds == null) {
            refreshedRows.keys
        } else {
            (previousCurrentCatalogIds - requestedCatalogIds.orEmpty()) + refreshedRows.keys
        }
        val refreshed = TmdbDiscoverySnapshot(
            rowRecordsByCatalog = rows,
            updatedAtMs = System.currentTimeMillis(),
            includeAdult = sanitized.includeAdult,
            hideUnreleasedDigital = sanitized.hideUnreleasedDigital,
            catalogIdsWithCurrentPreferences = catalogIdsWithCurrentPreferences
        )
        snapshot.value = refreshed
        refreshed
    }

    private suspend fun fetchCatalogRowRecord(
        catalogId: String,
        preferences: TmdbCatalogPreferences
    ): RailPreviewCatalogRowRecord? {
        val title = tmdbCatalogTitle(catalogId) ?: return null
        val contentType = catalogContentType(catalogId) ?: return null
        val results = runCatchingOrEmpty { client.fetchCatalog(catalogId, preferences) }
        val items = mapCatalogResults(
            railId = catalogId,
            results = results,
            contentType = contentType,
            generatedAtMs = System.currentTimeMillis()
        )
        if (items.isEmpty()) return null
        return RailPreviewCatalogRowRecord(
            addonId = ADDON_ID,
            addonName = ADDON_NAME,
            addonBaseUrl = ADDON_BASE_URL,
            catalogId = catalogId,
            catalogName = title,
            type = contentType,
            previews = items
        )
    }

    private suspend fun runCatchingOrEmpty(block: suspend () -> List<TmdbMediaResult>): List<TmdbMediaResult> = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
            emptyList()
    }

    private suspend fun runCatchingMultiOrEmpty(
        block: suspend () -> List<TmdbMultiSearchResult>
    ): List<TmdbMultiSearchResult> = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
        emptyList()
    }

    private fun mapCatalogResults(
        railId: String,
        results: List<TmdbMediaResult>,
        contentType: ContentType,
        generatedAtMs: Long
    ): List<RailItemPreview> {
        val genreNames = when (contentType) {
            ContentType.MOVIE -> TMDB_MOVIE_GENRES
            ContentType.SERIES,
            ContentType.TV -> TMDB_TV_GENRES
            else -> emptyMap()
        }
        return results.take(MAX_ITEMS_PER_SOURCE)
            .mapIndexed { index, result ->
                railPreviewMapper.mapResult(
                    railId = railId,
                    result = result,
                    itemType = contentType,
                    position = index,
                    generatedAtMs = generatedAtMs,
                    genreNames = genreNames
                )
            }
    }

    private suspend fun mapMultiSearchResults(
        results: List<TmdbMultiSearchResult>
    ): List<MetaPreview> = coroutineScope {
        results.map { result ->
            async {
                when (result.mediaType?.lowercase()) {
                    "movie" -> mapMultiMovieOrTv(result, ContentType.MOVIE)
                    "tv" -> mapMultiMovieOrTv(result, ContentType.SERIES)
                    "person" -> mapMultiPerson(result)
                    else -> null
                }
            }
        }.awaitAll().filterNotNull()
    }

    private suspend fun mapMultiMovieOrTv(
        result: TmdbMultiSearchResult,
        contentType: ContentType
    ): MetaPreview? {
        val rawTitle = firstNonBlank(
            result.title,
            result.name,
            result.originalTitle,
            result.originalName
        ) ?: return null
        val date = firstNonBlank(result.releaseDate, result.firstAirDate)
        val year = date?.take(4)?.takeIf { value -> value.length == 4 && value.all(Char::isDigit) }
        val displayName = if (year != null) "$rawTitle ($year)" else rawTitle
        val poster = tmdbImageUrl(result.posterPath, "w780")
        val imdbId = imdbLookupSemaphore.withPermit { client.imdbId(result.id, contentType) }
        return MetaPreview(
            id = imdbId ?: "tmdb:${result.id}",
            type = contentType,
            rawType = contentType.toApiString(),
            name = displayName,
            poster = poster,
            posterShape = PosterShape.POSTER,
            background = null,
            logo = null,
            description = result.overview?.trim()?.takeIf { it.isNotBlank() },
            releaseInfo = date,
            imdbRating = result.voteAverage?.toFloat(),
            ratingSource = TitleRatingSource.TMDB,
            genres = emptyList(),
            language = result.originalLanguage?.trim()?.takeIf { it.isNotBlank() }
        )
    }

    private fun mapMultiPerson(result: TmdbMultiSearchResult): MetaPreview? {
        val name = firstNonBlank(result.name, result.originalName) ?: return null
        val poster = tmdbImageUrl(result.profilePath, "w780")
        return MetaPreview(
            id = "$TMDB_PERSON_ID_PREFIX${result.id}",
            type = ContentType.PERSON,
            rawType = ContentType.PERSON.toApiString(),
            name = name,
            poster = poster,
            posterShape = PosterShape.POSTER,
            background = null,
            logo = null,
            description = null,
            releaseInfo = null,
            imdbRating = null,
            ratingSource = null,
            genres = emptyList(),
            language = null
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
        private const val SEARCH_PAGES = 2
        private const val SEARCH_MAX_ITEMS = 40
        const val TMDB_PERSON_ID_PREFIX = "tmdb_person:"
    }
}

private val TMDB_MOVIE_GENRES = mapOf(
    28 to "Action",
    12 to "Adventure",
    16 to "Animation",
    35 to "Comedy",
    80 to "Crime",
    99 to "Documentary",
    18 to "Drama",
    10751 to "Family",
    14 to "Fantasy",
    36 to "History",
    27 to "Horror",
    10402 to "Music",
    9648 to "Mystery",
    10749 to "Romance",
    878 to "Science Fiction",
    10770 to "TV Movie",
    53 to "Thriller",
    10752 to "War",
    37 to "Western"
)

private val TMDB_TV_GENRES = mapOf(
    10759 to "Action & Adventure",
    16 to "Animation",
    35 to "Comedy",
    80 to "Crime",
    99 to "Documentary",
    18 to "Drama",
    10751 to "Family",
    10762 to "Kids",
    9648 to "Mystery",
    10763 to "News",
    10764 to "Reality",
    10765 to "Sci-Fi & Fantasy",
    10766 to "Soap",
    10767 to "Talk",
    10768 to "War & Politics",
    37 to "Western"
)
