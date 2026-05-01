package com.nexio.tv.data.repository

import com.nexio.tv.data.integration.mdblist.MDBListIntegrationProvider
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
import com.nexio.tv.domain.model.TitleRatingSource
import com.nexio.tv.domain.model.Video
import com.nexio.tv.domain.model.orDefault
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

internal const val EPISODE_RATINGS_COMPLETE_TTL_MS = 7L * 24L * 60L * 60L * 1000L
internal const val EPISODE_RATINGS_RETRY_TTL_MS = 30L * 60L * 1000L

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

    private enum class ProviderType(val apiValue: String) {
        TRAKT("trakt"),
        IMDB("imdb"),
        TMDB("tmdb"),
        LETTERBOXD("letterboxd"),
        TOMATOES("tomatoes"),
        AUDIENCE("audience"),
        METACRITIC("metacritic")
    }

    private val cacheTtlMs = 30L * 60L * 1000L
    private val cache = ConcurrentHashMap<String, CacheEntry>()
    private val inFlight = mutableMapOf<String, kotlinx.coroutines.Deferred<MDBListRatingsResult?>>()
    private val inFlightMutex = Mutex()
    private val episodeRatingsCache = ConcurrentHashMap<String, EpisodeRatingsCacheEntry>()
    private val episodeRatingsInFlight = mutableMapOf<String, kotlinx.coroutines.Deferred<Map<Pair<Int, Int>, Double>>>()
    private val episodeRatingsInFlightMutex = Mutex()
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

        val enabledProviders = enabledProviders(settings)
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
        val imdbId = imdbIdOverride?.trim()?.takeIf { it.startsWith("tt") }
            ?: resolveImdbId(meta, fallbackItemId, fallbackItemType, mediaType)
            ?: return null

        val providerHash = providers.map { it.apiValue }.sorted().joinToString(",")
        val cacheKey = "$mediaType:$imdbId:$providerHash:${apiKey.hashCode()}"
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
                    integrationProvider.fetchRatings(
                        imdbId = imdbId,
                        mediaType = mediaType,
                        apiKey = apiKey,
                        providers = providers.map { it.apiValue }
                    ).also { result ->
                        cache[cacheKey] = CacheEntry(
                            result = result,
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

    suspend fun enrichPreview(preview: MetaPreview, imdbIdOverride: String? = null): MetaPreview {
        val settings = settingsDataStore.settings.first()
        if (!settings.enabled) return preview

        val apiKey = settings.apiKey.trim()
        if (apiKey.isBlank()) return preview

        val needsImdb = settings.showImdb
        val needsTomatoes = preview.tomatoesRating == null && settings.showTomatoes
        if (!needsImdb && !needsTomatoes) return preview

        val result = getRatingsForMeta(
            meta = preview.toRatingsMeta(),
            fallbackItemId = preview.id,
            fallbackItemType = preview.apiType,
            apiKey = apiKey,
            providers = buildList {
                if (needsImdb) add(ProviderType.IMDB)
                if (needsTomatoes) add(ProviderType.TOMATOES)
            },
            imdbIdOverride = imdbIdOverride
        ) ?: return preview

        return preview.copy(
            imdbRating = result.ratings.imdb?.toFloat() ?: preview.imdbRating,
            ratingSource = if (result.ratings.imdb != null) TitleRatingSource.IMDB else preview.ratingSource.orDefault(),
            tomatoesRating = result.ratings.tomatoes ?: preview.tomatoesRating
        )
    }

    suspend fun getEpisodeRatingsForMeta(
        meta: Meta,
        fallbackItemId: String,
        fallbackItemType: String,
        episodeTmdbIds: Map<Pair<Int, Int>, Int>
    ): Map<Pair<Int, Int>, Double> {
        if (episodeTmdbIds.isEmpty()) return emptyMap()

        val settings = settingsDataStore.settings.first()
        if (!settings.enabled) return emptyMap()

        val apiKey = settings.apiKey.trim()
        if (apiKey.isBlank()) return emptyMap()

        val mediaType = normalizeMediaType(meta.apiType.ifBlank { fallbackItemType })
        if (mediaType != "show") return emptyMap()
        val cacheNamespace = episodeRatingsCacheNamespace(meta, fallbackItemId)
        val semaphore = Semaphore(3)

        return coroutineScope {
            episodeTmdbIds.entries
                .groupBy(keySelector = { it.key.first }, valueTransform = { it.key to it.value })
                .map { (season, entries) ->
                    async {
                        semaphore.withPermit {
                            getEpisodeRatingsForSeason(
                                cacheNamespace = cacheNamespace,
                                season = season,
                                apiKey = apiKey,
                                episodeTmdbIds = entries.toMap()
                            )
                        }
                    }
                }
                .awaitAll()
                .fold(emptyMap<Pair<Int, Int>, Double>()) { acc, seasonRatings -> acc + seasonRatings }
        }
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

    private fun enabledProviders(settings: MDBListSettings): List<ProviderType> = buildList {
        if (settings.showTrakt) add(ProviderType.TRAKT)
        if (settings.showImdb) add(ProviderType.IMDB)
        if (settings.showTmdb) add(ProviderType.TMDB)
        if (settings.showLetterboxd) add(ProviderType.LETTERBOXD)
        if (settings.showTomatoes) add(ProviderType.TOMATOES)
        if (settings.showAudience) add(ProviderType.AUDIENCE)
        if (settings.showMetacritic) add(ProviderType.METACRITIC)
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
        val converted = tmdbService.ensureTmdbId(meta.id, lookupType)?.toIntOrNull()?.let { tmdbNumericId ->
            tmdbService.tmdbToImdb(tmdbNumericId, lookupType)
        }
        return converted?.takeIf { it.startsWith("tt") }
    }

    private fun extractImdbId(rawId: String?): String? {
        if (rawId.isNullOrBlank()) return null
        val regex = Regex("tt\\d+")
        return regex.find(rawId)?.value
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
