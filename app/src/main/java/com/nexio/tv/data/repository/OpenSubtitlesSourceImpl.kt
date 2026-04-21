package com.nexio.tv.data.repository

import android.util.Log
import com.nexio.tv.core.player.OpenSubtitlesHasher
import com.nexio.tv.data.local.OpenSubtitlesPreferences
import com.nexio.tv.data.remote.api.OpenSubtitlesApiClient
import com.nexio.tv.data.remote.api.OpenSubtitlesArchiveExtractor
import com.nexio.tv.data.remote.model.OpenSubtitlesSearchResult
import com.nexio.tv.domain.model.Subtitle
import com.nexio.tv.domain.repository.OpenSubtitlesSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Production [OpenSubtitlesSource] backed by [OpenSubtitlesApiClient].
 *
 * Behavior:
 *  1. If disabled in prefs → return empty.
 *  2. Run an IMDb search (movie or episode) in parallel with an oshash search
 *     when the URL allows hash computation.
 *  3. Filter via prefs (trusted-only, AI translations).
 *  4. Rank: hash matches first, then SubFromTrusted, then download count desc.
 *  5. Return [Subtitle] rows pointing at the original download URL — file
 *     extraction happens lazily at playback time via [extractor].
 */
@Singleton
class OpenSubtitlesSourceImpl @Inject constructor(
    private val apiClient: OpenSubtitlesApiClient,
    private val preferences: OpenSubtitlesPreferences,
    private val hasher: HashComputer,
    @Suppress("unused") private val extractor: OpenSubtitlesArchiveExtractor
) : OpenSubtitlesSource {

    /** Trivial seam over [OpenSubtitlesHasher] so tests can supply hashes without HTTP. */
    fun interface HashComputer {
        suspend fun compute(url: String, headers: Map<String, String>): OpenSubtitlesHasher.Result?
    }

    override suspend fun search(
        type: String,
        id: String,
        videoId: String?,
        videoHash: String?,
        videoSize: Long?,
        filename: String?
    ): List<Subtitle> = withContext(Dispatchers.IO) {
        val snap = preferences.snapshot()
        if (!snap.enabled) return@withContext emptyList()

        val (imdbId, season, episode) = parseTarget(type, id, videoId)
            ?: return@withContext emptyList()

        val rows = coroutineScope {
            val imdbDeferred = async { runImdbSearch(imdbId, season, episode) }
            val hashDeferred = async {
                runHashSearch(videoHash, videoSize)
                    ?: tryHashSearchFromUrl(filename)
            }
            (imdbDeferred.await() + hashDeferred.await().orEmpty())
        }
            .distinctBy { it.subtitleId }
            .filter { row ->
                (snap.includeAiTranslated || !row.aiTranslated) &&
                    (!snap.onlyTrusted || row.trusted)
            }
            .sortedWith(
                compareByDescending<OpenSubtitlesSearchResult> { it.movieHash != null }
                    .thenByDescending { it.trusted }
                    .thenByDescending { it.downloads ?: 0 }
            )

        rows.map { it.toDomain() }
    }

    private fun parseTarget(type: String, id: String, videoId: String?): Triple<String, Int?, Int?>? {
        val canonicalType = type.trim().lowercase()
        val parts = (videoId ?: id).split(':')
        val imdb = parts.firstOrNull()?.takeIf { it.isNotBlank() } ?: return null
        return when (canonicalType) {
            "movie" -> Triple(imdb, null, null)
            "series", "tv", "episode" -> {
                val season = parts.getOrNull(1)?.toIntOrNull()
                val episode = parts.getOrNull(2)?.toIntOrNull()
                if (season != null && episode != null) Triple(imdb, season, episode)
                else Triple(imdb, null, null)
            }
            else -> Triple(imdb, null, null)
        }
    }

    private suspend fun runImdbSearch(
        imdbId: String,
        season: Int?,
        episode: Int?
    ): List<OpenSubtitlesSearchResult> = runCatching {
        if (season != null && episode != null) {
            apiClient.searchSeriesEpisode(imdbId, season, episode)
        } else {
            apiClient.searchByImdb(imdbId)
        }
    }.onFailure { Log.w(TAG, "imdb search failed: ${it.message}") }
        .getOrDefault(emptyList())

    private suspend fun runHashSearch(
        hash: String?,
        size: Long?
    ): List<OpenSubtitlesSearchResult>? {
        if (hash.isNullOrBlank() || size == null || size <= 0) return null
        return runCatching { apiClient.searchByHash(hash, size) }
            .onFailure { Log.w(TAG, "hash search failed: ${it.message}") }
            .getOrDefault(emptyList())
    }

    private suspend fun tryHashSearchFromUrl(url: String?): List<OpenSubtitlesSearchResult>? {
        if (url.isNullOrBlank() || !url.startsWith("http", ignoreCase = true)) return null
        val computed = runCatching { hasher.compute(url, emptyMap()) }.getOrNull() ?: return null
        return runCatching { apiClient.searchByHash(computed.hash, computed.fileSize) }
            .onFailure { Log.w(TAG, "post-hash search failed: ${it.message}") }
            .getOrDefault(emptyList())
    }

    private fun OpenSubtitlesSearchResult.toDomain(): Subtitle = Subtitle(
        id = "opensubtitles:$subtitleId",
        url = downloadUrl,
        lang = languageCode.ifBlank { language },
        addonName = ADDON_NAME,
        addonLogo = null,
        isHashMatch = movieHash != null
    )

    companion object {
        private const val TAG = "OpenSubtitlesSource"
        const val ADDON_NAME = "OpenSubtitles"
    }
}
