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
        filename: String?
    ): List<Subtitle> = withContext(Dispatchers.IO) {
        val snapshot = preferences.snapshot()
        diag {
            "search entry type=$type id=$id videoId=$videoId hash=$videoHash size=$videoSize " +
                "enabled=${snapshot.enabled} onlyTrusted=${snapshot.onlyTrusted} " +
                "includeAi=${snapshot.includeAiTranslated}"
        }
        if (!snapshot.enabled) {
            diag { "search short-circuit: provider disabled in preferences" }
            return@withContext emptyList()
        }

        val target = parseTarget(type, id, videoId)
        if (target == null) {
            diag { "search short-circuit: parseTarget FAILED for type=$type id=$id videoId=$videoId" }
            return@withContext emptyList()
        }
        val (imdbId, season, episode) = target
        diag { "parseTarget ok imdb=$imdbId season=$season episode=$episode" }

        val rows = coroutineScope {
            val imdbDeferred = async { runImdbSearch(imdbId, season, episode) }
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

    private fun parseTarget(type: String, id: String, videoId: String?): Triple<String, Int?, Int?>? {
        val canonicalType = type.trim().lowercase()
        val parts = (videoId ?: id).split(':')
        val imdb = parts.firstOrNull()?.takeIf { it.startsWith("tt", ignoreCase = true) }
            ?: return null
        return when (canonicalType) {
            "series", "tv", "episode" -> {
                val season = parts.getOrNull(1)?.toIntOrNull()
                val episode = parts.getOrNull(2)?.toIntOrNull()
                Triple(imdb, season, episode)
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
