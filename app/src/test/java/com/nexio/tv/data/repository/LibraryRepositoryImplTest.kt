package com.nexio.tv.data.repository

import com.nexio.tv.data.local.TraktAuthDataStore
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryRepositoryImplTest {

    @Test
    fun `hasTraktCache reflects restored service cache before auth flow settles`() = runTest {
        val traktAuthState = MutableStateFlow(false)
        val traktAuthDataStore = mockk<TraktAuthDataStore>()
        val traktLibraryService = mockk<TraktLibraryService>()
        val debridLibraryService = mockk<DebridLibraryService>()

        every { traktAuthDataStore.isEffectivelyAuthenticated } returns traktAuthState
        every { traktLibraryService.observeHasCache() } returns flowOf(true)
        every { traktLibraryService.observeAllItems() } returns flowOf(emptyList())
        every { traktLibraryService.observeListTabs() } returns flowOf(emptyList())
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
    }
}
