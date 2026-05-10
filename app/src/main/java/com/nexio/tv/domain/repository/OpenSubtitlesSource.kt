package com.nexio.tv.domain.repository

import com.nexio.tv.domain.model.Subtitle

interface OpenSubtitlesSource {
    suspend fun search(
        type: String,
        id: String,
        videoId: String? = null,
        videoHash: String? = null,
        videoSize: Long? = null,
        filename: String? = null,
        imdbHint: String? = null
    ): List<Subtitle>
}
