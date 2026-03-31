package com.nexio.tv.data.trailer

import android.util.Log
import com.nexio.tv.BuildConfig
import com.nexio.tv.core.tmdb.TmdbMetadataService
import com.nexio.tv.data.local.TmdbSettingsDataStore
import com.nexio.tv.data.remote.api.TmdbApi
import com.nexio.tv.data.remote.api.TmdbVideoResult
import com.nexio.tv.data.remote.api.TrailerApi
import com.nexio.tv.core.network.NetworkResult
import com.nexio.tv.data.trailer.helper.TrailerAvailabilityService
import com.nexio.tv.domain.model.Stream
import com.nexio.tv.domain.repository.AddonRepository
import com.nexio.tv.domain.repository.StreamRepository
import java.net.URI
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

private const val TAG = "TrailerService"
private const val STREAILER_ADDON_ID = "org.streailer.trailer"
private const val TMDB_TRAILER_FALLBACK_LANGUAGE = "en-US"
private val YOUTUBE_SOURCE_CACHE_TTL: Duration = Duration.ofHours(3)
private val YOUTUBE_VIDEO_ID_REGEX = Regex("^[a-zA-Z0-9_-]{11}$")

private sealed interface CachedTrailerLookup {
    data object Miss : CachedTrailerLookup
    data class Hit(val result: TrailerResolutionResult) : CachedTrailerLookup
}

private data class CachedTrailerPlaybackSource(
    val playbackSource: TrailerPlaybackSource,
    val cachedAt: Instant,
    val authBacked: Boolean
)

