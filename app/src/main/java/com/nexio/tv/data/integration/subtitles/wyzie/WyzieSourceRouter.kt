package com.nexio.tv.data.integration.subtitles.wyzie

import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.WyzieIdHints
import com.nexio.tv.domain.model.WyzieSource

/**
 * Pure mapping from `(content type, hints)` to the Wyzie source list.
 *
 * Wyzie's free tier exposes only `opensubtitles`, `subdl`, and `tvsubtitles`; asking for any
 * other provider returns HTTP 403. Pro-tier scrapers (animetosho, jimaku, kitsunekko, ajatttools,
 * subf2m, podnapisi, gestdown) historically had separate movie/tv and anime/non-anime routing,
 * but in practice that produced 100% 403s for anime users on the free key.
 *
 * To keep behaviour identical for free and pro keys and avoid silent anime degradation, all
 * movie/tv/anime requests now use the same free-tier-accessible source triple. Pro keys still
 * get the same response from these three providers; users wanting the anime-specialised
 * scrapers can re-introduce branching by extending this list.
 *
 * `hints.isAnime` is no longer consulted here — anime detection still drives [WyzieIdHints]
 * construction (so `kitsu:`/`mal:` IDs are recognised) but no longer changes the source set.
 */
object WyzieSourceRouter {

    private val UNIFIED_SOURCES = listOf(
        WyzieSource.OPENSUBTITLES,
        WyzieSource.SUBDL,
        WyzieSource.TVSUBTITLES,
    )

    @Suppress("UNUSED_PARAMETER")
    fun sourcesFor(type: ContentType, hints: WyzieIdHints): List<WyzieSource> =
        when (type) {
            ContentType.MOVIE,
            ContentType.SERIES,
            ContentType.TV -> UNIFIED_SOURCES
            ContentType.CHANNEL,
            ContentType.PERSON,
            ContentType.UNKNOWN -> emptyList()
        }
}
