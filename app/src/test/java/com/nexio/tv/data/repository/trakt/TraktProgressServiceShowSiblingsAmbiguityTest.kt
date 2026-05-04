package com.nexio.tv.data.repository.trakt

import com.nexio.tv.core.integration.IntegrationCallResult
import com.nexio.tv.core.metadata.router.MetadataRouterFacade
import com.nexio.tv.data.integration.trakt.TraktIntegrationProvider
import com.nexio.tv.data.remote.dto.trakt.TraktIdsDto
import com.nexio.tv.data.remote.dto.trakt.TraktShowDto
import com.nexio.tv.data.remote.dto.trakt.TraktWatchedEpisodeDto
import com.nexio.tv.data.remote.dto.trakt.TraktWatchedSeasonDto
import com.nexio.tv.data.remote.dto.trakt.TraktWatchedShowItemDto
import com.nexio.tv.data.repository.TraktProgressService
import com.nexio.tv.data.repository.trakt.TraktProgressMutationExecutor
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class TraktProgressServiceShowSiblingsAmbiguityTest {

    private val traktIntegrationProvider = mockk<TraktIntegrationProvider>(relaxed = true)
    private val service = TraktProgressService(
        traktIntegrationProvider = traktIntegrationProvider,
        traktProgressMutationExecutor = mockk<TraktProgressMutationExecutor>(relaxed = true),
        metadataRouterFacade = mockk<MetadataRouterFacade>(relaxed = true)
    )

    @Test
    fun two_shows_sharing_imdb_id_keep_their_own_episode_sets_under_unique_ids() = runBlocking {
        val showA = TraktWatchedShowItemDto(
            plays = 1, lastWatchedAt = "2026-05-04T10:00:00Z",
            show = TraktShowDto(
                title = "Show A", year = 2020,
                ids = TraktIdsDto(trakt = 1, slug = "show-a", imdb = "tt9999999", tvdb = 100)
            ),
            seasons = listOf(TraktWatchedSeasonDto(number = 1, episodes = listOf(
                TraktWatchedEpisodeDto(number = 1, plays = 1, lastWatchedAt = "2026-05-04T10:00:00Z")
            )))
        )
        val showB = TraktWatchedShowItemDto(
            plays = 1, lastWatchedAt = "2026-05-04T11:00:00Z",
            show = TraktShowDto(
                title = "Show B", year = 2021,
                ids = TraktIdsDto(trakt = 2, slug = "show-b", imdb = "tt9999999", tvdb = 200)
            ),
            seasons = listOf(TraktWatchedSeasonDto(number = 1, episodes = listOf(
                TraktWatchedEpisodeDto(number = 2, plays = 1, lastWatchedAt = "2026-05-04T11:00:00Z")
            )))
        )
        coEvery { traktIntegrationProvider.getWatchedShows() } returns
            IntegrationCallResult.Success(listOf(showA, showB))

        val watchedA = service.observeEpisodeProgress("tvdb:100").first()
        val watchedB = service.observeEpisodeProgress("tvdb:200").first()

        assertEquals("Show A's episode (1,1) must be preserved", setOf(1 to 1), watchedA.keys)
        assertEquals("Show B's episode (1,2) must be preserved", setOf(1 to 2), watchedB.keys)
    }

    @Test
    fun ambiguous_imdb_lookup_returns_empty_rather_than_wrong_show() = runBlocking {
        val showA = TraktWatchedShowItemDto(
            plays = 1, lastWatchedAt = "2026-05-04T10:00:00Z",
            show = TraktShowDto(
                title = "Show A", year = 2020,
                ids = TraktIdsDto(trakt = 1, slug = "show-a", imdb = "tt9999999", tvdb = 100)
            ),
            seasons = listOf(TraktWatchedSeasonDto(number = 1, episodes = listOf(
                TraktWatchedEpisodeDto(number = 1, plays = 1, lastWatchedAt = "2026-05-04T10:00:00Z")
            )))
        )
        val showB = TraktWatchedShowItemDto(
            plays = 1, lastWatchedAt = "2026-05-04T11:00:00Z",
            show = TraktShowDto(
                title = "Show B", year = 2021,
                ids = TraktIdsDto(trakt = 2, slug = "show-b", imdb = "tt9999999", tvdb = 200)
            ),
            seasons = listOf(TraktWatchedSeasonDto(number = 1, episodes = listOf(
                TraktWatchedEpisodeDto(number = 2, plays = 1, lastWatchedAt = "2026-05-04T11:00:00Z")
            )))
        )
        coEvery { traktIntegrationProvider.getWatchedShows() } returns
            IntegrationCallResult.Success(listOf(showA, showB))

        val ambiguous = service.observeEpisodeProgress("tt9999999").first()

        assertEquals("ambiguous IMDB lookup must return empty (refuse to guess)", emptySet<Pair<Int, Int>>(), ambiguous.keys)
    }
}