@Singleton
class TrailerService(
    private val trailerApi: TrailerApi,
    private val tmdbApi: TmdbApi,
    private val inAppYouTubeExtractor: InAppYouTubeExtractor,
    private val tmdbSettingsDataStore: TmdbSettingsDataStore,
    private val tmdbMetadataService: TmdbMetadataService,
    private val addonRepository: AddonRepository,
    private val streamRepository: StreamRepository,
    private val trailerAvailabilityService: TrailerAvailabilityService,
    private val clock: Clock
) {
    @Inject
    constructor(
        trailerApi: TrailerApi,
        tmdbApi: TmdbApi,
        inAppYouTubeExtractor: InAppYouTubeExtractor,
        tmdbSettingsDataStore: TmdbSettingsDataStore,
        tmdbMetadataService: TmdbMetadataService,
        addonRepository: AddonRepository,
        streamRepository: StreamRepository,
        trailerAvailabilityService: TrailerAvailabilityService
    ) : this(
        trailerApi = trailerApi,
        tmdbApi = tmdbApi,
        inAppYouTubeExtractor = inAppYouTubeExtractor,
        tmdbSettingsDataStore = tmdbSettingsDataStore,
        tmdbMetadataService = tmdbMetadataService,
        addonRepository = addonRepository,
        streamRepository = streamRepository,
        trailerAvailabilityService = trailerAvailabilityService,
        clock = Clock.systemUTC()
    )

    private val lookupCache = ConcurrentHashMap<String, CachedTrailerLookup>()
    private val youtubeSourceCache = ConcurrentHashMap<String, CachedTrailerPlaybackSource>()

    suspend fun resolveTrailer(
        title: String,
        year: String? = null,
        tmdbId: String? = null,
        type: String? = null,
        seasonNumber: Int? = null,
        contentId: String? = null,
        fallbackYtIds: List<String> = emptyList()
    ): TrailerResolutionResult? = withContext(Dispatchers.IO) {
        val helperSignedIn = trailerAvailabilityService.isSignedIn()
        val cacheKey = listOf(
            title,
            year.orEmpty(),
            tmdbId.orEmpty(),
            type.orEmpty(),
            seasonNumber?.toString().orEmpty(),
            contentId.orEmpty(),
            fallbackYtIds.joinToString(","),
            helperSignedIn.toString()
        ).joinToString("|")

        when (val cached = lookupCache[cacheKey]) {
            is CachedTrailerLookup.Hit -> return@withContext cached.result
            CachedTrailerLookup.Miss -> return@withContext null
            null -> Unit
        }

        val resolved = try {
            resolveTrailerInternal(
                title = title,
                year = year,
                tmdbId = tmdbId,
                type = type,
                seasonNumber = seasonNumber,
                contentId = contentId,
                fallbackYtIds = fallbackYtIds
            )
        } catch (error: Exception) {
            Log.e(TAG, "Error resolving trailer for $title: ${error.message}", error)
            null
        }

        lookupCache[cacheKey] = resolved?.let(CachedTrailerLookup::Hit) ?: CachedTrailerLookup.Miss
        resolved
    }

    suspend fun getTrailerPlaybackSource(
        title: String,
        year: String? = null,
        tmdbId: String? = null,
        type: String? = null,
        seasonNumber: Int? = null,
        contentId: String? = null,
        fallbackYtIds: List<String> = emptyList()
    ): TrailerPlaybackSource? {
        return when (
            val result = resolveTrailer(
                title = title,
                year = year,
                tmdbId = tmdbId,
                type = type,
                seasonNumber = seasonNumber,
                contentId = contentId,
                fallbackYtIds = fallbackYtIds
            )
        ) {
            is TrailerResolutionResult.Playback -> result.source
            else -> null
        }
    }

    suspend fun getTrailerPlaybackSourceFromTmdbId(
        tmdbId: String?,
        type: String?,
        seasonNumber: Int? = null,
        title: String? = null,
        year: String? = null
    ): TrailerPlaybackSource? {
        return when (
            val result = resolveTrailerPlaybackFromTmdbId(
                tmdbId = tmdbId,
                type = type,
                seasonNumber = seasonNumber,
                title = title,
                year = year
            )
        ) {
            is TrailerResolutionResult.Playback -> result.source
            else -> null
        }
    }

    suspend fun getTrailerPlaybackSourceFromYouTubeUrl(
        youtubeUrl: String,
        title: String? = null,
        year: String? = null
    ): TrailerPlaybackSource? {
        return when (
            val result = resolveYouTubeTrailer(
                youtubeUrl = youtubeUrl,
                title = title,
                year = year
            )
        ) {
            is TrailerResolutionResult.Playback -> result.source
            else -> null
        }
    }

    fun clearCache() {
        lookupCache.clear()
        youtubeSourceCache.clear()
    }

    private suspend fun resolveTrailerInternal(
        title: String,
        year: String?,
        tmdbId: String?,
        type: String?,
        seasonNumber: Int?,
        contentId: String?,
        fallbackYtIds: List<String>
    ): TrailerResolutionResult? {
        resolveTrailerPlaybackFromTmdbId(
            tmdbId = tmdbId,
            type = type,
            seasonNumber = seasonNumber,
            title = title,
            year = year
        )?.let { return it }

        fallbackYtIds.asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { ytId ->
                resolveYouTubeTrailer(
                    youtubeUrl = "https://www.youtube.com/watch?v=$ytId",
                    title = title,
                    year = year
                )?.let { return it }
            }

        return resolveStreailerTrailer(
            contentId = contentId,
            type = type,
            title = title,
            year = year
        )
    }

    private suspend fun resolveTrailerPlaybackFromTmdbId(
        tmdbId: String?,
        type: String?,
        seasonNumber: Int? = null,
        title: String? = null,
        year: String? = null
    ): TrailerResolutionResult? = withContext(Dispatchers.IO) {
        val numericTmdbId = tmdbId?.toIntOrNull() ?: return@withContext null
        val mediaType = normalizeTmdbMediaType(type)
        val tmdbLanguage = getPreferredTmdbTrailerLanguage()
        val apiKey = requireTmdbApiKey() ?: return@withContext null

        val tmdbResults = when (mediaType) {
            "movie" -> fetchTmdbMovieVideos(numericTmdbId, tmdbLanguage, apiKey)
            "tv" -> {
                val seasonResults = seasonNumber
                    ?.takeIf { it >= 0 }
                    ?.let { season ->
                        Log.d(TAG, "Trying TMDB season videos for tmdbId=$numericTmdbId season=$season")
                        fetchTmdbSeasonVideos(numericTmdbId, season, tmdbLanguage, apiKey)
                    }
                    .orEmpty()
                if (seasonResults.isNotEmpty()) {
                    Log.d(TAG, "Using TMDB season trailer candidates for tmdbId=$numericTmdbId season=$seasonNumber")
                    seasonResults
                } else {
                    if (seasonNumber != null) {
                        Log.d(TAG, "No TMDB season trailer found for tmdbId=$numericTmdbId season=$seasonNumber, falling back to series videos")
                    }
                    fetchTmdbTvVideos(numericTmdbId, tmdbLanguage, apiKey)
                }
            }
            else -> fetchTmdbMovieVideos(numericTmdbId, tmdbLanguage, apiKey) +
                fetchTmdbTvVideos(numericTmdbId, tmdbLanguage, apiKey)
        }

        for (candidate in rankTmdbVideoCandidates(tmdbResults)) {
            val key = candidate.key?.trim().orEmpty()
            if (key.isBlank()) continue
            resolveYouTubeTrailer(
                youtubeUrl = "https://www.youtube.com/watch?v=$key",
                title = title,
                year = year
            )?.let { return@withContext it }
        }

        null
    }

    private suspend fun resolveStreailerTrailer(
        contentId: String?,
        type: String?,
        title: String?,
        year: String?
    ): TrailerResolutionResult? = withContext(Dispatchers.IO) {
        val normalizedContentId = contentId?.trim()?.takeIf { it.isNotEmpty() } ?: return@withContext null
        val normalizedType = normalizeStreailerType(type) ?: return@withContext null
        val addon = addonRepository.getInstalledAddons().first()
            .firstOrNull { it.id == STREAILER_ADDON_ID }
            ?: return@withContext null

        val streamResult = streamRepository.getStreamsFromAddon(
            baseUrl = addon.baseUrl,
            type = normalizedType,
            videoId = normalizedContentId
        )

        val streams = when (streamResult) {
            is NetworkResult.Success -> streamResult.data
            else -> return@withContext null
        }

        val candidate = selectStreailerTrailerCandidate(streams) ?: return@withContext null
        candidate.youtubeId?.let { ytId ->
            return@withContext resolveYouTubeTrailer(
                youtubeUrl = "https://www.youtube.com/watch?v=$ytId",
                title = title,
                year = year
            )
        }

        val externalUrl = candidate.externalUrl?.trim().orEmpty()
        if (externalUrl.isBlank()) return@withContext null

        return@withContext if (extractYouTubeVideoId(externalUrl) != null) {
            resolveYouTubeTrailer(
                youtubeUrl = externalUrl,
                title = title,
                year = year
            )
        } else {
            null
        }
    }

    private suspend fun resolveYouTubeTrailer(
        youtubeUrl: String,
        title: String?,
        year: String?
    ): TrailerResolutionResult? = withContext(Dispatchers.IO) {
        val helperSignedIn = trailerAvailabilityService.isSignedIn()
        val youtubeKey = extractYouTubeVideoId(youtubeUrl)
        if (!youtubeKey.isNullOrBlank()) {
            getValidCachedYoutubeSource(
                youtubeKey = youtubeKey,
                requireAuthBacked = helperSignedIn
            )?.let { cached ->
                return@withContext TrailerResolutionResult.Playback(cached)
            }
        }

        if (helperSignedIn) {
            val helperSource = trailerAvailabilityService.resolveAuthenticatedYouTubePlayback(youtubeUrl)
            if (helperSource != null) {
                Log.d(TAG, "Resolved $youtubeUrl via authenticated embedded helper")
                if (!youtubeKey.isNullOrBlank()) {
                    youtubeSourceCache[youtubeKey] = CachedTrailerPlaybackSource(
                        playbackSource = helperSource,
                        cachedAt = Instant.now(clock),
                        authBacked = true
                    )
                }
                return@withContext TrailerResolutionResult.Playback(helperSource)
            }
            return@withContext null
        }

        val localSource = runCatching {
            inAppYouTubeExtractor.extractPlaybackSource(youtubeUrl)
        }.getOrNull()
        if (localSource != null) {
            Log.d(TAG, "Resolved $youtubeUrl via local in-app extractor")
            if (!youtubeKey.isNullOrBlank()) {
                youtubeSourceCache[youtubeKey] = CachedTrailerPlaybackSource(
                    playbackSource = localSource,
                    cachedAt = Instant.now(clock),
                    authBacked = false
                )
            }
            return@withContext TrailerResolutionResult.Playback(localSource)
        }

        if (BuildConfig.TRAILER_API_URL.isNotBlank()) {
            val backendUrl = runCatching {
                trailerApi.getTrailer(
                    youtubeUrl = youtubeUrl,
                    title = title,
                    year = year
                )
            }.getOrNull()
                ?.takeIf { it.isSuccessful }
                ?.body()
                ?.url
                ?.takeIf(::isValidUrl)

            if (backendUrl != null) {
                Log.d(TAG, "Resolved $youtubeUrl via trailer backend bridge")
                val playbackSource = TrailerPlaybackSource(videoUrl = backendUrl)
                if (!youtubeKey.isNullOrBlank()) {
                    youtubeSourceCache[youtubeKey] = CachedTrailerPlaybackSource(
                        playbackSource = playbackSource,
                        cachedAt = Instant.now(clock),
                        authBacked = false
                    )
                }
                return@withContext TrailerResolutionResult.Playback(playbackSource)
            }
        }

        null
    }

    private suspend fun fetchTmdbMovieVideos(
        tmdbId: Int,
        preferredLanguage: String,
        apiKey: String
    ): List<TmdbVideoResult> {
        val localized = fetchTmdbMovieVideosOnce(tmdbId, preferredLanguage, apiKey)
        if (localized.isNotEmpty() || preferredLanguage.equals(TMDB_TRAILER_FALLBACK_LANGUAGE, ignoreCase = true)) {
            return localized
        }
        return fetchTmdbMovieVideosOnce(tmdbId, TMDB_TRAILER_FALLBACK_LANGUAGE, apiKey)
    }

    private suspend fun fetchTmdbTvVideos(
        tmdbId: Int,
        preferredLanguage: String,
        apiKey: String
    ): List<TmdbVideoResult> {
        val localized = fetchTmdbTvVideosOnce(tmdbId, preferredLanguage, apiKey)
        if (localized.isNotEmpty() || preferredLanguage.equals(TMDB_TRAILER_FALLBACK_LANGUAGE, ignoreCase = true)) {
            return localized
        }
        return fetchTmdbTvVideosOnce(tmdbId, TMDB_TRAILER_FALLBACK_LANGUAGE, apiKey)
    }

    private suspend fun fetchTmdbSeasonVideos(
        tmdbId: Int,
        seasonNumber: Int,
        preferredLanguage: String,
        apiKey: String
    ): List<TmdbVideoResult> {
        val localized = fetchTmdbSeasonVideosOnce(tmdbId, seasonNumber, preferredLanguage, apiKey)
        if (localized.isNotEmpty() || preferredLanguage.equals(TMDB_TRAILER_FALLBACK_LANGUAGE, ignoreCase = true)) {
            return localized
        }
        return fetchTmdbSeasonVideosOnce(tmdbId, seasonNumber, TMDB_TRAILER_FALLBACK_LANGUAGE, apiKey)
    }

    private suspend fun fetchTmdbMovieVideosOnce(
        tmdbId: Int,
        language: String,
        apiKey: String
    ): List<TmdbVideoResult> {
        return try {
            val response = tmdbApi.getMovieVideos(
                movieId = tmdbId,
                apiKey = apiKey,
                language = language
            )
            if (response.isSuccessful) response.body()?.results.orEmpty() else emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private suspend fun fetchTmdbTvVideosOnce(
        tmdbId: Int,
        language: String,
        apiKey: String
    ): List<TmdbVideoResult> {
        return try {
            val response = tmdbApi.getTvVideos(
                tvId = tmdbId,
                apiKey = apiKey,
                language = language
            )
            if (response.isSuccessful) response.body()?.results.orEmpty() else emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private suspend fun fetchTmdbSeasonVideosOnce(
        tmdbId: Int,
        seasonNumber: Int,
        language: String,
        apiKey: String
    ): List<TmdbVideoResult> {
        return try {
            val response = tmdbApi.getTvSeasonVideos(
                tvId = tmdbId,
                seasonNumber = seasonNumber,
                apiKey = apiKey,
                language = language
            )
            if (response.isSuccessful) response.body()?.results.orEmpty() else emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private suspend fun getPreferredTmdbTrailerLanguage(): String {
        return normalizeTmdbTrailerLanguage(tmdbMetadataService.currentTmdbLanguageTag())
    }

    private suspend fun requireTmdbApiKey(): String? {
        val apiKey = tmdbSettingsDataStore.settings.first().apiKey.trim()
        return apiKey.takeIf { it.isNotBlank() }
    }

    private fun getValidCachedYoutubeSource(
        youtubeKey: String,
        requireAuthBacked: Boolean
    ): TrailerPlaybackSource? {
        val cached = youtubeSourceCache[youtubeKey] ?: return null
        if (requireAuthBacked && !cached.authBacked) {
            youtubeSourceCache.remove(youtubeKey, cached)
            return null
        }
        val age = Duration.between(cached.cachedAt, Instant.now(clock))
        if (age <= YOUTUBE_SOURCE_CACHE_TTL) {
            return cached.playbackSource
        }
        youtubeSourceCache.remove(youtubeKey, cached)
        return null
    }
}

internal fun normalizeTmdbTrailerLanguage(language: String?): String {
    val normalized = language
        ?.trim()
        ?.replace('_', '-')
        ?.takeIf { it.isNotBlank() }
        ?: return TMDB_TRAILER_FALLBACK_LANGUAGE

    if (normalized.contains('-')) {
        val parts = normalized.split("-", limit = 2)
        val locale = parts[0].lowercase()
        val region = parts.getOrNull(1)?.uppercase()?.takeIf { it.isNotBlank() }
        return if (region != null) "$locale-$region" else locale
    }

    if (normalized.equals("en", ignoreCase = true)) return TMDB_TRAILER_FALLBACK_LANGUAGE
    return normalized.lowercase()
}

internal fun normalizeTmdbMediaType(type: String?): String? {
    return when (type?.trim()?.lowercase()) {
        "movie", "film" -> "movie"
        "tv", "series", "show", "tvshow" -> "tv"
        else -> null
    }
}

internal fun normalizeStreailerType(type: String?): String? {
    return when (type?.trim()?.lowercase()) {
        "movie", "film" -> "movie"
        "tv", "series", "show", "tvshow" -> "series"
        else -> null
    }
}

internal fun rankTmdbVideoCandidates(results: List<TmdbVideoResult>): List<TmdbVideoResult> {
    return results
        .asSequence()
        .filter { (it.site ?: "").equals("YouTube", ignoreCase = true) }
        .filter { !it.key.isNullOrBlank() }
        .filter {
            when (it.type?.trim()?.lowercase()) {
                "trailer", "teaser" -> true
                else -> false
            }
        }
        .sortedWith(
            compareBy<TmdbVideoResult> { videoTypePriority(it.type) }
                .thenBy { if (it.official == true) 0 else 1 }
                .thenByDescending { it.size ?: 0 }
                .thenByDescending { parsePublishedAtEpoch(it.publishedAt) }
        )
        .toList()
}

internal fun selectStreailerTrailerCandidate(streams: List<Stream>): StreailerTrailerCandidate? {
    return streams
        .asSequence()
        .filterNot(::isRecapStream)
        .sortedBy(::streailerTrailerPriority)
        .mapNotNull { stream ->
            when {
                !stream.ytId.isNullOrBlank() -> StreailerTrailerCandidate(youtubeId = stream.ytId)
                !stream.externalUrl.isNullOrBlank() -> StreailerTrailerCandidate(externalUrl = stream.externalUrl)
                else -> null
            }
        }
        .firstOrNull()
}

internal fun extractYouTubeVideoId(input: String): String? {
    val trimmed = input.trim()
    if (trimmed.matches(YOUTUBE_VIDEO_ID_REGEX)) return trimmed

    return runCatching {
        val uri = URI(trimmed)
        val host = uri.host?.lowercase()?.removePrefix("www.") ?: return@runCatching null
        when {
            host == "youtu.be" -> {
                val id = uri.path?.trim('/')?.substringBefore('/')?.trim().orEmpty()
                id.takeIf { it.matches(YOUTUBE_VIDEO_ID_REGEX) }
            }

            host == "youtube.com" || host.endsWith(".youtube.com") -> {
                val path = uri.path.orEmpty()
                val query = uri.rawQuery.orEmpty()

                if (path.startsWith("/watch")) {
                    query.split("&")
                        .asSequence()
                        .mapNotNull { entry ->
                            val index = entry.indexOf('=')
                            if (index <= 0) return@mapNotNull null
                            val key = entry.substring(0, index)
                            val value = entry.substring(index + 1)
                            if (key == "v") value else null
                        }
                        .firstOrNull { it.matches(YOUTUBE_VIDEO_ID_REGEX) }
                } else {
                    val segments = path.trim('/').split("/")
                    val candidate = when (segments.firstOrNull()?.lowercase()) {
                        "embed", "shorts", "live" -> segments.getOrNull(1)
                        else -> null
                    }
                    candidate?.takeIf { it.matches(YOUTUBE_VIDEO_ID_REGEX) }
                }
            }

            else -> null
        }
    }.getOrNull()
}

private fun streailerTrailerPriority(stream: Stream): Int {
    val bingeGroup = stream.behaviorHints?.bingeGroup?.trim()?.lowercase()
    val combinedText = listOf(stream.name, stream.title, stream.description)
        .joinToString(" ")
        .lowercase()

    return when {
        bingeGroup == "trailer" -> 0
        "trailer" in combinedText -> 1
        else -> 2
    }
}

private fun isRecapStream(stream: Stream): Boolean {
    val bingeGroup = stream.behaviorHints?.bingeGroup?.trim()?.lowercase()
    if (bingeGroup == "recap") return true
    val combinedText = listOf(stream.name, stream.title, stream.description)
        .joinToString(" ")
        .lowercase()
    return "recap" in combinedText
}

private fun isValidUrl(url: String?): Boolean {
    return !url.isNullOrBlank() && (url.startsWith("http://") || url.startsWith("https://"))
}

private fun videoTypePriority(type: String?): Int {
    return when (type?.trim()?.lowercase()) {
        "trailer" -> 0
        "teaser" -> 1
        else -> 2
    }
}

private fun parsePublishedAtEpoch(value: String?): Long {
    if (value.isNullOrBlank()) return Long.MIN_VALUE
    return runCatching { Instant.parse(value).toEpochMilli() }.getOrDefault(Long.MIN_VALUE)
}
