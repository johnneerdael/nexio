package com.nexio.tv.data.repository.trakt

import com.nexio.tv.core.integration.IntegrationCallResult
import com.nexio.tv.core.metadata.router.MetadataRouterFacade
import com.nexio.tv.data.integration.trakt.TraktIntegrationProvider
import com.nexio.tv.data.integration.trakt.TraktPagedResponse
import com.nexio.tv.data.remote.dto.trakt.TraktHiddenItemDto
import com.nexio.tv.data.remote.dto.trakt.TraktIdsDto
import com.nexio.tv.data.remote.dto.trakt.TraktShowDto
import com.nexio.tv.data.remote.dto.trakt.TraktWatchedEpisodeDto
import com.nexio.tv.data.remote.dto.trakt.TraktWatchedSeasonDto
import com.nexio.tv.data.remote.dto.trakt.TraktWatchedShowItemDto
import com.nexio.tv.data.repository.TraktProgressService
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

class TraktProgressServiceHiddenDroppedFilterTest {

    private val traktIntegrationProvider = mockk<TraktIntegrationProvider>(relaxed = true) {
        every { currentTraktProfileId() } returns 1
    }
    private val service = TraktProgressService(
        traktIntegrationProvider = traktIntegrationProvider,
        traktProgressMutationExecutor = mockk<TraktProgressMutationExecutor>(relaxed = true),
        metadataRouterFacade = mockk<MetadataRouterFacade>(relaxed = true)
    )

    @Test
    fun dropped_show_canonicalised_with_show_kind_is_excluded_from_next_up() = runBlocking {
        val show = TraktShowDto(
            title = "Dropped Show", year = 2020,
            ids = TraktIdsDto(trakt = 99, slug = "dropped-show", tvdb = 999, imdb = "tt9999998", tmdb = 9998)
        )
        val watched = TraktWatchedShowItemDto(
            plays = 1, lastWatchedAt = "2026-05-04T10:00:00Z",
            show = show,
            seasons = listOf(TraktWatchedSeasonDto(number = 1, episodes = listOf(
                TraktWatchedEpisodeDto(number = 1, plays = 1, lastWatchedAt = "2026-05-04T10:00:00Z")
            )))
        )
        coEvery { traktIntegrationProvider.getWatchedShows() } returns
            IntegrationCallResult.Success(listOf(watched))
        coEvery {
            traktIntegrationProvider.getHiddenItems(section = "dropped", type = "show", page = any(), limit = any())
        } returns IntegrationCallResult.Success(
            TraktPagedResponse(body = listOf(TraktHiddenItemDto(type = "show", show = show)), pageCount = 1)
        )
        coEvery {
            traktIntegrationProvider.getHiddenItems(section = "progress_watched", type = any(), page = any(), limit = any())
        } returns IntegrationCallResult.Success(TraktPagedResponse(body = emptyList(), pageCount = 1))

        val nextUp = service.testOnlyDeriveNextUp()

        assertTrue(
            "dropped show must be excluded from next-up regardless of which id form the dropped set uses. Got: ${nextUp.map { it.contentId }}",
            nextUp.none { it.contentId == "tvdb:999" || it.contentId == "tt9999998" }
        )
    }
}
