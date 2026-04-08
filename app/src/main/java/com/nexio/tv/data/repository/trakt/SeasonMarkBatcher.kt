package com.nexio.tv.data.repository.trakt

import com.nexio.tv.data.trakt.outbox.TraktMutationOutboxCoordinator
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A narrow batcher that collapses season-mark fan-out into one queued
 * POST /sync/history mutation and waits for that durable outbox item to settle.
 */
@Singleton
class SeasonMarkBatcher @Inject constructor(
    private val traktMutationOutboxCoordinator: TraktMutationOutboxCoordinator,
    private val traktSeasonMarkMutationAdapter: TraktSeasonMarkMutationAdapter,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun markSeasonWatched(
        showContentId: String,
        seasonNumber: Int,
        episodes: List<TraktEpisodeRef>
    ): SeasonMarkResult =
        withContext(ioDispatcher) {
            val settled = traktMutationOutboxCoordinator.enqueueAndAwaitOrThrow(
                TraktSeasonMarkMutationAdapter.buildEnvelope(
                    showContentId = showContentId,
                    seasonNumber = seasonNumber,
                    episodes = episodes
                ),
                fallbackMessage = "Failed to batch mark season watched"
            )

            val notFoundIds = traktSeasonMarkMutationAdapter.consumeNotFound(settled.id)
            val (notFound, succeeded) = episodes.partition { it.traktId in notFoundIds }

            SeasonMarkResult(succeeded = succeeded, notFound = notFound)
        }
}

/**
 * A lightweight reference to a Trakt episode identified by its Trakt integer ID.
 */
data class TraktEpisodeRef(val traktId: Int)

data class SeasonMarkResult(
    val succeeded: List<TraktEpisodeRef>,
    val notFound: List<TraktEpisodeRef>,
)
