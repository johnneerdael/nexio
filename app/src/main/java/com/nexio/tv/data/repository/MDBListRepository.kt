package com.nexio.tv.data.repository

import com.nexio.tv.data.integration.mdblist.MDBListIntegrationProvider
import com.nexio.tv.core.integration.IntegrationCallResult
import com.nexio.tv.core.tmdb.TmdbService
import com.nexio.tv.data.local.MDBListSettingsDataStore
import com.nexio.tv.data.remote.dto.mdblist.MDBListRatingItemDto
import com.nexio.tv.domain.model.MDBListRatingsResult
import com.nexio.tv.domain.model.MDBListSettings
import com.nexio.tv.domain.model.Meta
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.MetaCompany
import com.nexio.tv.domain.model.MetaLink
import com.nexio.tv.domain.model.PosterShape
import com.nexio.tv.domain.model.Video
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

internal const val EPISODE_RATINGS_COMPLETE_TTL_MS = 7L * 24L * 60L * 60L * 1000L
internal const val EPISODE_RATINGS_RETRY_TTL_MS = 30L * 60L * 1000L
internal const val MDBLIST_TITLE_RATINGS_TTL_MS = 7L * 24L * 60L * 60L * 1000L
internal const val MDBLIST_TITLE_RATINGS_NEGATIVE_TTL_MS = 6L * 60L * 60L * 1000L

data class MDBListTitleRatingRequest(
    val stableId: String,
    val mediaType: String,
    val requestProvider: String,
    val ratingSources: List<String>
)

