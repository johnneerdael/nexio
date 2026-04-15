package com.nexio.tv.core.tvdb

import android.util.Log
import com.nexio.tv.data.local.TvdbSettingsDataStore
import com.nexio.tv.data.remote.api.TvdbApi
import com.nexio.tv.data.remote.api.TvdbSeriesExtendedRecord
import com.nexio.tv.data.trailer.TrailerPlaybackSource
import com.nexio.tv.data.trailer.TrailerResolutionResult
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

private const val TAG = "TvdbTrailerResolver"

sealed interface TvdbTrailerLookupResult {
    data object Inactive : TvdbTrailerLookupResult
    data object Missing : TvdbTrailerLookupResult
    data class Unusable(val reason: String) : TvdbTrailerLookupResult
    data class Resolved(val result: TrailerResolutionResult) : TvdbTrailerLookupResult
    /** YouTube URL that should be routed through the existing YouTube extractor path. */
    data class ResolvedYouTube(val youtubeUrl: String, val videoId: String) : TvdbTrailerLookupResult
}

@Singleton
class TvdbTrailerResolver @Inject constructor(
    private val tvdbSettingsDataStore: TvdbSettingsDataStore,
    private val tvdbIdentityService: TvdbIdentityService,
    private val tvdbApi: TvdbApi,
    private val authService: TvdbAuthService,
    private val tvdbTrailerMapper: TvdbTrailerMapper
) {
    suspend fun resolveTitleTrailer(
        contentId: String?,
        type: String?,
        title: String?,
        year: String?
    ): TvdbTrailerLookupResult = withContext(Dispatchers.IO) {
        if (!isTvdbActiveForTv(type)) return@withContext TvdbTrailerLookupResult.Inactive

        val candidates = fetchAndMapCandidates(contentId) ?: return@withContext TvdbTrailerLookupResult.Missing

        // Filter to non-recap title trailers (no season association)
        val titleCandidates = candidates.filter { !it.isRecap && it.seasonNumber == null }
        if (titleCandidates.isEmpty()) {
            Log.d(TAG, "tvdb_trailer_missing contentId=$contentId reason=no_title_candidates total=${candidates.size}")
            return@withContext TvdbTrailerLookupResult.Missing
        }

        resolveFirstUsable(titleCandidates, contentId)
    }

    suspend fun resolveSeasonTrailer(
        contentId: String?,
        type: String?,
        seasonNumber: Int?,
        title: String?,
        year: String?
    ): TvdbTrailerLookupResult = withContext(Dispatchers.IO) {
        if (!isTvdbActiveForTv(type)) return@withContext TvdbTrailerLookupResult.Inactive
        if (seasonNumber == null || seasonNumber < 0) return@withContext TvdbTrailerLookupResult.Inactive

        val candidates = fetchAndMapCandidates(contentId) ?: return@withContext TvdbTrailerLookupResult.Missing

        val seasonTrailers = candidates.filter {
            !it.isRecap && it.seasonNumber == seasonNumber
        }
        if (seasonTrailers.isEmpty()) {
            return@withContext TvdbTrailerLookupResult.Missing
        }

        resolveFirstUsable(seasonTrailers, contentId)
    }

    suspend fun resolveSeasonRecap(
        contentId: String?,
        type: String?,
        seasonNumber: Int?,
        title: String?,
        year: String?
    ): TvdbTrailerLookupResult = withContext(Dispatchers.IO) {
        if (!isTvdbActiveForTv(type)) return@withContext TvdbTrailerLookupResult.Inactive
        if (seasonNumber == null || seasonNumber < 0) return@withContext TvdbTrailerLookupResult.Inactive

        val candidates = fetchAndMapCandidates(contentId) ?: return@withContext TvdbTrailerLookupResult.Missing

        val recapCandidates = candidates.filter {
            it.isRecap && (it.seasonNumber == null || it.seasonNumber == seasonNumber)
        }
        if (recapCandidates.isEmpty()) {
            return@withContext TvdbTrailerLookupResult.Missing
        }

        resolveFirstUsable(recapCandidates, contentId)
    }

    private suspend fun isTvdbActiveForTv(type: String?): Boolean {
        val normalizedType = type?.trim()?.lowercase()
        val isTv = normalizedType in setOf("tv", "series", "show", "tvshow")
        if (!isTv) return false
        return tvdbSettingsDataStore.settings.first().isActive
    }

    private suspend fun fetchAndMapCandidates(contentId: String?): List<TvdbTrailerCandidate>? {
        val trimmedId = contentId?.trim()?.takeIf { it.isNotBlank() } ?: return null

        val identity = resolveIdentity(trimmedId) ?: run {
            Log.d(TAG, "tvdb_trailer_missing contentId=$contentId reason=identity_not_found")
            return null
        }

        val record = fetchSeriesRecord(identity) ?: run {
            Log.d(TAG, "tvdb_trailer_missing contentId=$contentId reason=record_not_found")
            return null
        }

        val candidates = tvdbTrailerMapper.mapCandidates(record)
        if (candidates.isEmpty()) {
            Log.d(TAG, "tvdb_trailer_missing contentId=$contentId reason=no_trailers_on_record")
            return null
        }

        return candidates
    }

    private suspend fun fetchSeriesRecord(identity: TvdbSeriesIdentity): TvdbSeriesExtendedRecord? {
        val authorization = authService.bearerToken() ?: return null
        return runCatching {
            tvdbApi.getSeriesExtended(
                authorization = authorization,
                id = identity.tvdbId,
                meta = "translations",
                short = false
            )
        }.onFailure { error ->
            Log.w(TAG, "TVDB series trailer request failed reason=${error.javaClass.simpleName}")
        }.getOrNull()
            ?.takeIf { it.isSuccessful }
            ?.body()
            ?.data
    }

    private suspend fun resolveIdentity(contentId: String): TvdbSeriesIdentity? {
        val normalized = contentId.lowercase()
        return when {
            normalized.startsWith("tvdb:") -> {
                contentId.substringAfter(':').toIntOrNull()?.let {
                    tvdbIdentityService.resolveSeriesByTvdbId(it)
                }
            }
            normalized.startsWith("imdb:") -> {
                tvdbIdentityService.resolveSeriesByRemoteId(
                    contentId.substringAfter(':'),
                    TvdbRemoteIdSource.IMDB
                )
            }
            contentId.startsWith("tt", ignoreCase = true) -> {
                tvdbIdentityService.resolveSeriesByRemoteId(
                    contentId.substringBefore(':').substringBefore('/'),
                    TvdbRemoteIdSource.IMDB
                )
            }
            normalized.startsWith("tmdb:") -> {
                val value = contentId.substringAfter(':').removePrefix("series-").removePrefix("tv-").trim()
                tvdbIdentityService.resolveSeriesByRemoteId(value, TvdbRemoteIdSource.TMDB)
            }
            else -> tvdbIdentityService.resolveSeriesByRemoteId(contentId, TvdbRemoteIdSource.OTHER)
        }
    }

    private fun resolveFirstUsable(
        candidates: List<TvdbTrailerCandidate>,
        contentId: String?
    ): TvdbTrailerLookupResult {
        var lastUnusableReason: String? = null

        for (candidate in candidates) {
            when (val usability = tvdbTrailerMapper.classify(candidate)) {
                is TvdbTrailerUsability.YouTube -> {
                    Log.d(TAG, "tvdb_trailer_success contentId=$contentId type=youtube videoId=${usability.videoId}")
                    return TvdbTrailerLookupResult.ResolvedYouTube(
                        youtubeUrl = usability.url,
                        videoId = usability.videoId
                    )
                }
                is TvdbTrailerUsability.DirectMedia -> {
                    Log.d(TAG, "tvdb_trailer_success contentId=$contentId type=direct_media")
                    return TvdbTrailerLookupResult.Resolved(
                        TrailerResolutionResult.Playback(
                            TrailerPlaybackSource(videoUrl = usability.url)
                        )
                    )
                }
                is TvdbTrailerUsability.External -> {
                    Log.d(TAG, "tvdb_trailer_success contentId=$contentId type=external url=${usability.url}")
                    return TvdbTrailerLookupResult.Resolved(
                        TrailerResolutionResult.External(usability.url)
                    )
                }
                is TvdbTrailerUsability.Unusable -> {
                    Log.d(TAG, "tvdb_trailer_unusable_url contentId=$contentId reason=${usability.reason} url=${usability.url}")
                    lastUnusableReason = usability.reason
                }
            }
        }

        return if (lastUnusableReason != null) {
            TvdbTrailerLookupResult.Unusable(lastUnusableReason)
        } else {
            TvdbTrailerLookupResult.Missing
        }
    }
}
