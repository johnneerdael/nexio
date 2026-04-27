package com.nexio.tv.core.metadata.composition

import com.nexio.tv.data.repository.ContinueWatchingRecord

data class ProfileMetadataOverlay(
    val profileId: Int,
    val watched: Boolean?,
    val progress: PlaybackProgress?,
    val listMembership: ListMembership?,
    val scrobbleState: ScrobbleState?,
    val userRating: Int?,
    val continueWatching: ContinueWatchingRecord?
) {
    init {
        require(profileId > 0) { "ProfileMetadataOverlay.profileId must be positive" }
    }
}

data class PlaybackProgress(val positionMs: Long, val durationMs: Long)
data class ListMembership(val listIds: Set<String>)
data class ScrobbleState(val active: Boolean, val updatedAtMs: Long)
