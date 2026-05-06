package com.nexio.tv.data.repository.trakt

import com.nexio.tv.data.repository.ContinueWatchingSnapshotService
import com.nexio.tv.data.repository.TrackingAuthSession
import com.nexio.tv.data.repository.TraktAuthService
import com.nexio.tv.data.trakt.outbox.TraktMutationOutboxCoordinator
import com.nexio.tv.domain.model.TrackingProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Collapses season-mark fan-out into one queued POST /sync/history mutation and returns as soon
 * as the durable outbox accepts the command.
 */
@Singleton
class SeasonMarkBatcher @Inject constructor(
    private val traktMutationOutboxCoordinator: TraktMutationOutboxCoordinator,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val traktAuthService: TraktAuthService,
) {
    suspend fun markSeasonWatched(
        showContentId: String,
        seasonNumber: Int,
        episodes: List<TraktEpisodeRef>,
        rollbackState: ContinueWatchingSnapshotService.EpisodeRollbackState,
        profileId: Int = 1
    ) {
        withContext(ioDispatcher) {
            traktMutationOutboxCoordinator.enqueueAndDrain(
                TraktSeasonMarkMutationAdapter.buildEnvelope(
                    showContentId = showContentId,
                    seasonNumber = seasonNumber,
                    episodes = episodes,
                    rollbackState = rollbackState,
                    session = mutationSession(profileId)
                )
            )
        }
    }

    private suspend fun mutationSession(profileId: Int): TrackingAuthSession {
        val base = TrackingAuthSession(TrackingProvider.TRAKT, profileId)
        return traktAuthService.mutationAccountScopedSession(base)
    }
}

/**
 * Reference to a season episode using both app-side coordinates and its Trakt integer ID.
 */
data class TraktEpisodeRef(
    val episodeNumber: Int,
    val traktId: Int
)
