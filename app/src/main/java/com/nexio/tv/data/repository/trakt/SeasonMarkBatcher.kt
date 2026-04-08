package com.nexio.tv.data.repository.trakt

import com.nexio.tv.data.trakt.outbox.TraktMutationLifecycleState
import com.nexio.tv.data.trakt.outbox.TraktMutationOutboxCoordinator
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A narrow batcher that collapses season-mark fan-out into a single batched POST /sync/history.
 * Issues exactly one network call per [markSeasonWatched] invocation — no debounce, no queue,
 * no retry. Hard failures propagate as-is to the caller.
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
            val settled = traktMutationOutboxCoordinator.enqueueAndAwait(
                TraktSeasonMarkMutationAdapter.buildEnvelope(
                    showContentId = showContentId,
                    seasonNumber = seasonNumber,
                    episodes = episodes
                )
            )

            if (settled.state == TraktMutationLifecycleState.TERMINAL_FAILED) {
                throw IllegalStateException(settled.lastError ?: "Failed to batch mark season watched")
            }

            val notFoundIds = traktSeasonMarkMutationAdapter.consumeNotFound(settled.id)

            val succeeded = episodes.filter { it.traktId !in notFoundIds }
            val notFound = episodes.filter { it.traktId in notFoundIds }

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
