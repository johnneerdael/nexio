package com.nexio.tv.data.repository

import com.nexio.tv.data.local.TraktAuthDataStore
import com.nexio.tv.domain.model.LibraryEntry
import com.nexio.tv.domain.model.LibraryListTab
import com.nexio.tv.domain.model.LibrarySourceMode
import com.nexio.tv.domain.model.PosterShape
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryRepositoryImplTest {

    @Test
    fun `restored trakt cache is surfaced before auth flow settles`() = runTest {
        val traktAuthState = MutableStateFlow(false)
        val traktAuthDataStore = mockk<TraktAuthDataStore>()
        val traktLibraryService = mockk<TraktLibraryService>()
        val debridLibraryService = mockk<DebridLibraryService>()
        val traktTabs = listOf(
            LibraryListTab(
                key = TraktLibraryService.WATCHLIST_KEY,
                title = "Watchlist",
                type = LibraryListTab.Type.WATCHLIST
            )
        )
        val traktItems = listOf(
            LibraryEntry(
                id = "tt1234567",
                type = "movie",
                name = "Cached Movie",
                poster = null,
                posterShape = PosterShape.POSTER,
                background = null,
                logo = null,
                description = null,
                releaseInfo = null,
                imdbRating = null,
                genres = emptyList(),
                addonBaseUrl = null,
                listKeys = setOf(TraktLibraryService.WATCHLIST_KEY)
            )
        )

        every { traktAuthDataStore.isEffectivelyAuthenticated } returns traktAuthState
        every { traktLibraryService.observeHasCache() } returns flowOf(true)
        every { traktLibraryService.observeAllItems() } returns flowOf(traktItems)
        every { traktLibraryService.observeListTabs() } returns flowOf(traktTabs)
        every { traktLibraryService.observeIsRefreshing() } returns flowOf(false)
        every { debridLibraryService.observeIsConnected() } returns flowOf(false)
        every { debridLibraryService.observeIsRefreshing() } returns flowOf(false)
        every { debridLibraryService.observeItems() } returns flowOf(emptyList())
        every { debridLibraryService.observeListTabs() } returns flowOf(emptyList())

        val repository = LibraryRepositoryImpl(
            traktAuthDataStore = traktAuthDataStore,
            traktLibraryService = traktLibraryService,
            debridLibraryService = debridLibraryService
        )

        assertTrue(repository.hasTraktCache.first())
        assertEquals(LibrarySourceMode.TRAKT, repository.sourceMode.first())
        assertEquals(traktTabs, repository.listTabs.first())
        assertEquals(traktItems, repository.libraryItems.first())
    }
}
