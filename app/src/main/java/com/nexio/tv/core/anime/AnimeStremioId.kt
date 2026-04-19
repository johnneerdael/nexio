package com.nexio.tv.core.anime

enum class AnimeIdSource {
    KITSU,
    MAL,
    ANILIST,
    ANIDB,
    TMDB,
    TVDB,
    IMDB
}

data class AnimeStremioId(
    val source: AnimeIdSource,
    val value: String
) {
    companion object {
        fun parse(raw: String?): AnimeStremioId? {
            val trimmed = raw?.trim().orEmpty()
            if (trimmed.isBlank()) return null
            if (trimmed.startsWith("tt", ignoreCase = true)) {
                return AnimeStremioId(
                    source = AnimeIdSource.IMDB,
                    value = trimmed.substringBefore(':').substringBefore('/').trim()
                )
            }

            val prefix = trimmed.substringBefore(':', missingDelimiterValue = "").lowercase()
            val value = trimmed.substringAfter(':', missingDelimiterValue = "").trim()
            if (value.isBlank()) return null

            val source = when (prefix) {
                "kitsu" -> AnimeIdSource.KITSU
                "mal" -> AnimeIdSource.MAL
                "anilist" -> AnimeIdSource.ANILIST
                "anidb" -> AnimeIdSource.ANIDB
                "tmdb" -> AnimeIdSource.TMDB
                "tvdb" -> AnimeIdSource.TVDB
                "imdb" -> AnimeIdSource.IMDB
                else -> return null
            }

            return AnimeStremioId(
                source = source,
                value = value.substringBefore(':').substringBefore('/').trim()
            )
        }
    }
}
