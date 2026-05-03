package com.nexio.tv.data.integration.subtitles.wyzie

import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.WyzieIdHints
import com.nexio.tv.domain.model.WyzieSource

/**
 * Pure mapping from `(content type, hints)` to the curated Wyzie source list.
 *
 * Anime detection: any of `kitsu`/`mal`/`anilist`/`anidb` non-null. yify is intentionally
 * dropped from the movie list (SRT-in-ZIP, Media3 cannot unwrap).
 */
object WyzieSourceRouter {

    private val NON_ANIME_MOVIE = listOf(
        WyzieSource.OPENSUBTITLES,
        WyzieSource.SUBDL,
        WyzieSource.SUBF2M,
        WyzieSource.PODNAPISI,
    )

    private val NON_ANIME_TV = listOf(
        WyzieSource.OPENSUBTITLES,
        WyzieSource.SUBDL,
        WyzieSource.SUBF2M,
        WyzieSource.PODNAPISI,
        WyzieSource.GESTDOWN,
    )

    private val ANIME_MOVIE = listOf(
        WyzieSource.JIMAKU,
        WyzieSource.AJATTTOOLS,
    )

    private val ANIME_TV = listOf(
        WyzieSource.ANIMETOSHO,
        WyzieSource.JIMAKU,
        WyzieSource.KITSUNEKKO,
        WyzieSource.AJATTTOOLS,
    )

    fun sourcesFor(type: ContentType, hints: WyzieIdHints): List<WyzieSource> {
        val anime = hints.isAnime
        return when (type) {
            ContentType.MOVIE -> if (anime) ANIME_MOVIE else NON_ANIME_MOVIE
            ContentType.SERIES, ContentType.TV -> if (anime) ANIME_TV else NON_ANIME_TV
            ContentType.CHANNEL, ContentType.PERSON, ContentType.UNKNOWN -> emptyList()
        }
    }
}
