package com.nexio.tv.domain.model

/**
 * Minimal domain representation of an episode to be marked as watched.
 * Only the fields consumed by [com.nexio.tv.domain.repository.WatchProgressRepository.markAsCompletedBatch]
 * are included, with no transport-layer DTO dependency.
 */
data class SeasonEpisodeMark(
    val episodeNumber: Int?,
    val airDate: String?
)
