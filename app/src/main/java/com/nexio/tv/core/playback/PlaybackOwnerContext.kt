package com.nexio.tv.core.playback

import com.nexio.tv.core.integration.ProviderAccountRef

data class PlaybackOwnerContext(
    val ownerProfileId: Int,
    val ownerSessionId: String,
    val traktAccount: ProviderAccountRef?,
    val simklAccount: ProviderAccountRef?,
    val startedAtEpochMs: Long
) {
    init {
        require(ownerProfileId > 0) { "PlaybackOwnerContext.ownerProfileId must be positive" }
        require(ownerSessionId.isNotBlank()) { "PlaybackOwnerContext.ownerSessionId must not be blank" }
        require(startedAtEpochMs > 0L) { "PlaybackOwnerContext.startedAtEpochMs must be positive" }
    }
}
