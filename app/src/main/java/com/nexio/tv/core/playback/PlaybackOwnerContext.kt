package com.nexio.tv.core.playback

data class PlaybackOwnerContext(
    val ownerProfileId: Int,
    val ownerSessionId: String,
    val startedAtEpochMs: Long
) {
    init {
        require(ownerProfileId > 0) { "PlaybackOwnerContext.ownerProfileId must be positive" }
        require(ownerSessionId.isNotBlank()) { "PlaybackOwnerContext.ownerSessionId must not be blank" }
        require(startedAtEpochMs > 0L) { "PlaybackOwnerContext.startedAtEpochMs must be positive" }
    }
}
