package com.nexio.tv.core.tvdb

import com.nexio.tv.data.remote.api.TvdbSeriesExtendedRecord
import com.nexio.tv.data.remote.api.TvdbSeasonExtendedRecord
import com.nexio.tv.data.remote.api.TvdbTrailerRecord
import java.net.URI
import javax.inject.Inject

private val YOUTUBE_VIDEO_ID_REGEX = Regex("^[a-zA-Z0-9_-]{11}$")
private val DIRECT_MEDIA_EXTENSIONS = setOf(".mp4", ".m3u8", ".webm")
private val REJECTED_SCHEMES = setOf("intent", "file", "content", "javascript")

class TvdbTrailerMapper @Inject constructor() {

    fun mapCandidates(series: TvdbSeriesExtendedRecord): List<TvdbTrailerCandidate> {
        return mapTrailerRecords(series.trailers, seasonNumber = null)
    }

    fun mapCandidates(season: TvdbSeasonExtendedRecord): List<TvdbTrailerCandidate> {
        return mapTrailerRecords(season.trailers, seasonNumber = season.number)
    }

    private fun mapTrailerRecords(
        trailers: List<TvdbTrailerRecord>?,
        seasonNumber: Int?
    ): List<TvdbTrailerCandidate> {
        return trailers.orEmpty().mapNotNull { trailer ->
            val url = trailer.url?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            TvdbTrailerCandidate(
                url = url,
                name = trailer.name?.trim()?.takeIf { it.isNotBlank() },
                type = null,
                seasonNumber = seasonNumber
            )
        }
    }

    fun classify(candidate: TvdbTrailerCandidate): TvdbTrailerUsability {
        val url = candidate.url.trim()
        if (url.isBlank()) {
            return TvdbTrailerUsability.Unusable(reason = "blank_url", url = null)
        }

        val uri = runCatching { URI(url) }.getOrNull()
            ?: return TvdbTrailerUsability.Unusable(reason = "malformed_url", url = url)

        val scheme = uri.scheme?.lowercase()
        if (scheme == null || scheme !in setOf("http", "https")) {
            if (scheme != null && scheme in REJECTED_SCHEMES) {
                return TvdbTrailerUsability.Unusable(reason = "rejected_scheme_$scheme", url = url)
            }
            return TvdbTrailerUsability.Unusable(reason = "unsupported_scheme_$scheme", url = url)
        }

        val host = uri.host?.lowercase()?.removePrefix("www.") ?: return TvdbTrailerUsability.Unusable(
            reason = "no_host",
            url = url
        )

        // YouTube detection
        val youtubeVideoId = extractYouTubeVideoId(uri, host)
        if (youtubeVideoId != null) {
            return TvdbTrailerUsability.YouTube(url = url, videoId = youtubeVideoId)
        }

        // Vimeo detection
        if (host == "vimeo.com" || host.endsWith(".vimeo.com")) {
            return TvdbTrailerUsability.External(url = url)
        }

        // Direct media detection
        val path = uri.path?.lowercase().orEmpty()
        if (DIRECT_MEDIA_EXTENSIONS.any { path.endsWith(it) }) {
            return TvdbTrailerUsability.DirectMedia(url = url)
        }

        // Other valid http/https URLs are external
        return TvdbTrailerUsability.External(url = url)
    }

    private fun extractYouTubeVideoId(uri: URI, host: String): String? {
        if (host == "youtu.be") {
            val id = uri.path?.trim('/')?.substringBefore('/')?.trim().orEmpty()
            return id.takeIf { it.matches(YOUTUBE_VIDEO_ID_REGEX) }
        }

        if (host == "youtube.com" || host.endsWith(".youtube.com")) {
            val path = uri.path.orEmpty()
            val query = uri.rawQuery.orEmpty()

            // /watch?v=ID
            if (path.startsWith("/watch")) {
                val videoId = query.split('&')
                    .firstOrNull { it.startsWith("v=") }
                    ?.substringAfter("v=")
                    ?.substringBefore('&')
                    ?.trim()
                return videoId?.takeIf { it.matches(YOUTUBE_VIDEO_ID_REGEX) }
            }

            // /embed/ID or /v/ID or /shorts/ID
            val prefixes = listOf("/embed/", "/v/", "/shorts/")
            for (prefix in prefixes) {
                if (path.startsWith(prefix)) {
                    val id = path.removePrefix(prefix).substringBefore('/').substringBefore('?').trim()
                    return id.takeIf { it.matches(YOUTUBE_VIDEO_ID_REGEX) }
                }
            }
        }

        return null
    }
}