@Singleton
class MDBListRepository @Inject constructor(
    private val integrationProvider: MDBListIntegrationProvider,
    private val settingsDataStore: MDBListSettingsDataStore,
    private val tmdbService: TmdbService
) {
    private data class CacheEntry(
        val result: MDBListRatingsResult?,
        val expiresAtMs: Long
    )

    private data class EpisodeRatingsCacheEntry(
        val result: Map<Pair<Int, Int>, Double>,
        val expiresAtMs: Long
    )

    private data class SourceCacheKey(
        val mediaType: String,
        val requestProvider: String,
        val ratingSource: String,
        val stableId: String,
        val apiKeyHash: Int
    )

    private data class SourceCacheEntry(
        val rating: Double?,
        val expiresAtMs: Long
    )

    private data class BatchGroup(
        val mediaType: String,
        val requestProvider: String,
        val ratingSource: String
    )

    private enum class ProviderType(val apiValue: String) {
        TRAKT("trakt"),
        IMDB("imdb"),
        MAL("mal"),
        TMDB("tmdb"),
        LETTERBOXD("letterboxd"),
        TOMATOES("tomatoes"),
        AUDIENCE("audience"),
        METACRITIC("metacritic")
    }

    private data class RatingLookupIdentity(
        val provider: String,
        val id: Any,
        val cacheToken: String
    )

    private val cacheTtlMs = MDBLIST_TITLE_RATINGS_TTL_MS
    private val cache = ConcurrentHashMap<String, CacheEntry>()
    private val inFlight = mutableMapOf<String, kotlinx.coroutines.Deferred<MDBListRatingsResult?>>()
    private val inFlightMutex = Mutex()
    private val episodeRatingsCache = ConcurrentHashMap<String, EpisodeRatingsCacheEntry>()
    private val episodeRatingsInFlight = mutableMapOf<String, kotlinx.coroutines.Deferred<Map<Pair<Int, Int>, Double>>>()
    private val episodeRatingsInFlightMutex = Mutex()
    private val sourceRatingCache = ConcurrentHashMap<SourceCacheKey, SourceCacheEntry>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun getRatingsForMeta(
        meta: Meta,
        fallbackItemId: String,
        fallbackItemType: String,
        imdbIdOverride: String? = null
    ): MDBListRatingsResult? {
        val settings = settingsDataStore.settings.first()
        if (!settings.enabled) return null

        val apiKey = settings.apiKey.trim()
        if (apiKey.isBlank()) return null

        val enabledProviders = enabledProviders(settings, meta, fallbackItemType)
        if (enabledProviders.isEmpty()) return null

        return getRatingsForMeta(
            meta = meta,
            fallbackItemId = fallbackItemId,
            fallbackItemType = fallbackItemType,
            apiKey = apiKey,
            providers = enabledProviders,
            imdbIdOverride = imdbIdOverride
        )
    }

    private suspend fun getRatingsForMeta(
        meta: Meta,
        fallbackItemId: String,
        fallbackItemType: String,
        apiKey: String,
        providers: List<ProviderType>,
        imdbIdOverride: String? = null
    ): MDBListRatingsResult? {
        if (providers.isEmpty()) return null

        val mediaType = normalizeMediaType(meta.apiType.ifBlank { fallbackItemType })
        val ratingIdentity = resolveRatingLookupIdentity(
            meta = meta,
            fallbackItemId = fallbackItemId,
            fallbackItemType = fallbackItemType,
            mediaType = mediaType,
            imdbIdOverride = imdbIdOverride
        )
            ?: return null

        val providerHash = providers.map { it.apiValue }.sorted().joinToString(",")
        val cacheKey = "$mediaType:${ratingIdentity.cacheToken}:$providerHash:${apiKey.hashCode()}"
        val now = System.currentTimeMillis()

        cache[cacheKey]?.let { cached ->
            if (cached.expiresAtMs > now) {
                return cached.result
            }
            cache.remove(cacheKey)
        }

        val deferred = inFlightMutex.withLock {
            inFlight[cacheKey] ?: scope.async {
                try {
                    val result = integrationProvider.fetchRatings(
                        ratingId = ratingIdentity.id,
                        requestProvider = ratingIdentity.provider,
                        mediaType = mediaType,
                        apiKey = apiKey,
                        providers = providers.map { it.apiValue }
                    )
                    result.also {
                        cache[cacheKey] = CacheEntry(
                            result = it,
                            expiresAtMs = System.currentTimeMillis() + cacheTtlMs
                        )
                    }
                } finally {
                    inFlightMutex.withLock {
                        inFlight.remove(cacheKey)
                    }
                }
            }.also { created ->
                inFlight[cacheKey] = created
            }
        }

        return deferred.await()
    }

    suspend fun getTitleRatings(
        requests: List<MDBListTitleRatingRequest>,
        cacheOnly: Boolean
    ): Map<MDBListTitleRatingRequest, com.nexio.tv.domain.model.MDBListRatings> {
        val settings = settingsDataStore.settings.first()
        if (!settings.enabled) return emptyMap()
        val apiKey = settings.apiKey.trim()
        if (apiKey.isBlank()) return emptyMap()
        return getTitleRatings(requests, apiKey, cacheOnly)
    }

    private suspend fun getTitleRatings(
        requests: List<MDBListTitleRatingRequest>,
        apiKey: String,
        cacheOnly: Boolean
    ): Map<MDBListTitleRatingRequest, com.nexio.tv.domain.model.MDBListRatings> {
        val normalizedRequests = requests.mapNotNull { request ->
            val stableId = request.stableId.trim().takeIf(String::isNotEmpty) ?: return@mapNotNull null
            val mediaType = normalizeMediaType(request.mediaType)
            val requestProvider = request.requestProvider.trim().lowercase().takeIf(String::isNotEmpty) ?: return@mapNotNull null
            val sources = request.ratingSources
                .map { it.trim().lowercase() }
                .filter { source -> ProviderType.entries.any { it.apiValue == source } }
                .filter { it in MDBLIST_ALLOWED_TITLE_RATING_SOURCES }
                .distinct()
            if (sources.isEmpty()) return@mapNotNull null
            request.copy(
                stableId = stableId,
                mediaType = mediaType,
                requestProvider = requestProvider,
                ratingSources = sources
            )
        }
        if (normalizedRequests.isEmpty()) return emptyMap()

        val apiKeyHash = apiKey.hashCode()
        val now = System.currentTimeMillis()
        val missingByGroup = linkedMapOf<BatchGroup, MutableList<String>>()

        for (i in normalizedRequests.indices) {
            val request = normalizedRequests[i]
            for (sourceIndex in request.ratingSources.indices) {
                val source = request.ratingSources[sourceIndex]
                val key = SourceCacheKey(
                    mediaType = request.mediaType,
                    requestProvider = request.requestProvider,
                    ratingSource = source,
                    stableId = request.stableId,
                    apiKeyHash = apiKeyHash
                )
                if (sourceRatingCache[key]?.expiresAtMs?.let { it > now } == true) continue
                if (!cacheOnly) {
                    missingByGroup.getOrPut(
                        BatchGroup(request.mediaType, request.requestProvider, source)
                    ) { mutableListOf() } += request.stableId
                }
            }
        }

        if (!cacheOnly) {
            hydrateMissingTitleRatings(missingByGroup, apiKey, apiKeyHash)
        }

        return buildTitleRatingResults(normalizedRequests, apiKeyHash)
    }

    private suspend fun hydrateMissingTitleRatings(
        missingByGroup: Map<BatchGroup, List<String>>,
        apiKey: String,
        apiKeyHash: Int
    ) {
        for ((group, rawIds) in missingByGroup) {
            val ids = rawIds.distinct()
            if (ids.isEmpty()) continue
            when (val result = integrationProvider.fetchRatingBatch(
                mediaType = group.mediaType,
                ratingType = group.ratingSource,
                requestProvider = group.requestProvider,
                ids = ids,
                apiKey = apiKey
            )) {
                is IntegrationCallResult.Success -> {
                    val expiresAt = System.currentTimeMillis() + MDBLIST_TITLE_RATINGS_TTL_MS
                    val negativeExpiresAt = System.currentTimeMillis() + MDBLIST_TITLE_RATINGS_NEGATIVE_TTL_MS
                    for (i in ids.indices) {
                        val id = ids[i]
                        val rating = result.value[id]
                        sourceRatingCache[SourceCacheKey(
                            mediaType = group.mediaType,
                            requestProvider = group.requestProvider,
                            ratingSource = group.ratingSource,
                            stableId = id,
                            apiKeyHash = apiKeyHash
                        )] = SourceCacheEntry(
                            rating = rating,
                            expiresAtMs = if (rating == null) negativeExpiresAt else expiresAt
                        )
                    }
                }
                else -> Unit
            }
        }
    }

    private fun buildTitleRatingResults(
        requests: List<MDBListTitleRatingRequest>,
        apiKeyHash: Int
    ): Map<MDBListTitleRatingRequest, com.nexio.tv.domain.model.MDBListRatings> {
        val out = linkedMapOf<MDBListTitleRatingRequest, com.nexio.tv.domain.model.MDBListRatings>()
        val now = System.currentTimeMillis()
        for (i in requests.indices) {
            val request = requests[i]
            var ratings = com.nexio.tv.domain.model.MDBListRatings()
            for (sourceIndex in request.ratingSources.indices) {
                val source = request.ratingSources[sourceIndex]
                val key = SourceCacheKey(
                    mediaType = request.mediaType,
                    requestProvider = request.requestProvider,
                    ratingSource = source,
                    stableId = request.stableId,
                    apiKeyHash = apiKeyHash
                )
                val rating = sourceRatingCache[key]?.takeIf { it.expiresAtMs > now }?.rating ?: continue
                ratings = ratings.withSource(source, rating)
            }
            if (!ratings.isEmpty()) {
                out[request] = ratings
            }
        }
        return out
    }

    suspend fun enrichPreview(preview: MetaPreview, imdbIdOverride: String? = null): MetaPreview {
        val settings = settingsDataStore.settings.first()
        if (!settings.enabled) return preview

        val apiKey = settings.apiKey.trim()
        if (apiKey.isBlank()) return preview

        val needsTomatoes = preview.tomatoesRating == null && settings.showTomatoes
        if (!needsTomatoes) return preview

        val result = getRatingsForMeta(
            meta = preview.toRatingsMeta(),
            fallbackItemId = preview.id,
            fallbackItemType = preview.apiType,
            apiKey = apiKey,
            providers = buildList {
                if (needsTomatoes) add(ProviderType.TOMATOES)
            },
            imdbIdOverride = imdbIdOverride
        ) ?: return preview

        return preview.copy(
            tomatoesRating = result.ratings.tomatoes ?: preview.tomatoesRating
        )
    }

    suspend fun getEpisodeRatingsForMeta(
        meta: Meta,
        fallbackItemId: String,
        fallbackItemType: String,
        episodeTmdbIds: Map<Pair<Int, Int>, Int>
    ): Map<Pair<Int, Int>, Double> {
        return emptyMap()
    }

    private suspend fun getEpisodeRatingsForSeason(
        cacheNamespace: String,
        season: Int,
        apiKey: String,
        episodeTmdbIds: Map<Pair<Int, Int>, Int>
    ): Map<Pair<Int, Int>, Double> {
        val cacheKey = "show:$cacheNamespace:$season:${apiKey.hashCode()}"
        val now = System.currentTimeMillis()

        episodeRatingsCache[cacheKey]?.let { cached ->
            if (cached.expiresAtMs > now) {
                return cached.result
            }
            episodeRatingsCache.remove(cacheKey)
        }

        val deferred = episodeRatingsInFlightMutex.withLock {
            episodeRatingsInFlight[cacheKey] ?: scope.async {
                try {
                    integrationProvider.fetchEpisodeRatingsForSeason(
                        cacheNamespace = cacheNamespace,
                        season = season,
                        apiKey = apiKey,
                        episodeTmdbIds = episodeTmdbIds
                    ).also { result ->
                        val ttlMs = episodeRatingsCacheTtlMs(
                            expectedCount = episodeTmdbIds.size,
                            actualCount = result.size
                        )
                        episodeRatingsCache[cacheKey] = EpisodeRatingsCacheEntry(
                            result = result,
                            expiresAtMs = System.currentTimeMillis() + ttlMs
                        )
                    }
                } finally {
                    episodeRatingsInFlightMutex.withLock {
                        episodeRatingsInFlight.remove(cacheKey)
                    }
                }
            }.also { created ->
                episodeRatingsInFlight[cacheKey] = created
            }
        }

        return deferred.await()
    }

    private fun enabledProviders(settings: MDBListSettings, meta: Meta, fallbackItemType: String): List<ProviderType> = buildList {
        if (isAnime(meta, fallbackItemType)) add(ProviderType.MAL)
        if (settings.showTmdb) add(ProviderType.TMDB)
        if (settings.showLetterboxd) add(ProviderType.LETTERBOXD)
        if (settings.showTomatoes) add(ProviderType.TOMATOES)
        if (settings.showMetacritic) add(ProviderType.METACRITIC)
    }

    private fun isAnime(meta: Meta, fallbackItemType: String): Boolean =
        fallbackItemType.equals("anime", ignoreCase = true) ||
            meta.rawType.equals("anime", ignoreCase = true) ||
            meta.apiType.equals("anime", ignoreCase = true)

    private suspend fun resolveRatingLookupIdentity(
        meta: Meta,
        fallbackItemId: String,
        fallbackItemType: String,
        mediaType: String,
        imdbIdOverride: String?
    ): RatingLookupIdentity? {
        if (isAnime(meta, fallbackItemType)) {
            val imdbId = extractCanonicalImdbId(imdbIdOverride)
                ?: extractCanonicalImdbId(meta.id)
                ?: extractCanonicalImdbId(fallbackItemId)
                ?: return null
            return RatingLookupIdentity(provider = "imdb", id = imdbId, cacheToken = "imdb:$imdbId")
        }

        val tmdbId = extractTmdbId(meta.id)
            ?: extractTmdbId(fallbackItemId)
            ?: meta.id.trim().takeIf { it.all(Char::isDigit) }?.toIntOrNull()
            ?: fallbackItemId.trim().takeIf { it.all(Char::isDigit) }?.toIntOrNull()
            ?: return null

        return RatingLookupIdentity(provider = "tmdb", id = tmdbId, cacheToken = "tmdb:$tmdbId")
    }

    private suspend fun resolveImdbId(
        meta: Meta,
        fallbackItemId: String,
        fallbackItemType: String,
        mediaType: String
    ): String? {
        extractImdbId(meta.id)?.let { return it }
        extractImdbId(fallbackItemId)?.let { return it }

        val tmdbId = extractTmdbId(meta.id)
            ?: extractTmdbId(fallbackItemId)
            ?: meta.id.trim().takeIf { it.all(Char::isDigit) }?.toIntOrNull()
            ?: fallbackItemId.trim().takeIf { it.all(Char::isDigit) }?.toIntOrNull()

        if (tmdbId != null) {
            val mapped = tmdbService.tmdbToImdb(tmdbId, fallbackItemType)
            if (!mapped.isNullOrBlank()) return mapped
        }

        val lookupType = if (fallbackItemType.isNotBlank()) fallbackItemType else mediaType
        var converted: String? = null
        for (candidate in listOf(meta.id, fallbackItemId).distinct()) {
            if (!isTmdbIdLookupCandidate(candidate)) continue
            converted = tmdbService.ensureTmdbId(candidate, lookupType)?.toIntOrNull()?.let { tmdbNumericId ->
                tmdbService.tmdbToImdb(tmdbNumericId, lookupType)
            }
            if (!converted.isNullOrBlank()) break
        }
        return converted?.takeIf { it.startsWith("tt") }
    }

    private fun extractImdbId(rawId: String?): String? {
        return extractCanonicalImdbId(rawId)
    }

    private fun extractTmdbId(rawId: String?): Int? {
        if (rawId.isNullOrBlank()) return null
        val trimmed = rawId.trim()
        if (trimmed.startsWith("tmdb:", ignoreCase = true)) {
            return trimmed.substringAfter(':').substringBefore(':').toIntOrNull()
        }
        return null
    }

    private fun normalizeMediaType(rawType: String): String {
        return when (rawType.lowercase()) {
            "movie", "film" -> "movie"
            "series", "tv", "show", "tvshow" -> "show"
            else -> "movie"
        }
    }

    private fun MetaPreview.toRatingsMeta(): Meta {
        return Meta(
            id = id,
            type = type,
            rawType = rawType,
            name = name,
            poster = poster,
            posterShape = posterShape,
            background = background,
            logo = logo,
            description = description,
            releaseInfo = releaseInfo,
            imdbRating = imdbRating,
            genres = genres,
            runtime = runtime,
            director = emptyList(),
            writer = emptyList(),
            cast = emptyList(),
            castMembers = emptyList(),
            videos = emptyList<Video>(),
            productionCompanies = emptyList<MetaCompany>(),
            networks = emptyList<MetaCompany>(),
            ageRating = null,
            country = null,
            awards = null,
            language = null,
            links = emptyList<MetaLink>(),
            trailerYtIds = trailerYtIds
        )
    }
}

