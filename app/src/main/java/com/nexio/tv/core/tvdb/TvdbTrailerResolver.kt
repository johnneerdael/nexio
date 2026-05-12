package com.nexio.tv.core.tvdb

import android.util.Log
import com.nexio.tv.core.anime.AnimeStremioId
import com.nexio.tv.core.locale.TrailerLanguageMatcher
import com.nexio.tv.data.integration.tvdb.TvdbIntegrationProvider
import com.nexio.tv.data.local.TvdbSettingsDataStore
import com.nexio.tv.data.remote.api.TvdbSeriesExtendedRecord
import com.nexio.tv.data.trailer.TrailerPlaybackSource
import com.nexio.tv.data.trailer.TrailerResolutionResult
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
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
    private val tvdbIntegrationProvider: TvdbIntegrationProvider,
    private val tvdbTrailerMapper: TvdbTrailerMapper
) {
    private val seriesRecordInFlight = ConcurrentHashMap<Int, CompletableDeferred<TvdbSeriesExtendedRecord?>>()

    suspend fun resolveTitleTrailer(
        contentId: String?,
        type: String?,
        title: String?,
        year: String?
    ): TvdbTrailerLookupResult = withContext(Dispatchers.IO) {
        if (!isTvdbActiveForTv(type)) return@withContext TvdbTrailerLookupResult.Inactive

        val seriesContext = fetchDefaultTitleContext(contentId)
            ?: return@withContext TvdbTrailerLookupResult.Missing
        if (seriesContext.candidates.isEmpty()) {
            Log.d(TAG, "tvdb_trailer_missing contentId=$contentId reason=no_title_candidates")
            return@withContext TvdbTrailerLookupResult.Missing
        }

        val filtered = filterByOriginalLanguage(seriesContext.candidates, seriesContext.originalLanguage, contentId)
        if (filtered.isEmpty()) {
            return@withContext TvdbTrailerLookupResult.Missing
        }
        resolveFirstUsable(filtered, contentId)
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

        val context = fetchAndMapContext(contentId) ?: return@withContext TvdbTrailerLookupResult.Missing

        val seasonTrailers = context.candidates.filter {
            !it.isRecap && it.seasonNumber == seasonNumber
        }
        if (seasonTrailers.isEmpty()) {
            return@withContext TvdbTrailerLookupResult.Missing
        }

        val filtered = filterByOriginalLanguage(seasonTrailers, context.originalLanguage, contentId)
        if (filtered.isEmpty()) {
            return@withContext TvdbTrailerLookupResult.Missing
        }
        resolveFirstUsable(filtered, contentId)
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

        val context = fetchAndMapContext(contentId) ?: return@withContext TvdbTrailerLookupResult.Missing

        val recapCandidates = context.candidates.filter {
            it.isRecap && it.seasonNumber == seasonNumber
        }
        if (recapCandidates.isEmpty()) {
            return@withContext TvdbTrailerLookupResult.Missing
        }

        val filtered = filterByOriginalLanguage(recapCandidates, context.originalLanguage, contentId)
        if (filtered.isEmpty()) {
            return@withContext TvdbTrailerLookupResult.Missing
        }
        resolveFirstUsable(filtered, contentId)
    }

    private suspend fun isTvdbActiveForTv(type: String?): Boolean {
        val normalizedType = type?.trim()?.lowercase()
        val isTv = normalizedType in setOf("tv", "series", "show", "tvshow")
        if (!isTv) return false
        return tvdbSettingsDataStore.settings.first().isActive
    }

    private suspend fun fetchAndMapContext(contentId: String?): SeriesTrailerContext? {
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

        return SeriesTrailerContext(candidates = candidates, originalLanguage = record.originalLanguage)
    }

    private suspend fun fetchDefaultTitleContext(contentId: String?): SeriesTrailerContext? {
        val trimmedId = contentId?.trim()?.takeIf { it.isNotBlank() } ?: return null

        val identity = resolveIdentity(trimmedId) ?: run {
            Log.d(TAG, "tvdb_trailer_missing contentId=$contentId reason=identity_not_found")
            return null
        }

        val record = fetchSeriesRecord(identity) ?: run {
            Log.d(TAG, "tvdb_trailer_missing contentId=$contentId reason=record_not_found")
            return null
        }

        val seriesOriginalLanguage = record.originalLanguage

        val seasonCandidates = fetchLatestSeasonTrailerCandidates(record)
        if (seasonCandidates.isNotEmpty()) {
            return SeriesTrailerContext(candidates = seasonCandidates, originalLanguage = seriesOriginalLanguage)
        }

        val candidates = tvdbTrailerMapper.mapCandidates(record)
            .filter { !it.isRecap }
        if (candidates.isEmpty()) {
            Log.d(TAG, "tvdb_trailer_missing contentId=$contentId reason=no_trailers_on_record")
            return null
        }
        return SeriesTrailerContext(candidates = candidates, originalLanguage = seriesOriginalLanguage)
    }

    /**
     * Filters candidates so only those whose declared language matches the series'
     * originalLanguage survive. A candidate with no declared language is **rejected**
     * (strictest mode — operator decision per CLAUDE.md trailer-eligibility rule).
     *
     * When the series carries no originalLanguage (TVDB record incomplete), fall back to
     * accepting English-tagged candidates only ("eng" / "en"). Treating unknown originals
     * as "any" reproduces the bug; we instead degrade to the safest assumption (English),
     * which matches the previous default for the bulk of the catalog.
     */
    private fun filterByOriginalLanguage(
        candidates: List<TvdbTrailerCandidate>,
        seriesOriginalLanguage: String?,
        contentId: String?
    ): List<TvdbTrailerCandidate> {
        val target = seriesOriginalLanguage?.trim()?.takeIf { it.isNotBlank() }
        val matched = candidates.filter { candidate ->
            val candidateLanguage = candidate.language?.trim()?.takeIf { it.isNotBlank() }
            if (candidateLanguage == null) return@filter false
            if (target != null) {
                TrailerLanguageMatcher.matches(candidateLanguage, target)
            } else {
                TrailerLanguageMatcher.matches(candidateLanguage, "en")
            }
        }
        if (matched.size < candidates.size) {
            val rejected = candidates - matched.toSet()
            for (candidate in rejected) {
                Log.d(
                    TAG,
                    "tvdb_trailer_rejected_language contentId=$contentId " +
                        "trailerLanguage=${candidate.language.orEmpty()} " +
                        "seriesOriginalLanguage=${target.orEmpty()} url=${candidate.url}"
                )
            }
        }
        return matched
    }

    private data class SeriesTrailerContext(
        val candidates: List<TvdbTrailerCandidate>,
        val originalLanguage: String?
    )

    private suspend fun fetchLatestSeasonTrailerCandidates(
        series: TvdbSeriesExtendedRecord
    ): List<TvdbTrailerCandidate> {
        val positiveSeasons = series.seasons.orEmpty()
            .filter { season -> season.id != null && (season.number ?: -1) > 0 }

        val orderedSeasons = positiveSeasons
            .filter { season -> season.type?.name.equals("Aired Order", ignoreCase = true) }
            .ifEmpty { positiveSeasons }
            .sortedByDescending { season -> season.number ?: -1 }

        for (season in orderedSeasons) {
            val seasonRecord = tvdbIntegrationProvider.fetchSeasonExtended(season.id ?: continue)
                ?: continue
            val candidates = tvdbTrailerMapper.mapCandidates(seasonRecord)
                .filter { !it.isRecap }
            if (candidates.isNotEmpty()) {
                return candidates
            }
        }

        return emptyList()
    }

    private suspend fun fetchSeriesRecord(identity: TvdbSeriesIdentity): TvdbSeriesExtendedRecord? {
        seriesRecordInFlight[identity.tvdbId]?.let { return it.await() }
        val deferred = CompletableDeferred<TvdbSeriesExtendedRecord?>()
        val existingDeferred = seriesRecordInFlight.putIfAbsent(identity.tvdbId, deferred)
        if (existingDeferred != null) {
            return existingDeferred.await()
        }

        return try {
            runCatching {
                tvdbIntegrationProvider.fetchSeriesExtended(
                    tvdbId = identity.tvdbId,
                    meta = null,
                    short = false
                )
            }.onFailure { error ->
                Log.w(TAG, "TVDB series trailer request failed reason=${error.javaClass.simpleName}")
            }.getOrNull()
                .also { deferred.complete(it) }
        } finally {
            seriesRecordInFlight.remove(identity.tvdbId, deferred)
        }
    }

    private suspend fun resolveIdentity(contentId: String): TvdbSeriesIdentity? {
        val normalized = contentId.lowercase()
        return when {
            AnimeStremioId.isExplicitAnimeOnlyId(contentId) -> null
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
