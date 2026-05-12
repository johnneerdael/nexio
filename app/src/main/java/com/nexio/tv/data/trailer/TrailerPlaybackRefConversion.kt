package com.nexio.tv.data.trailer

import com.nexio.tv.core.metadata.router.resolver.TrailerPlaybackRef
import com.nexio.tv.data.remote.api.TmdbVideoResult

internal fun rankedTmdbTrailerPlaybackRefs(
    videos: List<TmdbVideoResult>,
    originalLanguage: String? = null
): List<TrailerPlaybackRef> =
    rankTmdbVideoCandidates(videos, originalLanguage).mapNotNull { video ->
        video.key
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let(TrailerPlaybackRef::YouTubeId)
    }

internal fun rankedTmdbTrailerYoutubeIds(
    videos: List<TmdbVideoResult>,
    originalLanguage: String? = null
): List<String> =
    rankTmdbVideoCandidates(videos, originalLanguage).mapNotNull { video ->
        video.key
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }
