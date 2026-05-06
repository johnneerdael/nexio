package com.nexio.tv.data.trailer

import com.nexio.tv.core.metadata.router.resolver.TrailerPlaybackRef
import com.nexio.tv.data.remote.api.TmdbVideoResult

internal fun rankedTmdbTrailerPlaybackRefs(videos: List<TmdbVideoResult>): List<TrailerPlaybackRef> =
    rankTmdbVideoCandidates(videos).mapNotNull { video ->
        video.key
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let(TrailerPlaybackRef::YouTubeId)
    }

internal fun rankedTmdbTrailerYoutubeIds(videos: List<TmdbVideoResult>): List<String> =
    rankTmdbVideoCandidates(videos).mapNotNull { video ->
        video.key
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }
