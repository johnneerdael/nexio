package com.nexio.tv.domain.repository

import com.nexio.tv.domain.model.Subtitle

/**
 * Native (non-addon) source for OpenSubtitles search.
 *
 * Implementations search rest.opensubtitles.org by IMDb id (and optionally
 * season/episode), and — when a [videoHash] + [videoSize] are supplied —
 * also issue a moviehash query and merge the result. Hash-matched rows are
 * marked via [Subtitle.isHashMatch] so the UI can highlight them.
 */
interface OpenSubtitlesSource {
    suspend fun search(
        type: String,
        id: String,
        videoId: String? = null,
        videoHash: String? = null,
        videoSize: Long? = null,
        filename: String? = null
    ): List<Subtitle>
}
