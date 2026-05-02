package com.nexio.tv.data.integration.subtitles.wyzie

import com.nexio.tv.domain.model.WyzieIdHints

/**
 * Derives [WyzieIdHints] from the player's `contentId` routing string.
 *
 * Routing-prefix vocabulary observed in this codebase
 * (see [com.nexio.tv.domain.model.RailItemPreview.bestSupportedRoutingId]):
 *   - `tt…`        — IMDB id (no prefix separator; preserved verbatim including `tt`)
 *   - `tmdb:N`     — TMDB id (numeric tail)
 *   - `kitsu:N`    — Kitsu id (anime detector; not sent to Wyzie)
 *   - `mal:N`      — MyAnimeList id (anime detector)
 *   - `anilist:N`  — AniList id (anime detector)
 *   - `anidb:N`    — AniDB id (anime detector)
 *   - `tvdb:N`     — TheTVDB id (Wyzie has no TVDB lane → skipped)
 *
 * Unknown prefixes and null/blank input yield [WyzieIdHints.EMPTY].
 */
object WyzieIdHintsParser {

    fun parse(contentId: String?): WyzieIdHints {
        val trimmed = contentId?.trim().orEmpty()
        if (trimmed.isEmpty()) return WyzieIdHints.EMPTY

        if (trimmed.startsWith("tt", ignoreCase = true)) {
            // Preserve original casing for the digits but normalize the "tt" prefix to lower.
            return WyzieIdHints(imdb = "tt" + trimmed.substring(2))
        }

        val colon = trimmed.indexOf(':')
        if (colon <= 0 || colon == trimmed.lastIndex) return WyzieIdHints.EMPTY
        val namespace = trimmed.substring(0, colon).lowercase()
        val value = trimmed.substring(colon + 1)

        return when (namespace) {
            "tmdb" -> value.toIntOrNull()?.let { WyzieIdHints(tmdb = it) } ?: WyzieIdHints.EMPTY
            "kitsu" -> WyzieIdHints(kitsu = value)
            "mal" -> WyzieIdHints(mal = value)
            "anilist" -> WyzieIdHints(anilist = value)
            "anidb" -> WyzieIdHints(anidb = value)
            else -> WyzieIdHints.EMPTY
        }
    }
}
