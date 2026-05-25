package com.nexio.tv.data.repository

import com.nexio.tv.data.integration.mdblist.MDBListLibraryService
import com.nexio.tv.domain.model.LibraryEntry
import com.nexio.tv.domain.model.PosterShape
import com.nexio.tv.domain.model.UnifiedWatchlistSource
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class UnifiedWatchlistRepositoryTest {
    @Test
    fun `combines trakt and simkl watchlist rows without requiring both sources`() = runTest {
        val trakt = mockk<TraktLibraryService>()
        val simkl = mockk<SimklLibraryService>()
        val mdblist = mockk<MDBListLibraryService>()
        every { trakt.observeAllItems() } returns flowOf(listOf(entry("tt2543164", setOf(TraktLibraryService.WATCHLIST_KEY))))
        every { simkl.observeAllItems() } returns flowOf(emptyList())
        every { mdblist.observeAllItems() } returns flowOf(emptyList())

        val repo = UnifiedWatchlistRepository(trakt, simkl, mdblist)
        val rows = repo.memberships.first()

        assertEquals(1, rows.size)
        assertEquals(setOf(UnifiedWatchlistSource.TRAKT), rows.single().presentIn)
    }

    @Test
    fun `ignores non watchlist provider list memberships`() = runTest {
        val trakt = mockk<TraktLibraryService>()
        val simkl = mockk<SimklLibraryService>()
        val mdblist = mockk<MDBListLibraryService>()
        every { trakt.observeAllItems() } returns flowOf(listOf(entry("tt1111111", setOf("personal:list"))))
        every { simkl.observeAllItems() } returns flowOf(listOf(entry("tt2222222", setOf(SimklLibraryService.COMPLETED_KEY))))
        every { mdblist.observeAllItems() } returns flowOf(listOf(entry("tt3333333", setOf("mdblist:other"))))

        val repo = UnifiedWatchlistRepository(trakt, simkl, mdblist)

        assertEquals(emptyList<Any>(), repo.memberships.first())
    }

    @Test
    fun `merges mdblist watchlist rows with matching strong ids`() = runTest {
        val trakt = mockk<TraktLibraryService>()
        val simkl = mockk<SimklLibraryService>()
        val mdblist = mockk<MDBListLibraryService>()
        every { trakt.observeAllItems() } returns flowOf(listOf(entry("tt0137523", setOf(TraktLibraryService.WATCHLIST_KEY))))
        every { simkl.observeAllItems() } returns flowOf(emptyList())
        every { mdblist.observeAllItems() } returns flowOf(listOf(entry("tt0137523", setOf(MDBListLibraryService.WATCHLIST_KEY))))

        val repo = UnifiedWatchlistRepository(trakt, simkl, mdblist)
        val rows = repo.memberships.first()

        assertEquals(1, rows.size)
        assertEquals(
            setOf(UnifiedWatchlistSource.TRAKT, UnifiedWatchlistSource.MDBLIST),
            rows.single().presentIn
        )
    }

    @Test
    fun `preserves provider display metadata for unified rows`() = runTest {
        val trakt = mockk<TraktLibraryService>()
        val simkl = mockk<SimklLibraryService>()
        val mdblist = mockk<MDBListLibraryService>()
        every { trakt.observeAllItems() } returns flowOf(
            listOf(
                entry("tt32820897", setOf(TraktLibraryService.WATCHLIST_KEY)).copy(
                    name = "Demon Slayer",
                    poster = "nexio-artwork://decision/poster",
                    background = "https://image.tmdb.org/t/p/w1280/backdrop.jpg",
                    logo = "https://image.tmdb.org/t/p/w500/logo.png",
                    description = "The Corps are drawn into the Infinity Castle.",
                    releaseInfo = "2025",
                    imdbRating = 7.7f,
                    genres = listOf("Animation", "Action"),
                    tmdbId = 1311031,
                    traktId = 1071058
                )
            )
        )
        every { simkl.observeAllItems() } returns flowOf(emptyList())
        every { mdblist.observeAllItems() } returns flowOf(emptyList())

        val repo = UnifiedWatchlistRepository(trakt, simkl, mdblist)
        val row = repo.memberships.first().single()

        assertEquals("nexio-artwork://decision/poster", row.poster)
        assertEquals("https://image.tmdb.org/t/p/w1280/backdrop.jpg", row.background)
        assertEquals("https://image.tmdb.org/t/p/w500/logo.png", row.logo)
        assertEquals("The Corps are drawn into the Infinity Castle.", row.description)
        assertEquals(7.7f, row.imdbRating)
        assertEquals(listOf("Animation", "Action"), row.genres)
    }

    private fun entry(id: String, listKeys: Set<String>) = LibraryEntry(
        id = id,
        type = "movie",
        name = "Arrival",
        poster = null,
        posterShape = PosterShape.POSTER,
        background = null,
        logo = null,
        description = null,
        releaseInfo = "2016",
        imdbRating = null,
        genres = emptyList(),
        addonBaseUrl = null,
        listKeys = listKeys,
        imdbId = id
    )
}
