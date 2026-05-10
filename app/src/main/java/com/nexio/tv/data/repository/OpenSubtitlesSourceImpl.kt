package com.nexio.tv.data.repository

import android.util.Log
import com.nexio.tv.core.trace.SubtitleDiagnosticsGate
import com.nexio.tv.data.integration.subtitles.opensubtitles.OpenSubtitlesIntegrationProvider
import com.nexio.tv.data.local.OpenSubtitlesPreferences
import com.nexio.tv.data.remote.model.OpenSubtitlesSearchResult
import com.nexio.tv.domain.model.Subtitle
import com.nexio.tv.domain.repository.OpenSubtitlesSource
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

@Singleton
class OpenSubtitlesSourceImpl @Inject constructor(
    private val provider: OpenSubtitlesIntegrationProvider,
    private val preferences: OpenSubtitlesPreferences,
    private val diagnosticsGate: SubtitleDiagnosticsGate,
) : OpenSubtitlesSource {

    override suspend fun search(
        type: String,
        id: String,
        videoId: String?,
        videoHash: String?,
        videoSize: Long?,
        filename: String?,
        imdbHint: String?
    ): List<Subtitle> = withContext(Dispatchers.IO) {
        val snapshot = preferences.snapshot()
        diag {
            "search entry type=$type id=$id videoId=$videoId hash=$videoHash size=$videoSize " +
                "imdbHint=$imdbHint enabled=${snapshot.enabled} onlyTrusted=${snapshot.onlyTrusted} " +
                "includeAi=${snapshot.includeAiTranslated}"
        }
        if (!snapshot.enabled) {
            diag { "search short-circuit: provider disabled in preferences" }
            return@withContext emptyList()
        }

        val target = resolveTarget(type, id, videoId, imdbHint)
        if (target == null) {
            diag {
                "resolveTarget produced no IMDB target (type=$type id=$id videoId=$videoId imdbHint=$imdbHint); " +
                    "imdb-search lane skipped, hash lane will still attempt"
            }
        } else {
            diag { "resolveTarget ok imdb=${target.first} season=${target.second} episode=${target.third}" }
        }

        val rows = coroutineScope {
            val imdbDeferred = async {
                if (target != null) runImdbSearch(target.first, target.second, target.third)
                else emptyList()
            }
            val hashDeferred = async { runHashSearch(videoHash, videoSize) }
            (imdbDeferred.await() + hashDeferred.await())
        }
            .distinctBy { it.subtitleId }
            .filter { row ->
                (snapshot.includeAiTranslated || !row.aiTranslated) &&
                    (!snapshot.onlyTrusted || row.trusted)
            }
            .sortedWith(
                compareByDescending<OpenSubtitlesSearchResult> { it.movieHash != null }
                    .thenByDescending { it.trusted }
                    .thenByDescending { it.downloads ?: 0 }
            )

        diag { "search exit final count=${rows.size} (after distinct/filter/sort)" }
        rows.map { it.toDomain() }
    }

    private fun resolveTarget(
        type: String,
        id: String,
        videoId: String?,
        imdbHint: String?,
    ): Triple<String, Int?, Int?>? {
        val canonicalType = type.trim().lowercase()
        val isSeries = canonicalType == "series" || canonicalType == "tv" || canonicalType == "episode"
        val parts = (videoId ?: id).split(':')
        val canonicalImdb = parts.firstOrNull()?.takeIf { it.startsWith("tt", ignoreCase = true) }
        val imdb = canonicalImdb ?: normalizeImdbHint(imdbHint) ?: return null

        if (!isSeries) return Triple(imdb, null, null)

        // Episode/season position depends on whether parts[0] is the IMDB id itself
        // (tt…:S:E → S at [1], E at [2]) or a different namespace prefix
        // (tvdb:N:S:E → S at [2], E at [3]).
        val seasonIndex = if (canonicalImdb != null) 1 else 2
        val episodeIndex = seasonIndex + 1
        val season = parts.getOrNull(seasonIndex)?.toIntOrNull()
        val episode = parts.getOrNull(episodeIndex)?.toIntOrNull()
        return Triple(imdb, season, episode)
    }

    private fun normalizeImdbHint(hint: String?): String? {
        val trimmed = hint?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        return if (trimmed.startsWith("tt", ignoreCase = true)) trimmed else "tt$trimmed"
    }

    private suspend fun runImdbSearch(
        imdbId: String,
        season: Int?,
        episode: Int?
    ): List<OpenSubtitlesSearchResult> = runCatching {
        if (season != null && episode != null) {
            provider.searchSeriesEpisode(imdbId, season, episode)
        } else {
            provider.searchByImdb(imdbId)
        }
    }
        .onSuccess { results ->
            diag { "runImdbSearch ok imdb=$imdbId season=$season episode=$episode count=${results.size}" }
        }
        .onFailure {
            Log.w(TAG, "imdb search failed: ${it.message}")
            diag { "runImdbSearch threw imdb=$imdbId season=$season episode=$episode err=${it.javaClass.simpleName}:${it.message}" }
        }
        .getOrDefault(emptyList())

    private suspend fun runHashSearch(
        hash: String?,
        size: Long?
    ): List<OpenSubtitlesSearchResult> {
        if (hash.isNullOrBlank() || size == null || size <= 0) {
            diag { "runHashSearch skipped hash=${hash ?: "null"} size=$size" }
            return emptyList()
        }
        return runCatching { provider.searchByHash(hash, size) }
            .onSuccess { results ->
                diag { "runHashSearch ok hash=$hash size=$size count=${results.size}" }
            }
            .onFailure {
                Log.w(TAG, "hash search failed: ${it.message}")
                diag { "runHashSearch threw hash=$hash size=$size err=${it.javaClass.simpleName}:${it.message}" }
            }
            .getOrDefault(emptyList())
    }

    private fun OpenSubtitlesSearchResult.toDomain(): Subtitle = Subtitle(
        id = "opensubtitles:$subtitleId",
        url = downloadUrl,
        lang = languageCode.ifBlank { language },
        addonName = SOURCE_NAME,
        addonLogo = null,
        isHashMatch = movieHash != null
    )

    private inline fun diag(message: () -> String) {
        if (diagnosticsGate.isEnabled()) {
            Log.d(SubtitleDiagnosticsGate.TAG, "OpenSubtitlesSource: ${message()}")
        }
    }

    companion object {
        private const val TAG = "OpenSubtitlesSource"
        const val SOURCE_NAME = "OpenSubtitles"
    }
}