private val MDBLIST_ALLOWED_TITLE_RATING_SOURCES = setOf(
    "tmdb",
    "mal",
    "letterboxd",
    "tomatoes",
    "metacritic"
)

private fun com.nexio.tv.domain.model.MDBListRatings.withSource(
    source: String,
    rating: Double
): com.nexio.tv.domain.model.MDBListRatings {
    return when (source) {
        "tmdb" -> copy(tmdb = rating)
        "mal" -> copy(mal = rating)
        "letterboxd" -> copy(letterboxd = rating)
        "tomatoes" -> copy(tomatoes = rating)
        "metacritic" -> copy(metacritic = rating)
        else -> this
    }
}

internal fun mapEpisodeRatings(
    ratingItems: List<MDBListRatingItemDto>,
    episodeIdsByKey: Map<Pair<Int, Int>, Int>
): Map<Pair<Int, Int>, Double> {
    if (ratingItems.isEmpty() || episodeIdsByKey.isEmpty()) return emptyMap()
    val ratingById = ratingItems.associateNotNull { item ->
        val id = item.id.toEpisodeTmdbIdOrNull()
        val rating = item.rating
        if (id == null || rating == null) null else id to rating
    }
    return episodeIdsByKey.mapNotNull { (key, tmdbEpisodeId) ->
        ratingById[tmdbEpisodeId]?.let { key to it }
    }.toMap()
}

