package com.nexio.tv.core.tvdb

enum class TvdbRemoteIdSource {
    TVDB,
    IMDB,
    TMDB,
    TV_MAZE,
    WIKIDATA,
    OFFICIAL_SITE,
    OTHER
}

fun normalizeTvdbRemoteIdSource(sourceName: String?): TvdbRemoteIdSource {
    val normalized = sourceName
        ?.trim()
        ?.lowercase()
        ?.replace(Regex("[^a-z0-9]+"), "")
        .orEmpty()

    return when {
        normalized.isBlank() -> TvdbRemoteIdSource.OTHER
        normalized in setOf("tvdb", "thetvdb", "thetvdbcom") -> TvdbRemoteIdSource.TVDB
        normalized in setOf("imdb", "imdbcom", "internetmoviedatabase") -> TvdbRemoteIdSource.IMDB
        normalized in setOf("tmdb", "themoviedb", "themoviedbcom") -> TvdbRemoteIdSource.TMDB
        normalized in setOf("tvmaze", "tvmazedatabase") -> TvdbRemoteIdSource.TV_MAZE
        normalized == "wikidata" -> TvdbRemoteIdSource.WIKIDATA
        normalized in setOf("official", "officialsite", "website", "site") -> TvdbRemoteIdSource.OFFICIAL_SITE
        sourceName.isOfficialSiteValue() -> TvdbRemoteIdSource.OFFICIAL_SITE
        else -> TvdbRemoteIdSource.OTHER
    }
}

fun normalizeTvdbRemoteIdValue(
    value: String,
    source: TvdbRemoteIdSource
): String {
    val trimmed = value.trim()
    return if (source == TvdbRemoteIdSource.OFFICIAL_SITE) {
        trimmed.trimEnd('/')
    } else {
        trimmed
    }
}

private fun String?.isOfficialSiteValue(): Boolean {
    val candidate = this?.trim()?.lowercase().orEmpty()
    return candidate.startsWith("http://") || candidate.startsWith("https://")
}
