package com.nexio.tv.data.remote.api

import com.nexio.tv.data.remote.dto.OpenSubtitlesRestSubtitleDto
import com.nexio.tv.data.remote.model.OpenSubtitlesSearchResult
import java.time.LocalDateTime
import java.time.ZoneOffset

class OpenSubtitlesApiClient(
    private val api: OpenSubtitlesRestApi,
    private val userAgent: String,
    private val baseUrl: String = DEFAULT_BASE_URL
) {
    suspend fun searchByImdb(
        imdbId: String,
        languages: String? = null
    ): List<OpenSubtitlesSearchResult> {
        require(imdbId.isNotBlank()) { "imdbId must not be blank" }
        val filters = mutableMapOf("imdbid" to normalizeImdbId(imdbId))
        languages?.takeIf { it.isNotBlank() }?.let { filters["sublanguageid"] = it }
        return execute(filters)
    }

    suspend fun searchSeriesEpisode(
        imdbId: String,
        season: Int,
        episode: Int,
        languages: String? = null
    ): List<OpenSubtitlesSearchResult> {
        require(imdbId.isNotBlank()) { "imdbId must not be blank" }
        require(season >= 0) { "season must be >= 0" }
        require(episode >= 0) { "episode must be >= 0" }
        val filters = mutableMapOf(
            "episode" to episode.toString(),
            "imdbid" to normalizeImdbId(imdbId),
            "season" to season.toString()
        )
        languages?.takeIf { it.isNotBlank() }?.let { filters["sublanguageid"] = it }
        return execute(filters)
    }

    suspend fun searchByHash(
        movieHash: String,
        movieByteSize: Long,
        languages: String? = null
    ): List<OpenSubtitlesSearchResult> {
        require(movieHash.length == 16) { "movieHash must be 16 hex chars" }
        require(movieByteSize > 0) { "movieByteSize must be > 0" }
        val filters = mutableMapOf(
            "moviebytesize" to movieByteSize.toString(),
            "moviehash" to movieHash.lowercase()
        )
        languages?.takeIf { it.isNotBlank() }?.let { filters["sublanguageid"] = it }
        return execute(filters)
    }

    private suspend fun execute(filters: Map<String, String>): List<OpenSubtitlesSearchResult> {
        val path = filters.entries
            .sortedBy { it.key }
            .joinToString("/") { (key, value) -> "$key-$value" }
        val response = api.search("$baseUrl/search/$path", userAgent)
        if (!response.isSuccessful) return emptyList()
        return response.body().orEmpty().mapNotNull { it.toResult() }
    }

    private fun normalizeImdbId(imdbId: String): String =
        imdbId.removePrefix("tt").trimStart('0').ifEmpty { "0" }

    companion object {
        const val DEFAULT_BASE_URL = "https://rest.opensubtitles.org"
    }
}

internal fun OpenSubtitlesRestSubtitleDto.toResult(): OpenSubtitlesSearchResult? {
    val id = idSubtitleFile?.takeIf { it.isNotBlank() } ?: return null
    val downloadUrl = subDownloadLink?.takeIf { it.isNotBlank() }
        ?: zipDownloadLink?.takeIf { it.isNotBlank() }
        ?: return null
    return OpenSubtitlesSearchResult(
        subtitleId = id,
        language = languageName.orEmpty(),
        languageCode = subLanguageID.orEmpty(),
        downloadUrl = downloadUrl,
        filename = subFileName,
        movieHash = movieHash?.takeIf { matchedBy == "moviehash" },
        fps = movieFPS?.toDoubleOrNull()?.takeIf { it > 0.0 },
        downloads = subDownloadsCnt?.toIntOrNull(),
        trusted = subFromTrusted == "1",
        aiTranslated = subAutoTranslation == "1",
        uploadedAtEpochSeconds = parseAddDate(subAddDate)
    )
}

private fun parseAddDate(raw: String?): Long? {
    val normalized = raw?.trim().orEmpty()
    if (normalized.isEmpty()) return null
    return runCatching {
        LocalDateTime.parse(normalized.replace(' ', 'T')).toEpochSecond(ZoneOffset.UTC)
    }.getOrNull()
}