internal fun episodeRatingsCacheTtlMs(expectedCount: Int, actualCount: Int): Long {
    return if (expectedCount > 0 && actualCount == expectedCount) {
        EPISODE_RATINGS_COMPLETE_TTL_MS
    } else {
        EPISODE_RATINGS_RETRY_TTL_MS
    }
}

private inline fun <T, K, V> Iterable<T>.associateNotNull(transform: (T) -> Pair<K, V>?): Map<K, V> {
    val destination = LinkedHashMap<K, V>()
    for (element in this) {
        val pair = transform(element) ?: continue
        destination[pair.first] = pair.second
    }
    return destination
}

internal fun episodeRatingsCacheNamespace(meta: Meta, fallbackItemId: String): String {
    return extractEpisodeRatingsImdbId(meta.id)
        ?: extractEpisodeRatingsImdbId(fallbackItemId)
        ?: extractEpisodeRatingsTmdbId(meta.id)?.let { "tmdb:$it" }
        ?: extractEpisodeRatingsTmdbId(fallbackItemId)?.let { "tmdb:$it" }
        ?: meta.id.trim().takeIf { it.isNotBlank() }
        ?: fallbackItemId.trim().takeIf { it.isNotBlank() }
        ?: "unknown"
}

private fun extractEpisodeRatingsImdbId(rawId: String?): String? {
    if (rawId.isNullOrBlank()) return null
    val regex = Regex("tt\\d+")
    return regex.find(rawId)?.value
}

private fun extractEpisodeRatingsTmdbId(rawId: String?): Int? {
    if (rawId.isNullOrBlank()) return null
    val trimmed = rawId.trim()
    if (trimmed.startsWith("tmdb:", ignoreCase = true)) {
        return trimmed.substringAfter(':').substringBefore(':').toIntOrNull()
    }
    return null
}

private fun Any?.toEpisodeTmdbIdOrNull(): Int? {
    return when (this) {
        is Int -> this
        is Long -> toInt()
        is Double -> toInt()
        is Float -> toInt()
        is String -> toIntOrNull()
        else -> null
    }
}
