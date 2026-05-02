package com.nexio.tv.domain.model

import androidx.compose.runtime.Immutable

/**
 * Stable id hints used by the Wyzie subtitle integration.
 *
 * Wyzie's `/search` endpoint only accepts IMDB or TMDB ids (its anime sources resolve via TMDB
 * internally). `kitsu`/`mal`/`anilist`/`anidb` are carried so that [WyzieSourceRouter] can
 * detect anime content and select the anime source list — the values themselves are NOT sent
 * to Wyzie.
 *
 * `imdb` MUST preserve the `tt` prefix. `tmdb` is the integer TMDB id.
 */
@Immutable
data class WyzieIdHints(
    val imdb: String? = null,
    val tmdb: Int? = null,
    val kitsu: String? = null,
    val mal: String? = null,
    val anilist: String? = null,
    val anidb: String? = null,
) {
    val isAnime: Boolean
        get() = kitsu != null || mal != null || anilist != null || anidb != null

    val hasUsableWyzieId: Boolean
        get() = !imdb.isNullOrBlank() || tmdb != null

    companion object {
        val EMPTY = WyzieIdHints()
    }
}
