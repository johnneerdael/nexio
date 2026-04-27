package com.nexio.tv.data.repository

import com.nexio.tv.domain.model.TrackingProvider

data class ContinueWatchingRecord(
    val profileId: Int,
    val parentId: String,
    val contentId: String,
    val provider: TrackingProvider,
    val routingVersion: Int,
    val positionMs: Long,
    val durationMs: Long,
    val episodeContext: EpisodeContext?,
    val clickTimeDisplayMetadata: ContinueWatchingMetadataSnapshot?,
    val source: Source,
    val updatedAt: Long
) {
    init {
        require(profileId > 0) { "ContinueWatchingRecord.profileId must be positive" }
        require(parentId.isNotBlank()) { "ContinueWatchingRecord.parentId must not be blank" }
        require(contentId.isNotBlank()) { "ContinueWatchingRecord.contentId must not be blank" }
        require(positionMs >= 0L) { "ContinueWatchingRecord.positionMs must be >= 0" }
        require(durationMs >= 0L) { "ContinueWatchingRecord.durationMs must be >= 0" }
        require(routingVersion > 0) { "ContinueWatchingRecord.routingVersion must be positive" }
        require(updatedAt > 0L) { "ContinueWatchingRecord.updatedAt must be positive" }
    }

    fun identityKey(): String {
        val episodeKey = episodeContext?.let { "s${it.season}e${it.number}" }
        return if (episodeKey != null) {
            "profile:$profileId:continue-watching:$parentId:$episodeKey"
        } else {
            "profile:$profileId:continue-watching:$parentId"
        }
    }

    data class EpisodeContext(val season: Int, val number: Int) {
        init {
            require(season >= 0) { "EpisodeContext.season must be >= 0" }
            require(number >= 0) { "EpisodeContext.number must be >= 0" }
        }
    }

    enum class Source { LOCAL, SYNTHETIC, REMOTE }
}
