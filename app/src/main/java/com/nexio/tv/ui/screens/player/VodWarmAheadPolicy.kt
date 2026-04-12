package com.nexio.tv.ui.screens.player

internal object VodWarmAheadPolicy {
    fun shouldStartWarmAhead(
        useVodCache: Boolean,
        warmAheadEnabled: Boolean
    ): Boolean {
        return useVodCache && warmAheadEnabled
    }

    fun warmAheadCacheKey(
        playbackStreamUrl: String,
        resolvedRequestUrl: String?
    ): String {
        return playbackStreamUrl
    }
}
