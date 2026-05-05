package com.nexio.tv.core.anime.projection

import com.nexio.tv.domain.model.ProviderIds

enum class AnimeSubtype {
    TV, MOVIE, OVA, ONA, SPECIAL, MUSIC, UNKNOWN;
    companion object {
        fun parse(raw: String?): AnimeSubtype = when (raw?.uppercase()) {
            "TV" -> TV
            "MOVIE" -> MOVIE
            "OVA" -> OVA
            "ONA" -> ONA
            "SPECIAL" -> SPECIAL
            "MUSIC" -> MUSIC
            else -> UNKNOWN
        }
    }
}

enum class AnimeResourceRole {
    FRANCHISE_SINGLE_SERIES,
    SEASONAL_ENTRY,
    MOVIE,
    SPECIAL,
    UNKNOWN,
}

data class AnimeResourceDescriptor(
    val kitsuId: String,
    val malId: String?,
    val anilistId: String?,
    val anidbId: String?,
    val providerIds: ProviderIds,
    val subtype: AnimeSubtype,
    val startDate: String?,
    val episodeCount: Int?,
    val detectedSeasonNumbers: Set<Int>,
    val role: AnimeResourceRole,
)
