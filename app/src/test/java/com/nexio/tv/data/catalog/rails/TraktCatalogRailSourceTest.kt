package com.nexio.tv.data.catalog.rails

import com.nexio.tv.core.catalog.rails.CatalogRailDescriptor
import com.nexio.tv.core.catalog.rails.CatalogRailProvider
import com.nexio.tv.core.integration.IntegrationFetchResult
import com.nexio.tv.data.integration.railpreview.TraktRailPreviewMapper
import com.nexio.tv.data.integration.trakt.TraktIntegrationProvider
import com.nexio.tv.data.remote.dto.trakt.TraktIdsDto
import com.nexio.tv.data.remote.dto.trakt.TraktMovieDto
import com.nexio.tv.data.remote.dto.trakt.TraktShowDto
import com.nexio.tv.data.remote.dto.trakt.TraktTrendingMovieItemDto
import com.nexio.tv.data.remote.dto.trakt.TraktTrendingShowItemDto
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TraktCatalogRailSourceTest {

    private val integrationProvider = mockk<TraktIntegrationProvider>()
    private val mapper = TraktRailPreviewMapper()
    private val codec = TraktRailIdCodec()
    private val source = TraktCatalogRailSource(
        integrationProvider = integrationProvider,
        mapper = mapper,
        railIdCodec = codec
    )

    private fun ids(trakt: Int) = TraktIdsDto(trakt = trakt, slug = "slug-$trakt")
    private fun movie(traktId: Int, title: String = "Movie $traktId", year: Int = 2025) =
        TraktMovieDto(title = title, year = year, ids = ids(traktId))
    private fun show(traktId: Int, title: String = "Show $traktId", year: Int = 2025) =
        TraktShowDto(title = title, year = year, ids = ids(traktId))
    private fun trendingMovie(traktId: Int) =
        TraktTrendingMovieItemDto(watchers = 100 + traktId, movie = movie(traktId))
    private fun trendingShow(traktId: Int) =
        TraktTrendingShowItemDto(watchers = 100 + traktId, show = show(traktId))

    @Test
    fun `providerId is TRAKT`() {
        assertEquals(CatalogRailProvider.TRAKT, source.providerId)
    }

    @Test
    fun `availableRails returns four global descriptors in stable order`() = runBlocking {
        val rails = source.availableRails(profileId = 1)
        assertEquals(4, rails.size)
        assertEquals("trakt::trending_movies", rails[0].railId)
        assertEquals("trakt::trending_shows", rails[1].railId)
        assertEquals("trakt::popular_movies", rails[2].railId)
        assertEquals("trakt::popular_shows", rails[3].railId)
        rails.forEach { assertEquals(CatalogRailProvider.TRAKT, it.providerId) }
        assertEquals(listOf(0, 1, 2, 3), rails.map { it.sortOrder })
    }

    @Test
    fun `availableRails returns the same descriptors for any profileId (global rails)`() = runBlocking {
        assertEquals(source.availableRails(profileId = 1), source.availableRails(profileId = 99))
    }

    @Test
    fun `fetchRail TRENDING_MOVIES dispatches to fetchTrendingMovies and maps results`() = runBlocking {
        coEvery { integrationProvider.fetchTrendingMovies(any()) } returns
            listOf(trendingMovie(1), trendingMovie(2))

        val result = source.fetchRail(
            profileId = 1,
            rail = CatalogRailDescriptor(
                railId = "trakt::trending_movies",
                providerId = CatalogRailProvider.TRAKT,
                displayTitle = "x",
                sortOrder = 0
            )
        )

        assertTrue("expected Updated, got $result", result is IntegrationFetchResult.Updated)
        val items = (result as IntegrationFetchResult.Updated).value
        assertEquals(2, items.size)
        assertTrue(items.all { it.railId == "trakt::trending_movies" })
        assertEquals("trakt:movie:1", items[0].sourceItemId)
        assertEquals("trakt:movie:2", items[1].sourceItemId)
    }

    @Test
    fun `fetchRail TRENDING_SHOWS dispatches to fetchTrendingShows and maps results`() = runBlocking {
        coEvery { integrationProvider.fetchTrendingShows(any()) } returns
            listOf(trendingShow(10), trendingShow(20))

        val result = source.fetchRail(
            profileId = 1,
            rail = CatalogRailDescriptor(
                railId = "trakt::trending_shows",
                providerId = CatalogRailProvider.TRAKT,
                displayTitle = "x",
                sortOrder = 0
            )
        )

        assertTrue(result is IntegrationFetchResult.Updated)
        val items = (result as IntegrationFetchResult.Updated).value
        assertEquals(2, items.size)
        assertEquals("trakt:show:10", items[0].sourceItemId)
        assertEquals("trakt:show:20", items[1].sourceItemId)
    }

    @Test
    fun `fetchRail POPULAR_MOVIES dispatches to fetchPopularMovies and maps via mapMovie`() = runBlocking {
        coEvery { integrationProvider.fetchPopularMovies(any()) } returns
            listOf(movie(50), movie(60))

        val result = source.fetchRail(
            profileId = 1,
            rail = CatalogRailDescriptor(
                railId = "trakt::popular_movies",
                providerId = CatalogRailProvider.TRAKT,
                displayTitle = "x",
                sortOrder = 0
            )
        )

        assertTrue(result is IntegrationFetchResult.Updated)
        val items = (result as IntegrationFetchResult.Updated).value
        assertEquals(2, items.size)
        assertEquals("trakt:movie:50", items[0].sourceItemId)
    }

    @Test
    fun `fetchRail POPULAR_SHOWS dispatches to fetchPopularShows and maps via mapShow`() = runBlocking {
        coEvery { integrationProvider.fetchPopularShows(any()) } returns
            listOf(show(70), show(80))

        val result = source.fetchRail(
            profileId = 1,
            rail = CatalogRailDescriptor(
                railId = "trakt::popular_shows",
                providerId = CatalogRailProvider.TRAKT,
                displayTitle = "x",
                sortOrder = 0
            )
        )

        assertTrue(result is IntegrationFetchResult.Updated)
        val items = (result as IntegrationFetchResult.Updated).value
        assertEquals(2, items.size)
        assertEquals("trakt:show:70", items[0].sourceItemId)
    }

    @Test
    fun `fetchRail returns Missing when railId cannot be decoded`() = runBlocking {
        val result = source.fetchRail(
            profileId = 1,
            rail = CatalogRailDescriptor(
                railId = "addon::foo::movie::top",
                providerId = CatalogRailProvider.TRAKT,
                displayTitle = "x",
                sortOrder = 0
            )
        )
        assertEquals(IntegrationFetchResult.Missing, result)
    }

    @Test
    fun `fetchRail returns Missing when integration provider returns null`() = runBlocking {
        coEvery { integrationProvider.fetchTrendingMovies(any()) } returns null

        val result = source.fetchRail(
            profileId = 1,
            rail = CatalogRailDescriptor(
                railId = "trakt::trending_movies",
                providerId = CatalogRailProvider.TRAKT,
                displayTitle = "x",
                sortOrder = 0
            )
        )

        assertEquals(IntegrationFetchResult.Missing, result)
    }

    @Test
    fun `fetchRail filters out items the mapper rejects (mapNotNull)`() = runBlocking {
        // An item with no traktId in its movie can't be mapped; mapTrendingMovie returns null.
        val unmappable = TraktTrendingMovieItemDto(
            watchers = 5,
            movie = TraktMovieDto(title = "no-id", year = 2025, ids = TraktIdsDto(trakt = null))
        )
        coEvery { integrationProvider.fetchTrendingMovies(any()) } returns
            listOf(trendingMovie(1), unmappable, trendingMovie(2))

        val result = source.fetchRail(
            profileId = 1,
            rail = CatalogRailDescriptor(
                railId = "trakt::trending_movies",
                providerId = CatalogRailProvider.TRAKT,
                displayTitle = "x",
                sortOrder = 0
            )
        )

        assertTrue(result is IntegrationFetchResult.Updated)
        val items = (result as IntegrationFetchResult.Updated).value
        assertEquals(2, items.size) // unmappable item dropped
        assertEquals("trakt:movie:1", items[0].sourceItemId)
        assertEquals("trakt:movie:2", items[1].sourceItemId)
    }
}
