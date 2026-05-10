package com.nexio.tv.domain.repository

import com.nexio.tv.domain.model.Subtitle
import com.nexio.tv.domain.model.WyzieIdHints

interface SubtitleRepository {
    /**
     * Fetches subtitles from all installed addons that support subtitles AND from the
     * built-in Wyzie lane (when configured).
     *
     * @param type Content type (movie, series, etc.)
     * @param id Content ID (IMDB ID, etc.)
     * @param videoId Optional video ID for series (e.g., tt1234567:1:1 for series episode)
     * @param videoHash Optional OpenSubtitles file hash
     * @param videoSize Optional video file size in bytes
     * @param filename Optional video filename
     * @param wyzieHints Stable id hints for the Wyzie lane. Pass [WyzieIdHints.EMPTY] to skip
     *                   the Wyzie lane entirely (addons still run).
     * @param season Episode season number (paired with [episode]) for TV searches.
     * @param episode Episode number (paired with [season]) for TV searches.
     * @param imdbId Optional IMDB id sidecar (e.g. "tt1234567"). Used by providers that
     *               only key on IMDB (OpenSubtitles, Wyzie) when the canonical content id
     *               uses a non-IMDB namespace such as `tvdb:N` for series.
     * @return Merged list of subtitles from addons + Wyzie.
     */
    suspend fun getSubtitles(
        type: String,
        id: String,
        videoId: String? = null,
        videoHash: String? = null,
        videoSize: Long? = null,
        filename: String? = null,
        wyzieHints: WyzieIdHints = WyzieIdHints.EMPTY,
        season: Int? = null,
        episode: Int? = null,
        imdbId: String? = null,
    ): List<Subtitle>
}
