package com.nexio.tv.domain.repository

import com.nexio.tv.core.integration.ActiveProfileSession
import com.nexio.tv.domain.model.Meta
import com.nexio.tv.domain.model.SeasonEpisodeMark
import com.nexio.tv.domain.model.WatchProgress
import kotlinx.coroutines.flow.Flow

/**
 * Repository for managing watch progress data.
 */
interface WatchProgressRepository {
    
    fun observeProgress(profileId: Int): Flow<List<WatchProgress>>

    fun observeContinueWatching(profileId: Int): Flow<List<WatchProgress>>
    
    /**
     * Get watch progress for a specific content item (movie or series)
     */
    fun getProgress(profileId: Int, contentId: String): Flow<WatchProgress?>
    
    /**
     * Get watch progress for a specific episode
     */
    fun getEpisodeProgress(profileId: Int, contentId: String, season: Int, episode: Int): Flow<WatchProgress?>
    
    /**
     * Get all episode progress for a series as a map of (season, episode) to progress
     */
    fun getAllEpisodeProgress(profileId: Int, contentId: String): Flow<Map<Pair<Int, Int>, WatchProgress>>

    /**
     * Returns whether the item is marked as watched/completed.
     * For series episodes pass both [season] and [episode].
     */
    fun isWatched(profileId: Int, contentId: String, season: Int? = null, episode: Int? = null): Flow<Boolean>
    
    /**
     * Save or update watch progress
     */
    suspend fun upsertProgress(
        profileSession: ActiveProfileSession,
        progress: WatchProgress,
        syncRemote: Boolean = true
    )
    
    /**
     * Remove watch progress (playback only, does not affect Trakt history)
     */
    suspend fun removeProgress(
        profileSession: ActiveProfileSession,
        contentId: String,
        season: Int? = null,
        episode: Int? = null
    )

    /**
     * Remove from watch history (marks as unwatched on Trakt)
     */
    suspend fun removeFromHistory(
        profileSession: ActiveProfileSession,
        contentId: String,
        season: Int? = null,
        episode: Int? = null
    )

    /**
     * Clear all watched/progress data for a full show.
     */
    suspend fun clearShowProgress(profileSession: ActiveProfileSession, contentId: String)

    /**
     * Mark content as completed
     */
    suspend fun markAsCompleted(profileSession: ActiveProfileSession, progress: WatchProgress)

    /**
     * Mark all [episodes] of [seasonNumber] in [meta] as watched using a single batched Trakt POST.
     * Applies an atomic optimistic CW update, handles partial not_found rollback, and forces a
     * snapshot refresh on success.
     */
    suspend fun markAsCompletedBatch(
        profileSession: ActiveProfileSession,
        meta: Meta,
        seasonNumber: Int,
        episodes: List<SeasonEpisodeMark>
    )
    
    /**
     * Clear all watch progress
     */
    suspend fun clearAll(profileSession: ActiveProfileSession)

    /**
     * Clear hydrated localized metadata so it can rebuild for the current app language.
     */
    fun invalidateLocalizedMetadata()
}
