package com.nexio.tv.data.trailer

import com.nexio.tv.data.remote.api.TmdbVideoResult
import com.nexio.tv.domain.model.Stream
import java.time.Instant

private val SEASON_REGEX = Regex("""(?:\bseason\s*|(?<![a-z0-9])s)\s*0*([0-9]{1,2})\b""")

internal data class SeasonMediaAvailability(
    val hasTrailerOrTeaser: Boolean = false,
    val hasRecap: Boolean = false
)

internal sealed interface SeasonMediaCandidate {
    data class TmdbYouTube(val youtubeId: String) : SeasonMediaCandidate
    data class Streailer(val candidate: StreailerTrailerCandidate) : SeasonMediaCandidate
}

internal fun rankTmdbRecapCandidates(
    results: List<TmdbVideoResult>,
    originalLanguage: String? = null
): List<TmdbVideoResult> {
    return results
        .asSequence()
        .filter { (it.site ?: "").equals("YouTube", ignoreCase = true) }
        .filter { !it.key.isNullOrBlank() }
        .filter { isTmdbVideoLanguageEligible(it.iso6391, originalLanguage) }
        .filter { it.type?.trim()?.lowercase() == "recap" }
        .sortedWith(
            compareBy<TmdbVideoResult> { if (it.official == true) 0 else 1 }
                .thenByDescending { it.size ?: 0 }
                .thenByDescending { parseSeasonMediaPublishedAtEpoch(it.publishedAt) }
        )
        .toList()
}

internal fun selectStreailerRecapCandidate(streams: List<Stream>): StreailerTrailerCandidate? {
    return streams
        .asSequence()
        .filter(::isRecapStream)
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

internal fun selectSeasonStreailerTrailerCandidate(
    streams: List<Stream>,
    seasonNumber: Int
): StreailerTrailerCandidate? {
    return streams
        .asSequence()
        .filter(::isTrailerOrTeaserStream)
        .filter { matchesSeasonStream(it, seasonNumber) }
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

internal fun selectSeasonStreailerRecapCandidate(
    streams: List<Stream>,
    seasonNumber: Int
): StreailerTrailerCandidate? {
    return streams
        .asSequence()
        .filter(::isRecapStream)
        .filter { matchesSeasonStream(it, seasonNumber) }
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

internal fun orderedSeasonRecapCandidates(
    tmdbResults: List<TmdbVideoResult>,
    streailerRecap: StreailerTrailerCandidate?,
    originalLanguage: String? = null
): List<SeasonMediaCandidate> {
    val tmdbCandidates = rankTmdbRecapCandidates(tmdbResults, originalLanguage)
        .mapNotNull { result ->
            result.key
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let(SeasonMediaCandidate::TmdbYouTube)
        }
    val streailerCandidate = streailerRecap
        ?.takeIf { !it.youtubeId.isNullOrBlank() || extractYouTubeVideoId(it.externalUrl.orEmpty()) != null }
        ?.let(SeasonMediaCandidate::Streailer)

    return buildList {
        addAll(tmdbCandidates)
        if (streailerCandidate != null) add(streailerCandidate)
    }
}

private fun parseSeasonMediaPublishedAtEpoch(value: String?): Long {
    if (value.isNullOrBlank()) return Long.MIN_VALUE
    return runCatching { Instant.parse(value).toEpochMilli() }.getOrDefault(Long.MIN_VALUE)
}

private fun matchesSeasonStream(stream: Stream, seasonNumber: Int): Boolean {
    val combinedText = listOf(stream.name, stream.title, stream.description)
        .joinToString(" ")
        .lowercase()

    if (seasonNumber == 0) {
        if ("special" in combinedText) return true
    }

    val seasonMatches = SEASON_REGEX.findAll(combinedText)
        .mapNotNull { it.groupValues.getOrNull(1)?.toIntOrNull() }
        .toList()

    if (seasonMatches.isEmpty()) return false
    return seasonMatches.contains(seasonNumber)
}
