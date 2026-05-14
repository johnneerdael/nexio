package com.nexio.tv.data.repository

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
        every { trakt.observeAllItems() } returns flowOf(listOf(entry("tt2543164", setOf(TraktLibraryService.WATCHLIST_KEY))))
        every { simkl.observeAllItems() } returns flowOf(emptyList())

        val repo = UnifiedWatchlistRepository(trakt, simkl)
        val rows = repo.memberships.first()

        assertEquals(1, rows.size)
        assertEquals(setOf(UnifiedWatchlistSource.TRAKT), rows.single().presentIn)
    }

    @Test
    fun `ignores non watchlist provider list memberships`() = runTest {
        val trakt = mockk<TraktLibraryService>()
        val simkl = mockk<SimklLibraryService>()
        every { trakt.observeAllItems() } returns flowOf(listOf(entry("tt1111111", setOf("personal:list"))))
        every { simkl.observeAllItems() } returns flowOf(listOf(entry("tt2222222", setOf(SimklLibraryService.COMPLETED_KEY))))

        val repo = UnifiedWatchlistRepository(trakt, simkl)

        assertEquals(emptyList<Any>(), repo.memberships.first())
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
