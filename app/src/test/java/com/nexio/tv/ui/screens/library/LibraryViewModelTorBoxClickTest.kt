package com.nexio.tv.ui.screens.library

import com.nexio.tv.data.local.LayoutPreferenceDataStore
import com.nexio.tv.data.repository.DebridLibraryService
import com.nexio.tv.data.repository.TorBoxDirectPlayHandler
import com.nexio.tv.data.repository.TorBoxResolvedPlayback
import com.nexio.tv.domain.model.LibraryEntry
import com.nexio.tv.domain.model.LibraryEntryInput
import com.nexio.tv.domain.model.LibraryListTab
import com.nexio.tv.domain.model.LibrarySourceMode
import com.nexio.tv.domain.model.ListMembershipChanges
import com.nexio.tv.domain.model.ListMembershipSnapshot
import com.nexio.tv.domain.model.TraktListPrivacy
import com.nexio.tv.domain.model.UnifiedWatchlistMembership
import com.nexio.tv.domain.repository.LibraryRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModelTorBoxClickTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun layoutPrefs(): LayoutPreferenceDataStore {
        val store = mockk<LayoutPreferenceDataStore>()
        every { store.posterCardWidthDp } returns flowOf(126)
        every { store.posterCardCornerRadiusDp } returns flowOf(12)
        return store
    }

    private fun fakeRepository(): EmptyLibraryRepository = EmptyLibraryRepository()

    private fun torBoxEntry(): LibraryEntry = LibraryEntry(
        id = "tb:torrent:7:file:10",
        type = "movie",
        name = "movie",
        poster = null,
        background = null,
        logo = null,
        description = null,
        releaseInfo = null,
        imdbRating = null,
        genres = emptyList(),
        addonBaseUrl = null,
        listKeys = setOf(DebridLibraryService.TORBOX_LIST_KEY),
        playbackFilename = "movie.mkv",
    )

    @Test
    fun `onTorBoxItemClick emits Resolving then Navigate when handler resolves`() = runTest(dispatcher) {
        val handler = mockk<TorBoxDirectPlayHandler>()
        coEvery { handler.resolve(7, 10, "movie.mkv") } returns TorBoxResolvedPlayback.Resolved(
            url = "https://torbox.example/stream.mkv",
            torrentId = 7, fileId = 10, fileName = "movie.mkv",
            resumePositionMs = 0L,
        )
        val viewModel = LibraryViewModel(
            libraryRepository = fakeRepository(),
            layoutPreferenceDataStore = layoutPrefs(),
            torBoxDirectPlayHandler = handler,
        )
        val emitted = mutableListOf<DirectPlayCommand>()
        val collector: Job = launch { viewModel.directPlayCommands.collect { emitted += it } }

        viewModel.onTorBoxItemClick(torBoxEntry())
        advanceUntilIdle()
        collector.cancel()

        assertEquals(2, emitted.size)
        assertTrue(emitted[0] is DirectPlayCommand.Resolving)
        assertEquals("movie.mkv", (emitted[0] as DirectPlayCommand.Resolving).fileName)
        assertTrue(emitted[1] is DirectPlayCommand.Navigate)
        val nav = emitted[1] as DirectPlayCommand.Navigate
        assertEquals("https://torbox.example/stream.mkv", nav.url)
        assertEquals(true, nav.deterministicAutoplay)
        assertEquals(7, nav.torBoxTorrentId)
        assertEquals(10, nav.torBoxFileId)
    }

    @Test
    fun `onTorBoxItemClick emits Failed on handler failure and never Navigate`() = runTest(dispatcher) {
        val handler = mockk<TorBoxDirectPlayHandler>()
        coEvery { handler.resolve(7, 10, "movie.mkv") } returns
            TorBoxResolvedPlayback.Failed("TorBox returned no playback link.")
        val viewModel = LibraryViewModel(
            libraryRepository = fakeRepository(),
            layoutPreferenceDataStore = layoutPrefs(),
            torBoxDirectPlayHandler = handler,
        )
        val emitted = mutableListOf<DirectPlayCommand>()
        val collector: Job = launch { viewModel.directPlayCommands.collect { emitted += it } }

        viewModel.onTorBoxItemClick(torBoxEntry())
        advanceUntilIdle()
        collector.cancel()

        assertEquals(2, emitted.size)
        assertTrue(emitted[0] is DirectPlayCommand.Resolving)
        val failed = emitted[1]
        assertTrue(failed is DirectPlayCommand.Failed)
        assertEquals("TorBox returned no playback link.", (failed as DirectPlayCommand.Failed).message)
        assertTrue(emitted.none { it is DirectPlayCommand.Navigate })
    }

    /** Library repository that returns empty flows; the click handler does not consult the repo. */
    private class EmptyLibraryRepository : LibraryRepository {
        override val sourceMode: Flow<LibrarySourceMode> = MutableStateFlow(LibrarySourceMode.LOCAL)
        override val isSyncing: Flow<Boolean> = MutableStateFlow(false)
        override val hasProviderCache: Flow<Boolean> = MutableStateFlow(true)
        override val libraryItems: Flow<List<LibraryEntry>> = MutableStateFlow(emptyList())
        override val listTabs: Flow<List<LibraryListTab>> = MutableStateFlow(emptyList())
        override val unifiedWatchlistMemberships: Flow<List<UnifiedWatchlistMembership>> = flowOf(emptyList())
        override fun isInLibrary(itemId: String, itemType: String): Flow<Boolean> = flowOf(false)
        override fun isInWatchlist(itemId: String, itemType: String): Flow<Boolean> = flowOf(false)
        override suspend fun toggleDefault(item: LibraryEntryInput) {}
        override suspend fun getMembershipSnapshot(item: LibraryEntryInput): ListMembershipSnapshot = ListMembershipSnapshot()
        override suspend fun applyMembershipChanges(item: LibraryEntryInput, changes: ListMembershipChanges) {}
        override suspend fun createPersonalList(name: String, description: String?, privacy: TraktListPrivacy) {}
        override suspend fun updatePersonalList(listId: String, name: String, description: String?, privacy: TraktListPrivacy) {}
        override suspend fun deletePersonalList(listId: String) {}
        override suspend fun reorderPersonalLists(orderedListIds: List<String>) {}
        override suspend fun refreshNow() {}
        override suspend fun refreshProviderNow() {}
        override suspend fun refreshDebridNow() {}
        override suspend fun refreshRealDebridNow() {}
        override suspend fun refreshPremiumizeNow() {}
        override suspend fun refreshTorBoxNow() {}
    }
}
