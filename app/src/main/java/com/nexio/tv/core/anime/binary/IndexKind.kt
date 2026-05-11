package com.nexio.tv.core.anime.binary

internal enum class IndexKind(val slot: Int) {
    BY_KITSU(0),
    BY_MAL(1),
    BY_ANILIST(2),
    BY_ANIDB(3),
    BY_TMDB_MOVIE(4),
    BY_TVDB(5),
    BY_TMDB_TV(6),
    BY_IMDB(7),
    BY_ANIDB_EPISODE(8),
}
