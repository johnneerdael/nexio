package com.nexio.tv.data.repository

import com.nexio.tv.core.network.NetworkResult
import com.nexio.tv.data.local.DebugSettingsDataStore
import com.nexio.tv.data.remote.dto.trakt.TraktIdsDto
import com.nexio.tv.data.remote.dto.trakt.TraktListIdsDto
import com.nexio.tv.data.remote.dto.trakt.TraktListItemDto
import com.nexio.tv.data.remote.dto.trakt.TraktListSummaryDto
import com.nexio.tv.data.remote.dto.trakt.TraktMovieDto
import com.nexio.tv.data.remote.dto.trakt.TraktShowDto
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.Meta
import com.nexio.tv.domain.model.PosterShape
import com.nexio.tv.domain.repository.MetaRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import retrofit2.Response

class TraktLibraryServiceTest {

    @Test
    fun `refresh keeps custom lists and hydrates artwork for watchlist and custom list items`() = runTest {
        val traktApi = mockk<com.nexio.tv.data.remote.api.TraktApi>()
        val traktAuthService = mockk<TraktAuthService>()
        val metaRepository = mockk<MetaRepository>()
        val debugSettingsDataStore = mockk<DebugSettingsDataStore>()

        every { debugSettingsDataStore.diskFirstHomeStartupEnabled } returns flowOf(false)

        coEvery { traktAuthService.executeAuthorizedRequest<List<TraktListItemDto>>(any()) } returnsMany listOf(
            Response.success(
                listOf(
                    TraktListItemDto(
                        rank = 1,
                        listedAt = "2026-03-30T12:00:00Z",
                        type = "movie",
                        movie = TraktMovieDto(
                            title = "Watchlist Movie",
                            year = 2024,
                            ids = TraktIdsDto(imdb = "tt1234567", trakt = 10)
                        )
                    )
                )
            ),
            Response.success(emptyList()),
            Response.success(emptyList()),
            Response.success(
                listOf(
                    TraktListItemDto(
                        rank = 1,
                        listedAt = "2026-03-29T12:00:00Z",
                        type = "show",
                        show = TraktShowDto(
                            title = "Custom List Show",
                            year = 2023,
                            ids = TraktIdsDto(tmdb = 321, trakt = 11)
                        )
                    )
                )
            )
        )
        coEvery { traktAuthService.executeAuthorizedRequest<List<TraktListSummaryDto>>(any()) } returns Response.success(
            listOf(
                TraktListSummaryDto(
                    name = "My Custom List",
                    type = "personal",
                    ids = TraktListIdsDto(trakt = 123L, slug = "my-custom-list")
                )
            )
        )

        every { metaRepository.getMetaFromAllAddons(any(), any(), any(), any(), any()) } answers {
            val type = firstArg<String>()
            val id = secondArg<String>()
            flowOf(
                NetworkResult.Success(
                    meta(
                        id = id,
                        type = type,
                        name = "hydrated-$id",
                        poster = "https://image.test/$id/poster.jpg",
                        background = "https://image.test/$id/background.jpg",
                        logo = "https://image.test/$id/logo.png"
                    )
                )
            )
        }

        val service = TraktLibraryService(
            traktApi = traktApi,
            traktAuthService = traktAuthService,
            metaRepository = metaRepository,
            debugSettingsDataStore = debugSettingsDataStore
        )

        advanceUntilIdle()
        service.refreshNow()

        val tabs = service.observeListTabs().first()
        val items = service.observeAllItems().first()

        assertEquals(listOf(TraktLibraryService.WATCHLIST_KEY, "personal:123"), tabs.map { it.key })

        val watchlistItem = items.first { it.listKeys.contains(TraktLibraryService.WATCHLIST_KEY) }
        assertEquals("tt1234567", watchlistItem.id)
        assertNotNull(watchlistItem.poster)
        assertNotNull(watchlistItem.background)
        assertNotNull(watchlistItem.logo)

        val customListItem = items.first { it.listKeys.contains("personal:123") }
        assertEquals("tmdb:321", customListItem.id)
        assertNotNull(customListItem.poster)
        assertNotNull(customListItem.background)
        assertNotNull(customListItem.logo)
    }

    @Test
    fun `refresh hydrates metadata when trakt ids are the only stable ids`() = runTest {
        val traktApi = mockk<com.nexio.tv.data.remote.api.TraktApi>()
        val traktAuthService = mockk<TraktAuthService>()
        val metaRepository = mockk<MetaRepository>()
        val debugSettingsDataStore = mockk<DebugSettingsDataStore>()

        every { debugSettingsDataStore.diskFirstHomeStartupEnabled } returns flowOf(false)

        coEvery { traktAuthService.executeAuthorizedRequest<List<TraktListItemDto>>(any()) } returnsMany listOf(
            Response.success(
                listOf(
                    TraktListItemDto(
                        rank = 1,
                        listedAt = "2026-03-30T12:00:00Z",
                        type = "movie",
                        movie = TraktMovieDto(
                            title = "Trakt Only Movie",
                            year = 2025,
                            ids = TraktIdsDto(trakt = 999)
                        )
                    )
                )
            ),
            Response.success(emptyList())
        )
        coEvery { traktAuthService.executeAuthorizedRequest<List<TraktListSummaryDto>>(any()) } returns Response.success(emptyList())

        every { metaRepository.getMetaFromAllAddons(any(), any(), any(), any(), any()) } answers {
            val type = firstArg<String>()
            val id = secondArg<String>()
            flowOf(
                NetworkResult.Success(
                    meta(
                        id = id,
                        type = type,
                        name = "hydrated-$id",
                        poster = "https://image.test/$id/poster.jpg",
                        background = "https://image.test/$id/background.jpg",
                        logo = "https://image.test/$id/logo.png"
                    )
                )
            )
        }

        val service = TraktLibraryService(
            traktApi = traktApi,
            traktAuthService = traktAuthService,
            metaRepository = metaRepository,
            debugSettingsDataStore = debugSettingsDataStore
        )

        advanceUntilIdle()
        service.refreshNow()

        val item = service.observeAllItems().first().single()

        assertEquals("trakt:999", item.id)
        assertNotNull(item.poster)
        assertNotNull(item.background)
        assertNotNull(item.logo)
    }

    private fun meta(
        id: String,
        type: String,
        name: String,
        poster: String,
        background: String,
        logo: String
    ): Meta {
        val contentType = if (type == "movie") ContentType.MOVIE else ContentType.SERIES
        return Meta(
            id = id,
            type = contentType,
            rawType = type,
            name = name,
            poster = poster,
            posterShape = PosterShape.POSTER,
            background = background,
            logo = logo,
            description = "description-$id",
            releaseInfo = "2024",
            imdbRating = 8.4f,
            genres = listOf("Drama"),
            runtime = null,
            director = emptyList(),
            writer = emptyList(),
            cast = emptyList(),
            castMembers = emptyList(),
            videos = emptyList(),
            productionCompanies = emptyList(),
            networks = emptyList(),
            country = null,
            awards = null,
            language = null,
            links = emptyList(),
            trailerYtIds = emptyList()
        )
    }
}
