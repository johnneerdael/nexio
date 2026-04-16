package com.nexio.tv.data.trailer

import android.util.Log
import com.nexio.tv.BuildConfig
import com.nexio.tv.core.metadata.MetadataApiKeyResolver
import com.nexio.tv.core.tmdb.TmdbMetadataService
import com.nexio.tv.core.tvdb.TvdbTrailerLookupResult
import com.nexio.tv.core.tvdb.TvdbTrailerResolver
import com.nexio.tv.data.local.MetadataDiskCacheStore
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
private fun trailerDebugLog(message: String) {
    if (!BuildConfig.DEBUG) return
    runCatching { Log.d(TAG, message) }
}

private fun trailerWarnLog(message: String) {
    if (!BuildConfig.DEBUG) return
    runCatching { Log.w(TAG, message) }
}

private const val STREAILER_ADDON_ID = "org.streailer.trailer"
private const val TMDB_TRAILER_FALLBACK_LANGUAGE = "en-US"
private const val TMDB_TRAILER_CACHE_PROVIDER = "trailer"
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
    private val metadataDiskCacheStore: MetadataDiskCacheStore,
    private val tmdbMetadataService: TmdbMetadataService,
    private val addonRepository: AddonRepository,
    private val streamRepository: StreamRepository,
    private val trailerAvailabilityService: TrailerAvailabilityService,
    private val clock: Clock,
    private val metadataApiKeyResolver: MetadataApiKeyResolver? = null,
    private val tvdbTrailerResolver: TvdbTrailerResolver? = null
) {
    @Inject
    constructor(
        trailerApi: TrailerApi,
        tmdbApi: TmdbApi,
        inAppYouTubeExtractor: InAppYouTubeExtractor,
        tmdbSettingsDataStore: TmdbSettingsDataStore,
        metadataDiskCacheStore: MetadataDiskCacheStore,
        tmdbMetadataService: TmdbMetadataService,
        addonRepository: AddonRepository,
        streamRepository: StreamRepository,
        trailerAvailabilityService: TrailerAvailabilityService,
        metadataApiKeyResolver: MetadataApiKeyResolver,
        tvdbTrailerResolver: TvdbTrailerResolver
    ) : this(
        trailerApi = trailerApi,
        tmdbApi = tmdbApi,
        inAppYouTubeExtractor = inAppYouTubeExtractor,
        tmdbSettingsDataStore = tmdbSettingsDataStore,
        metadataDiskCacheStore = metadataDiskCacheStore,
        tmdbMetadataService = tmdbMetadataService,
        addonRepository = addonRepository,
        streamRepository = streamRepository,
        trailerAvailabilityService = trailerAvailabilityService,
        clock = Clock.systemUTC(),
        metadataApiKeyResolver = metadataApiKeyResolver,
        tvdbTrailerResolver = tvdbTrailerResolver
    )

    private val lookupCache = ConcurrentHashMap<String, CachedTrailerLookup>()
    private val youtubeSourceCache = ConcurrentHashMap<String, CachedTrailerPlaybackSource>()
    // Process-lifetime cache: tmdbId (Int) → latest aired season number, or -1 if none found / detection failed.
    // ConcurrentHashMap does not accept null values, so -1 is used as a sentinel for "computed but no result".
    private val tvLatestAiredSeasonCache = ConcurrentHashMap<Int, Int>()

    fun invalidateLookupCache(
        title: String,
        year: String? = null,
        tmdbId: String? = null,
        type: String? = null,
        seasonNumber: Int? = null,
        contentId: String? = null,
        fallbackYtIds: List<String> = emptyList()
    ) {
        val cacheKeyPrefix = buildLookupCachePrefix(
            title = title,
            year = year,
            tmdbId = tmdbId,
            type = type,
            seasonNumber = seasonNumber,
            contentId = contentId,
            fallbackYtIds = fallbackYtIds
        )
        lookupCache.keys.removeIf { it.startsWith(cacheKeyPrefix) }
    }

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
        val tmdbLanguage = getPreferredTmdbTrailerLanguage()
        val tmdbApiKey = tmdbSettingsDataStore.settings.first().apiKey.trim()

        // For TV shows with no explicit season, auto-detect the latest aired season before building
        // the cache key so the key encodes the actual season. When a new season airs the detected
        // number changes, the key changes, and the cache misses correctly.
        val effectiveSeasonNumber: Int? = if (
            seasonNumber == null &&
            !tmdbId.isNullOrBlank() &&
            normalizeTmdbMediaType(type) == "tv"
        ) {
            val numericId = tmdbId.toIntOrNull()
            if (numericId != null && tmdbApiKey.isNotBlank()) {
                resolveLatestAiredSeasonNumber(numericId, tmdbApiKey).also { detected ->
                    if (detected != null) {
                        Log.d(TAG, "Auto-detected latest aired season=$detected for tmdbId=$tmdbId")
                    }
                }
            } else null
        } else {
            seasonNumber
        }

        val cacheKey = buildLookupCacheKey(
            title = title,
            year = year,
            tmdbId = tmdbId,
            type = type,
            seasonNumber = effectiveSeasonNumber,
            contentId = contentId,
            fallbackYtIds = fallbackYtIds,
            helperSignedIn = helperSignedIn,
            tmdbLanguage = tmdbLanguage,
            tmdbApiKey = tmdbApiKey
        )

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
                seasonNumber = effectiveSeasonNumber,
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

    internal suspend fun getSeasonMediaAvailability(
        tmdbId: String?,
        type: String?,
        seasonNumber: Int?,
        contentId: String?
    ): SeasonMediaAvailability = withContext(Dispatchers.IO) {
        val normalizedSeason = seasonNumber?.takeIf { it >= 0 } ?: return@withContext SeasonMediaAvailability()
        val numericTmdbId = tmdbId?.toIntOrNull()
        val mediaType = normalizeTmdbMediaType(type)
        val apiKey = requireTmdbApiKey()
        val tmdbLanguage = getPreferredTmdbTrailerLanguage()

        val seasonTmdbVideos = if (numericTmdbId != null && mediaType == "tv" && apiKey != null) {
            fetchTmdbSeasonVideos(numericTmdbId, normalizedSeason, tmdbLanguage, apiKey)
        } else {
            emptyList()
        }
        val streams = fetchStreailerStreams(contentId = contentId, type = type).orEmpty()
        val seasonStreailerTrailer = selectSeasonStreailerTrailerCandidate(streams, normalizedSeason)
        val seasonStreailerRecap = selectSeasonStreailerRecapCandidate(streams, normalizedSeason)

        SeasonMediaAvailability(
            hasTrailerOrTeaser = rankTmdbVideoCandidates(seasonTmdbVideos).isNotEmpty() ||
                hasInternalStreailerCandidate(seasonStreailerTrailer),
            hasRecap = rankTmdbRecapCandidates(seasonTmdbVideos).isNotEmpty() ||
                hasInternalStreailerCandidate(seasonStreailerRecap)
        )
    }

    internal suspend fun getTitleMediaAvailability(
        tmdbId: String?,
        type: String?,
        contentId: String?,
        fallbackYtIds: List<String> = emptyList()
    ): Boolean = withContext(Dispatchers.IO) {
        val fallbackYtCount = fallbackYtIds.count { it.isNotBlank() }
        trailerDebugLog("getTitleMediaAvailability start tmdbId=$tmdbId type=$type contentId=$contentId fallbackYtIds=$fallbackYtCount")

        val mediaType = normalizeTmdbMediaType(type)

        // For TV: check TVDB first before TMDB per D-05
        if (mediaType == "tv") {
            val tvdbResult = tvdbTrailerResolver?.resolveTitleTrailer(
                contentId = contentId,
                type = type,
                title = null,
                year = null
            )
            if (tvdbResult is TvdbTrailerLookupResult.Resolved || tvdbResult is TvdbTrailerLookupResult.ResolvedYouTube) {
                trailerDebugLog("getTitleMediaAvailability resolved via tvdb contentId=$contentId")
                return@withContext true
            }

            val streailerCandidate = fetchStreailerStreams(contentId = contentId, type = type)
                ?.let(::selectStreailerTrailerCandidate)
            if (hasInternalStreailerCandidate(streailerCandidate)) {
                trailerDebugLog("getTitleMediaAvailability resolved via streailer contentId=$contentId")
                return@withContext true
            }

            if (fallbackYtCount > 0) {
                trailerDebugLog("getTitleMediaAvailability resolved via fallbackYtIds contentId=$contentId")
                return@withContext true
            }

            // TMDB TV videos as last resort
            val numericTmdbId = tmdbId?.toIntOrNull()
            val apiKey = requireTmdbApiKey()
            val tmdbLanguage = getPreferredTmdbTrailerLanguage()
            if (numericTmdbId != null && apiKey != null) {
                val tmdbTvVideos = fetchTmdbTvVideos(numericTmdbId, tmdbLanguage, apiKey)
                if (rankTmdbVideoCandidates(tmdbTvVideos).isNotEmpty()) {
                    trailerDebugLog("getTitleMediaAvailability resolved via tmdb tv fallback tmdbId=$numericTmdbId")
                    return@withContext true
                }
            }

            trailerDebugLog("getTitleMediaAvailability not available contentId=$contentId")
            return@withContext false
        }

        // Movie/other: existing order (TMDB first)
        val numericTmdbId = tmdbId?.toIntOrNull()
        val apiKey = requireTmdbApiKey()
        val tmdbLanguage = getPreferredTmdbTrailerLanguage()

        val tmdbTitleVideos = when {
            numericTmdbId == null || apiKey == null -> emptyList()
            mediaType == "movie" -> fetchTmdbMovieVideos(numericTmdbId, tmdbLanguage, apiKey)
            else -> emptyList()
        }
        if (rankTmdbVideoCandidates(tmdbTitleVideos).isNotEmpty()) {
            trailerDebugLog(
                "getTitleMediaAvailability resolved via tmdb videos tmdbId=$numericTmdbId mediaType=$mediaType total=${tmdbTitleVideos.size} ranked=${rankTmdbVideoCandidates(tmdbTitleVideos).size}"
            )
            return@withContext true
        }

        val streailerCandidate = fetchStreailerStreams(contentId = contentId, type = type)
            ?.let(::selectStreailerTrailerCandidate)
        if (hasInternalStreailerCandidate(streailerCandidate)) {
            trailerDebugLog("getTitleMediaAvailability resolved via streailer contentId=$contentId")
            return@withContext true
        }

        if (fallbackYtCount > 0) {
            trailerDebugLog("getTitleMediaAvailability resolved via fallbackYtIds contentId=$contentId")
            return@withContext true
        }

        trailerDebugLog("getTitleMediaAvailability not available contentId=$contentId")
        false
    }

    internal suspend fun getSeasonTrailerPlaybackSource(
        title: String,
        year: String? = null,
        tmdbId: String? = null,
        type: String? = null,
        seasonNumber: Int? = null,
        contentId: String? = null
    ): TrailerPlaybackSource? {
        return when (
            val result = resolveSeasonTrailer(
                title = title,
                year = year,
                tmdbId = tmdbId,
                type = type,
                seasonNumber = seasonNumber,
                contentId = contentId
            )
        ) {
            is TrailerResolutionResult.Playback -> result.source
            else -> null
        }
    }

    internal suspend fun getSeasonRecapPlaybackSource(
        title: String,
        year: String? = null,
        tmdbId: String? = null,
        type: String? = null,
        seasonNumber: Int? = null,
        contentId: String? = null
    ): TrailerPlaybackSource? {
        return when (
            val result = resolveSeasonRecap(
                title = title,
                year = year,
                tmdbId = tmdbId,
                type = type,
                seasonNumber = seasonNumber,
                contentId = contentId
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
        tvLatestAiredSeasonCache.clear()
    }

    private fun buildLookupCacheKey(
        title: String,
        year: String?,
        tmdbId: String?,
        type: String?,
        seasonNumber: Int?,
        contentId: String?,
        fallbackYtIds: List<String>,
        helperSignedIn: Boolean,
        tmdbLanguage: String,
        tmdbApiKey: String
    ): String {
        return buildString {
            append(buildLookupCachePrefix(title, year, tmdbId, type, seasonNumber, contentId, fallbackYtIds))
            append('|')
            append(helperSignedIn)
            append('|')
            append(tmdbLanguage)
            append('|')
            append(tmdbApiKey)
        }
    }

    private fun buildLookupCachePrefix(
        title: String,
        year: String?,
        tmdbId: String?,
        type: String?,
        seasonNumber: Int?,
        contentId: String?,
        fallbackYtIds: List<String>
    ): String {
        return listOf(
            title,
            year.orEmpty(),
            tmdbId.orEmpty(),
            type.orEmpty(),
            seasonNumber?.toString().orEmpty(),
            contentId.orEmpty(),
            fallbackYtIds.joinToString(",")
        ).joinToString("|")
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
        val isTv = normalizeTmdbMediaType(type) == "tv"

        if (isTv) {
            // TV fallback order per D-07: TVDB -> Streailer -> fallback YT IDs -> explicit TMDB
            return resolveTvTrailerInternal(
                title = title,
                year = year,
                tmdbId = tmdbId,
                type = type,
                seasonNumber = seasonNumber,
                contentId = contentId,
                fallbackYtIds = fallbackYtIds
            )
        }

        // Movie ordering unchanged: TMDB -> fallback YT IDs -> Streailer
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

    private suspend fun resolveTvTrailerInternal(
        title: String,
        year: String?,
        tmdbId: String?,
        type: String?,
        seasonNumber: Int?,
        contentId: String?,
        fallbackYtIds: List<String>
    ): TrailerResolutionResult? {
        // 1. TVDB title trailer
        val tvdbResult = tvdbTrailerResolver?.resolveTitleTrailer(
            contentId = contentId,
            type = type,
            title = title,
            year = year
        )
        when (tvdbResult) {
            is TvdbTrailerLookupResult.ResolvedYouTube -> {
                resolveYouTubeTrailer(
                    youtubeUrl = tvdbResult.youtubeUrl,
                    title = title,
                    year = year
                )?.let { return it }
            }
            is TvdbTrailerLookupResult.Resolved -> return tvdbResult.result
            else -> Unit
        }

        // 2. Streailer
        resolveStreailerTrailer(
            contentId = contentId,
            type = type,
            title = title,
            year = year
        )?.let { return it }

        // 3. Fallback YouTube IDs
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

        // 4. Explicit TMDB fallback (last resort for TV)
        trailerDebugLog("tmdb_trailer_fallback contentId=$contentId tmdbId=$tmdbId")
        return resolveTrailerPlaybackFromTmdbId(
            tmdbId = tmdbId,
            type = type,
            seasonNumber = seasonNumber,
            title = title,
            year = year
        )
    }

    private suspend fun resolveSeasonTrailer(
        title: String,
        year: String?,
        tmdbId: String?,
        type: String?,
        seasonNumber: Int?,
        contentId: String?
    ): TrailerResolutionResult? = withContext(Dispatchers.IO) {
        val normalizedSeason = seasonNumber?.takeIf { it >= 0 } ?: return@withContext null
        val numericTmdbId = tmdbId?.toIntOrNull()
        val mediaType = normalizeTmdbMediaType(type)
        val apiKey = requireTmdbApiKey()
        val tmdbLanguage = getPreferredTmdbTrailerLanguage()

        if (numericTmdbId != null && mediaType == "tv" && apiKey != null) {
            val seasonResults = fetchTmdbSeasonVideos(numericTmdbId, normalizedSeason, tmdbLanguage, apiKey)
            for (candidate in rankTmdbVideoCandidates(seasonResults)) {
                val key = candidate.key?.trim().orEmpty()
                if (key.isBlank()) continue
                resolveYouTubeTrailer(
                    youtubeUrl = "https://www.youtube.com/watch?v=$key",
                    title = title,
                    year = year
                )?.let { return@withContext it }
            }
        }

        val streailerCandidate = fetchStreailerStreams(contentId = contentId, type = type)
            ?.let { selectSeasonStreailerTrailerCandidate(it, normalizedSeason) }

        streailerCandidate?.youtubeId?.let { ytId ->
            return@withContext resolveYouTubeTrailer(
                youtubeUrl = "https://www.youtube.com/watch?v=$ytId",
                title = title,
                year = year
            )
        }

        val externalUrl = streailerCandidate?.externalUrl?.trim().orEmpty()
        if (externalUrl.isNotBlank() && extractYouTubeVideoId(externalUrl) != null) {
            return@withContext resolveYouTubeTrailer(
                youtubeUrl = externalUrl,
                title = title,
                year = year
            )
        }

        null
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
                val rankedSeasonResults = rankTmdbVideoCandidates(seasonResults)
                if (rankedSeasonResults.isNotEmpty()) {
                    Log.d(TAG, "Using TMDB season trailer candidates for tmdbId=$numericTmdbId season=$seasonNumber")
                    rankedSeasonResults
                } else {
                    if (seasonNumber != null && seasonResults.isNotEmpty()) {
                        Log.d(TAG, "TMDB season videos for tmdbId=$numericTmdbId season=$seasonNumber had no trailer/teaser candidates, falling back to series videos")
                    } else if (seasonNumber != null) {
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
        val streams = fetchStreailerStreams(contentId = contentId, type = type) ?: return@withContext null

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

    private suspend fun resolveSeasonRecap(
        title: String,
        year: String?,
        tmdbId: String?,
        type: String?,
        seasonNumber: Int?,
        contentId: String?
    ): TrailerResolutionResult? = withContext(Dispatchers.IO) {
        val numericTmdbId = tmdbId?.toIntOrNull()
        val normalizedSeason = seasonNumber?.takeIf { it >= 0 } ?: return@withContext null
        val mediaType = normalizeTmdbMediaType(type)
        val tmdbLanguage = getPreferredTmdbTrailerLanguage()
        val apiKey = requireTmdbApiKey()

        val tmdbRecapResults = if (numericTmdbId != null && mediaType == "tv" && apiKey != null) {
            fetchTmdbSeasonVideos(numericTmdbId, normalizedSeason, tmdbLanguage, apiKey)
        } else {
            emptyList()
        }
        val streailerRecap = fetchStreailerStreams(contentId = contentId, type = type)
            ?.let { selectSeasonStreailerRecapCandidate(it, normalizedSeason) }

        for (candidate in orderedSeasonRecapCandidates(tmdbRecapResults, streailerRecap)) {
            when (candidate) {
                is SeasonMediaCandidate.TmdbYouTube -> {
                    resolveYouTubeTrailer(
                        youtubeUrl = "https://www.youtube.com/watch?v=${candidate.youtubeId}",
                        title = title,
                        year = year
                    )?.let { return@withContext it }
                }

                is SeasonMediaCandidate.Streailer -> {
                    val streailerCandidate = candidate.candidate
                    val resolved = when {
                        !streailerCandidate.youtubeId.isNullOrBlank() -> resolveYouTubeTrailer(
                            youtubeUrl = "https://www.youtube.com/watch?v=${streailerCandidate.youtubeId}",
                            title = title,
                            year = year
                        )

                        else -> {
                            val youtubeId = extractYouTubeVideoId(streailerCandidate.externalUrl.orEmpty())
                            youtubeId?.let {
                                resolveYouTubeTrailer(
                                    youtubeUrl = "https://www.youtube.com/watch?v=$it",
                                    title = title,
                                    year = year
                                )
                            }
                        }
                    }
                    if (resolved != null) return@withContext resolved
                }
            }
        }

        null
    }

    private suspend fun fetchStreailerStreams(
        contentId: String?,
        type: String?
    ): List<Stream>? = withContext(Dispatchers.IO) {
        val normalizedContentId = contentId?.trim()?.takeIf { it.isNotEmpty() } ?: return@withContext null
        val normalizedType = normalizeStreailerType(type) ?: return@withContext null
        val addon = addonRepository.getInstalledAddons().first()
            .firstOrNull { it.id == STREAILER_ADDON_ID }
            ?: return@withContext null

        when (
            val streamResult = streamRepository.getStreamsFromAddon(
                baseUrl = addon.baseUrl,
                type = normalizedType,
                videoId = normalizedContentId
            )
        ) {
            is NetworkResult.Success -> streamResult.data
            else -> null
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
            getValidCachedYoutubeSource(youtubeKey)?.let { cached ->
                trailerDebugLog(
                    "resolveYouTubeTrailer cache hit key=$youtubeKey authBacked=${cached.authBacked}"
                )
                return@withContext TrailerResolutionResult.Playback(cached.playbackSource)
            }
        }

        val localSource = try {
            inAppYouTubeExtractor.extractPlaybackSource(youtubeUrl)
        } catch (error: NonEnglishYouTubeTrailerException) {
            trailerDebugLog(
                "resolveYouTubeTrailer rejected non-english url=$youtubeUrl " +
                    "language=${error.languageCode.orEmpty()}"
            )
            Log.w(
                TAG,
                "Rejected non-English YouTube trailer $youtubeUrl " +
                    "language=${error.languageCode.orEmpty()} title=${error.trailerTitle.orEmpty()}"
            )
            return@withContext null
        } catch (_: Exception) {
            null
        }
        if (localSource != null) {
            trailerDebugLog("resolveYouTubeTrailer native success url=$youtubeUrl")
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
        trailerDebugLog("resolveYouTubeTrailer native miss url=$youtubeUrl")

        if (helperSignedIn) {
            val helperSource = trailerAvailabilityService.resolveAuthenticatedYouTubePlayback(youtubeUrl)
            if (helperSource != null) {
                trailerDebugLog("resolveYouTubeTrailer helper success url=$youtubeUrl")
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
            trailerDebugLog("resolveYouTubeTrailer helper miss url=$youtubeUrl")
            Log.w(
                TAG,
                "Authenticated embedded helper did not resolve $youtubeUrl, " +
                    "falling back to backend/public trailer resolution"
            )
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
                trailerDebugLog("resolveYouTubeTrailer backend success url=$youtubeUrl")
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
            trailerDebugLog("resolveYouTubeTrailer backend miss url=$youtubeUrl")
        }

        null
    }

    private suspend fun fetchTmdbMovieVideos(
        tmdbId: Int,
        preferredLanguage: String,
        apiKey: String
    ): List<TmdbVideoResult> {
        val localized = fetchTmdbMovieVideosOnce(tmdbId, preferredLanguage, apiKey)
        if (preferredLanguage.equals(TMDB_TRAILER_FALLBACK_LANGUAGE, ignoreCase = true)) {
            return localized
        }
        if (rankTmdbVideoCandidates(localized).isNotEmpty()) {
            return localized
        }
        return fetchTmdbMovieVideosOnce(tmdbId, TMDB_TRAILER_FALLBACK_LANGUAGE, apiKey)
    }

    private suspend fun resolveLatestAiredSeasonNumber(
        tmdbId: Int,
        apiKey: String
    ): Int? {
        tvLatestAiredSeasonCache[tmdbId]?.let { cached -> return if (cached >= 0) cached else null }
        val result = try {
            val seasons = tmdbApi.getTvDetails(tvId = tmdbId, apiKey = apiKey)
                .body()?.seasons.orEmpty()
            val today = java.time.LocalDate.now(clock.zone)
            seasons
                .filter { season ->
                    val num = season.seasonNumber ?: return@filter false
                    if (num <= 0) return@filter false
                    val count = season.episodeCount ?: return@filter false
                    if (count <= 0) return@filter false
                    val airDate = season.airDate?.takeIf { it.isNotBlank() } ?: return@filter false
                    runCatching { java.time.LocalDate.parse(airDate) <= today }.getOrElse { false }
                }
                .maxByOrNull { it.seasonNumber!! }
                ?.seasonNumber
        } catch (_: Exception) {
            null
        }
        tvLatestAiredSeasonCache[tmdbId] = result ?: -1
        return result
    }

    private suspend fun fetchTmdbTvVideos(
        tmdbId: Int,
        preferredLanguage: String,
        apiKey: String
    ): List<TmdbVideoResult> {
        val localized = fetchTmdbTvVideosOnce(tmdbId, preferredLanguage, apiKey)
        if (preferredLanguage.equals(TMDB_TRAILER_FALLBACK_LANGUAGE, ignoreCase = true)) {
            return localized
        }
        if (rankTmdbVideoCandidates(localized).isNotEmpty()) {
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
        metadataDiskCacheStore.readTmdbTitleVideos(
            tmdbId = tmdbId,
            mediaType = "movie",
            languageTag = language,
            providerToken = TMDB_TRAILER_CACHE_PROVIDER
        )?.let {
            trailerDebugLog("TMDB movie videos cache hit tmdbId=$tmdbId language=$language count=${it.size}")
            return it
        }

        return try {
            val response = tmdbApi.getMovieVideos(
                movieId = tmdbId,
                apiKey = apiKey,
                language = language
            )
            if (!response.isSuccessful) {
                trailerDebugLog("TMDB movie videos fetch failed tmdbId=$tmdbId language=$language http=${response.code()}")
                emptyList()
            } else {
                val results = filterCacheableTmdbTrailerVideos(response.body()?.results.orEmpty())
                trailerDebugLog("TMDB movie videos fetch tmdbId=$tmdbId language=$language count=${results.size}")
                metadataDiskCacheStore.writeTmdbTitleVideos(
                    tmdbId = tmdbId,
                    mediaType = "movie",
                    languageTag = language,
                    providerToken = TMDB_TRAILER_CACHE_PROVIDER,
                    videos = results
                )
                results
            }
        } catch (error: Exception) {
            trailerWarnLog("TMDB movie videos fetch exception tmdbId=$tmdbId language=$language: ${error.message}")
            emptyList()
        }
    }

    private suspend fun fetchTmdbTvVideosOnce(
        tmdbId: Int,
        language: String,
        apiKey: String
    ): List<TmdbVideoResult> {
        metadataDiskCacheStore.readTmdbTitleVideos(
            tmdbId = tmdbId,
            mediaType = "tv",
            languageTag = language,
            providerToken = TMDB_TRAILER_CACHE_PROVIDER
        )?.let {
            trailerDebugLog("TMDB tv videos cache hit tmdbId=$tmdbId language=$language count=${it.size}")
            return it
        }

        return try {
            val response = tmdbApi.getTvVideos(
                tvId = tmdbId,
                apiKey = apiKey,
                language = language
            )
            if (!response.isSuccessful) {
                trailerDebugLog("TMDB tv videos fetch failed tmdbId=$tmdbId language=$language http=${response.code()}")
                emptyList()
            } else {
                val results = filterCacheableTmdbTrailerVideos(response.body()?.results.orEmpty())
                trailerDebugLog("TMDB tv videos fetch tmdbId=$tmdbId language=$language count=${results.size}")
                metadataDiskCacheStore.writeTmdbTitleVideos(
                    tmdbId = tmdbId,
                    mediaType = "tv",
                    languageTag = language,
                    providerToken = TMDB_TRAILER_CACHE_PROVIDER,
                    videos = results
                )
                results
            }
        } catch (error: Exception) {
            trailerWarnLog("TMDB tv videos fetch exception tmdbId=$tmdbId language=$language: ${error.message}")
            emptyList()
        }
    }

    private suspend fun fetchTmdbSeasonVideosOnce(
        tmdbId: Int,
        seasonNumber: Int,
        language: String,
        apiKey: String
    ): List<TmdbVideoResult> {
        metadataDiskCacheStore.readTmdbSeasonVideos(
            tmdbId = tmdbId,
            seasonNumber = seasonNumber,
            languageTag = language,
            providerToken = TMDB_TRAILER_CACHE_PROVIDER
        )?.let { return it }

        return try {
            val response = tmdbApi.getTvSeasonVideos(
                tvId = tmdbId,
                seasonNumber = seasonNumber,
                apiKey = apiKey,
                language = language
            )
            if (!response.isSuccessful) {
                emptyList()
            } else {
                val results = filterCacheableTmdbTrailerVideos(response.body()?.results.orEmpty())
                metadataDiskCacheStore.writeTmdbSeasonVideos(
                    tmdbId = tmdbId,
                    seasonNumber = seasonNumber,
                    languageTag = language,
                    providerToken = TMDB_TRAILER_CACHE_PROVIDER,
                    videos = results
                )
                results
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun getPreferredTmdbTrailerLanguage(): String {
        return normalizeTmdbTrailerLanguage(
            runCatching { tmdbMetadataService.currentTmdbLanguageTag() }.getOrNull()
        )
    }

    private suspend fun requireTmdbApiKey(): String? {
        metadataApiKeyResolver?.tmdbCredential()?.let { credential ->
            if (credential.missing) {
                trailerDebugLog("TMDB trailer lookup skipped: api_key_missing")
                return null
            }
            return credential.apiKey
        }
        val apiKey = tmdbSettingsDataStore.settings.first().apiKey.trim()
        return apiKey.takeIf { it.isNotBlank() }
    }

    private fun getValidCachedYoutubeSource(youtubeKey: String): CachedTrailerPlaybackSource? {
        val cached = youtubeSourceCache[youtubeKey] ?: return null
        val age = Duration.between(cached.cachedAt, Instant.now(clock))
        if (age <= YOUTUBE_SOURCE_CACHE_TTL) {
            return cached
        }
        youtubeSourceCache.remove(youtubeKey, cached)
        trailerDebugLog("resolveYouTubeTrailer cache expired key=$youtubeKey authBacked=${cached.authBacked}")
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
        .filter { isEnglishTmdbVideoLanguage(it.iso6391) }
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

internal fun isEnglishTmdbVideoLanguage(languageCode: String?): Boolean {
    val normalized = languageCode
        ?.trim()
        ?.lowercase()
        ?.takeIf { it.isNotEmpty() }
        ?: return false
    return normalized == "en"
}

internal fun selectStreailerTrailerCandidate(streams: List<Stream>): StreailerTrailerCandidate? {
    return streams
        .asSequence()
        .filter(::isTrailerOrTeaserStream)
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

internal fun streailerTrailerPriority(stream: Stream): Int {
    val bingeGroup = stream.behaviorHints?.bingeGroup?.trim()?.lowercase()
    val combinedText = listOf(stream.name, stream.title, stream.description)
        .joinToString(" ")
        .lowercase()

    return when {
        bingeGroup == "trailer" -> 0
        "trailer" in combinedText -> 1
        bingeGroup == "teaser" -> 2
        "teaser" in combinedText -> 3
        else -> 4
    }
}

internal fun isTrailerOrTeaserStream(stream: Stream): Boolean {
    val bingeGroup = stream.behaviorHints?.bingeGroup?.trim()?.lowercase()
    if (bingeGroup == "trailer" || bingeGroup == "teaser") return true

    val combinedText = listOf(stream.name, stream.title, stream.description)
        .joinToString(" ")
        .lowercase()

    return "trailer" in combinedText || "teaser" in combinedText
}

internal fun isRecapStream(stream: Stream): Boolean {
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

private fun hasInternalStreailerCandidate(candidate: StreailerTrailerCandidate?): Boolean {
    return when {
        candidate == null -> false
        !candidate.youtubeId.isNullOrBlank() -> true
        else -> extractYouTubeVideoId(candidate.externalUrl.orEmpty()) != null
    }
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
