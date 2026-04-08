package com.nexio.tv.data.repository.trakt

import com.nexio.tv.data.remote.dto.trakt.TraktHistoryEpisodeAddDto
import com.nexio.tv.data.remote.dto.trakt.TraktIdsDto
import com.nexio.tv.data.repository.TraktProgressService
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
    private val traktProgressService: TraktProgressService,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun markSeasonWatched(episodes: List<TraktEpisodeRef>): SeasonMarkResult =
        withContext(ioDispatcher) {
            val dtos = episodes.map { ref ->
                TraktHistoryEpisodeAddDto(ids = TraktIdsDto(trakt = ref.traktId))
            }

            val response = traktProgressService.addHistoryBatch(dtos)

            val notFoundIds: Set<Int> = response.body()
                ?.notFound
                ?.episodes
                ?.mapNotNull { it.ids?.trakt }
                ?.toSet()
                ?: emptySet()

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
