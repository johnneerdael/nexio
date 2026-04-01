package com.nexio.tv.ui.screens.library

import com.nexio.tv.data.local.LayoutPreferenceDataStore
import com.nexio.tv.domain.model.LibraryEntry
import com.nexio.tv.domain.model.LibraryEntryInput
import com.nexio.tv.domain.model.LibraryListTab
import com.nexio.tv.domain.model.LibrarySourceMode
import com.nexio.tv.domain.model.ListMembershipChanges
import com.nexio.tv.domain.model.ListMembershipSnapshot
import com.nexio.tv.domain.model.TraktListPrivacy
import com.nexio.tv.domain.repository.LibraryRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `first uncached trakt session auto-syncs and blocks until cache exists`() = runTest(dispatcher) {
        val repository = FakeLibraryRepository(
            sourceMode = MutableStateFlow(LibrarySourceMode.TRAKT),
            isSyncing = MutableStateFlow(false),
            hasTraktCache = MutableStateFlow(false),
            libraryItems = MutableStateFlow(emptyList()),
            listTabs = MutableStateFlow(emptyList())
        )
        val viewModel = LibraryViewModel(
            libraryRepository = repository,
            layoutPreferenceDataStore = layoutPreferenceDataStore()
        )

        advanceUntilIdle()

        assertEquals(1, repository.refreshTraktNowCalls)
        assertTrue(viewModel.uiState.value.isLoading)

        repository.isSyncing.value = true
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.isLoading)

        repository.listTabs.value = MutableList(1) {
            LibraryListTab(
                key = "watchlist",
                title = "Watchlist",
                type = LibraryListTab.Type.WATCHLIST
            )
        }
        repository.hasTraktCache.value = true
        repository.isSyncing.value = false
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isLoading)
    }

    @Test
    fun `warm trakt cache never re-enters full-screen loading while syncing`() = runTest(dispatcher) {
        val repository = FakeLibraryRepository(
            sourceMode = MutableStateFlow(LibrarySourceMode.TRAKT),
            isSyncing = MutableStateFlow(true),
            hasTraktCache = MutableStateFlow(true),
            libraryItems = MutableStateFlow(emptyList()),
            listTabs = MutableStateFlow(
                listOf(
                    LibraryListTab(
                        key = "watchlist",
                        title = "Watchlist",
                        type = LibraryListTab.Type.WATCHLIST
                    )
                )
            )
        )
        val viewModel = LibraryViewModel(
            libraryRepository = repository,
            layoutPreferenceDataStore = layoutPreferenceDataStore()
        )

        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isLoading)
        assertTrue(viewModel.uiState.value.isSyncing)
        assertEquals(0, repository.refreshTraktNowCalls)
    }

    @Test
    fun `failed first trakt sync exits blocking load and surfaces retryable error`() = runTest(dispatcher) {
        val repository = FakeLibraryRepository(
            sourceMode = MutableStateFlow(LibrarySourceMode.TRAKT),
            isSyncing = MutableStateFlow(false),
            hasTraktCache = MutableStateFlow(false),
            libraryItems = MutableStateFlow(emptyList()),
            listTabs = MutableStateFlow(emptyList()),
            refreshTraktNowBlock = { throw IllegalStateException("Trakt unavailable") }
        )
        val viewModel = LibraryViewModel(
            libraryRepository = repository,
            layoutPreferenceDataStore = layoutPreferenceDataStore()
        )

        advanceUntilIdle()

        assertEquals(1, repository.refreshTraktNowCalls)
        assertFalse(viewModel.uiState.value.isLoading)
        assertEquals("Trakt unavailable", viewModel.uiState.value.errorMessage)
    }

    private fun layoutPreferenceDataStore(): LayoutPreferenceDataStore {
        val store = mockk<LayoutPreferenceDataStore>()
        every { store.posterCardWidthDp } returns flowOf(126)
        every { store.posterCardCornerRadiusDp } returns flowOf(12)
        return store
    }

    private class FakeLibraryRepository(
        override val sourceMode: MutableStateFlow<LibrarySourceMode>,
        override val isSyncing: MutableStateFlow<Boolean>,
        override val hasTraktCache: MutableStateFlow<Boolean>,
        override val libraryItems: MutableStateFlow<List<LibraryEntry>>,
        override val listTabs: MutableStateFlow<List<LibraryListTab>>,
        private val refreshTraktNowBlock: suspend () -> Unit = {}
    ) : LibraryRepository {
        var refreshTraktNowCalls: Int = 0

        override fun isInLibrary(itemId: String, itemType: String): Flow<Boolean> = flowOf(false)

        override fun isInWatchlist(itemId: String, itemType: String): Flow<Boolean> = flowOf(false)

        override suspend fun toggleDefault(item: LibraryEntryInput) = Unit

        override suspend fun getMembershipSnapshot(item: LibraryEntryInput): ListMembershipSnapshot {
            return ListMembershipSnapshot()
        }

        override suspend fun applyMembershipChanges(item: LibraryEntryInput, changes: ListMembershipChanges) = Unit

        override suspend fun createPersonalList(name: String, description: String?, privacy: TraktListPrivacy) = Unit

        override suspend fun updatePersonalList(
            listId: String,
            name: String,
            description: String?,
            privacy: TraktListPrivacy
        ) = Unit

        override suspend fun deletePersonalList(listId: String) = Unit

        override suspend fun reorderPersonalLists(orderedListIds: List<String>) = Unit

        override suspend fun refreshNow() = Unit

        override suspend fun refreshTraktNow() {
            refreshTraktNowCalls += 1
            refreshTraktNowBlock()
        }

        override suspend fun refreshDebridNow() = Unit

        override suspend fun refreshRealDebridNow() = Unit

        override suspend fun refreshPremiumizeNow() = Unit

        override suspend fun refreshTorBoxNow() = Unit
    }
}
