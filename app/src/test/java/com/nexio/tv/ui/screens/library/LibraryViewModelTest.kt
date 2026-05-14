package com.nexio.tv.ui.screens.library

import com.nexio.tv.data.local.LayoutPreferenceDataStore
import com.nexio.tv.core.profile.ProfileManager
import com.nexio.tv.data.repository.TorBoxDirectPlayHandler
import com.nexio.tv.data.repository.UnifiedWatchlistResolvedDisplayProjector
import com.nexio.tv.data.repository.UnifiedWatchlistSurfacePublisher
import com.nexio.tv.core.integration.ActiveProfileSession
import com.nexio.tv.domain.model.LibraryEntry
import com.nexio.tv.domain.model.LibraryEntryInput
import com.nexio.tv.domain.model.LibraryListTab
import com.nexio.tv.domain.model.LibraryListManagementMode
import com.nexio.tv.domain.model.LibraryProviderOption
import com.nexio.tv.domain.model.LibraryProviderSelection
import com.nexio.tv.domain.model.LibraryProviderSnapshot
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
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
    fun `library opens with unified selected provider`() = runTest(dispatcher) {
        val repository = FakeLibraryRepository(
            sourceMode = MutableStateFlow(LibrarySourceMode.LOCAL),
            isSyncing = MutableStateFlow(false),
            hasProviderCache = MutableStateFlow(true),
            libraryItems = MutableStateFlow(emptyList()),
            listTabs = MutableStateFlow(emptyList())
        )
        val viewModel = viewModel(repository)

        advanceUntilIdle()

        assertEquals(LibraryProviderSelection.UNIFIED, viewModel.uiState.value.selectedProvider)
        assertEquals(
            listOf(LibraryProviderOption(LibraryProviderSelection.UNIFIED)),
            viewModel.uiState.value.availableProviders
        )
        assertEquals(emptyList<Any>(), viewModel.unifiedWatchlistRows.value)
    }

    @Test
    fun `selected provider falls back to unified when option disappears`() = runTest(dispatcher) {
        val repository = FakeLibraryRepository(
            sourceMode = MutableStateFlow(LibrarySourceMode.TRAKT),
            isSyncing = MutableStateFlow(false),
            hasProviderCache = MutableStateFlow(true),
            libraryItems = MutableStateFlow(emptyList()),
            listTabs = MutableStateFlow(emptyList()),
            availableProviders = MutableStateFlow(
                listOf(
                    LibraryProviderOption(LibraryProviderSelection.UNIFIED),
                    LibraryProviderOption(LibraryProviderSelection.TRAKT)
                )
            )
        )
        val viewModel = viewModel(repository)

        advanceUntilIdle()
        viewModel.onSelectProvider(LibraryProviderSelection.TRAKT)
        advanceUntilIdle()

        assertEquals(LibraryProviderSelection.TRAKT, viewModel.uiState.value.selectedProvider)

        repository.availableProviders.value = listOf(LibraryProviderOption(LibraryProviderSelection.UNIFIED))
        advanceUntilIdle()

        assertEquals(LibraryProviderSelection.UNIFIED, viewModel.uiState.value.selectedProvider)
        assertEquals(null, viewModel.uiState.value.selectedListKey)
    }

    @Test
    fun `first uncached trakt session auto-syncs and blocks until cache exists`() = runTest(dispatcher) {
        val repository = FakeLibraryRepository(
            sourceMode = MutableStateFlow(LibrarySourceMode.TRAKT),
            isSyncing = MutableStateFlow(false),
            hasProviderCache = MutableStateFlow(false),
            libraryItems = MutableStateFlow(emptyList()),
            listTabs = MutableStateFlow(emptyList())
        )
        val viewModel = viewModel(repository)

        advanceUntilIdle()

        assertEquals(1, repository.refreshProviderNowCalls)
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
        repository.hasProviderCache.value = true
        repository.isSyncing.value = false
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isLoading)
    }

    @Test
    fun `warm trakt cache never re-enters full-screen loading while syncing`() = runTest(dispatcher) {
        val repository = FakeLibraryRepository(
            sourceMode = MutableStateFlow(LibrarySourceMode.TRAKT),
            isSyncing = MutableStateFlow(true),
            hasProviderCache = MutableStateFlow(true),
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
        val viewModel = viewModel(repository)

        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isLoading)
        assertTrue(viewModel.uiState.value.isSyncing)
        assertEquals(0, repository.refreshProviderNowCalls)
    }

    @Test
    fun `failed first trakt sync exits blocking load and surfaces retryable error`() = runTest(dispatcher) {
        val repository = FakeLibraryRepository(
            sourceMode = MutableStateFlow(LibrarySourceMode.TRAKT),
            isSyncing = MutableStateFlow(false),
            hasProviderCache = MutableStateFlow(false),
            libraryItems = MutableStateFlow(emptyList()),
            listTabs = MutableStateFlow(emptyList()),
            refreshProviderNowBlock = { throw IllegalStateException("Trakt unavailable") }
        )
        val viewModel = viewModel(repository)

        advanceUntilIdle()

        assertEquals(1, repository.refreshProviderNowCalls)
        assertFalse(viewModel.uiState.value.isLoading)
        assertEquals("Trakt unavailable", viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `simkl watchlist refresh uses provider sync path`() = runTest(dispatcher) {
        val repository = FakeLibraryRepository(
            sourceMode = MutableStateFlow(LibrarySourceMode.SIMKL),
            isSyncing = MutableStateFlow(false),
            hasProviderCache = MutableStateFlow(true),
            libraryItems = MutableStateFlow(emptyList()),
            listTabs = MutableStateFlow(
                listOf(
                    LibraryListTab(
                        key = "simkl:plantowatch",
                        title = "SIMKL Watchlist",
                        type = LibraryListTab.Type.WATCHLIST
                    )
                )
            ),
            availableProviders = MutableStateFlow(
                listOf(
                    LibraryProviderOption(LibraryProviderSelection.UNIFIED),
                    LibraryProviderOption(LibraryProviderSelection.SIMKL)
                )
            )
        )
        val viewModel = viewModel(repository)

        advanceUntilIdle()
        viewModel.onSelectProvider(LibraryProviderSelection.SIMKL)
        advanceUntilIdle()
        viewModel.onSelectListTab("simkl:plantowatch")
        viewModel.onRefresh()
        advanceUntilIdle()

        assertEquals(listOf(LibraryProviderSelection.SIMKL), repository.refreshProviderCalls)
        assertEquals(null, viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `debrid provider refresh routes by selected provider`() = runTest(dispatcher) {
        val repository = FakeLibraryRepository(
            sourceMode = MutableStateFlow(LibrarySourceMode.DEBRID),
            isSyncing = MutableStateFlow(false),
            hasProviderCache = MutableStateFlow(true),
            libraryItems = MutableStateFlow(emptyList()),
            listTabs = MutableStateFlow(emptyList()),
            availableProviders = MutableStateFlow(
                listOf(
                    LibraryProviderOption(LibraryProviderSelection.UNIFIED),
                    LibraryProviderOption(LibraryProviderSelection.REAL_DEBRID)
                )
            )
        )
        val viewModel = viewModel(repository)

        advanceUntilIdle()
        viewModel.onSelectProvider(LibraryProviderSelection.REAL_DEBRID)
        viewModel.onRefresh()
        advanceUntilIdle()

        assertEquals(listOf(LibraryProviderSelection.REAL_DEBRID), repository.refreshProviderCalls)
        assertEquals(0, repository.refreshProviderNowCalls)
    }

    @Test
    fun `mdblist dynamic list cannot open mutable list editor`() = runTest(dispatcher) {
        val repository = FakeLibraryRepository(
            sourceMode = MutableStateFlow(LibrarySourceMode.TRAKT),
            isSyncing = MutableStateFlow(false),
            hasProviderCache = MutableStateFlow(true),
            libraryItems = MutableStateFlow(emptyList()),
            listTabs = MutableStateFlow(emptyList()),
            availableProviders = MutableStateFlow(
                listOf(
                    LibraryProviderOption(LibraryProviderSelection.UNIFIED),
                    LibraryProviderOption(LibraryProviderSelection.MDBLIST)
                )
            ),
            providerSnapshot = MutableStateFlow(
                LibraryProviderSnapshot(
                    provider = LibraryProviderSelection.MDBLIST,
                    sourceMode = LibrarySourceMode.TRAKT,
                    listTabs = listOf(
                        LibraryListTab(
                            key = "mdblist:list:11",
                            title = "Trending",
                            type = LibraryListTab.Type.PERSONAL,
                            mdbListId = 11,
                            mdbListType = "dynamic",
                            isMutableStaticList = false
                        )
                    ),
                    selectedListKey = "mdblist:list:11",
                    supportsLists = true,
                    supportsListManagement = true,
                    listManagementMode = LibraryListManagementMode.MDBLIST_STATIC,
                    listSelectorLabel = "Trending"
                )
            )
        )
        val viewModel = viewModel(repository)

        advanceUntilIdle()
        viewModel.onOpenManageLists()

        assertFalse(viewModel.uiState.value.showManageDialog)
    }

    @Test
    fun `mdblist static list edit uses mdblist provider route`() = runTest(dispatcher) {
        val repository = FakeLibraryRepository(
            sourceMode = MutableStateFlow(LibrarySourceMode.TRAKT),
            isSyncing = MutableStateFlow(false),
            hasProviderCache = MutableStateFlow(true),
            libraryItems = MutableStateFlow(emptyList()),
            listTabs = MutableStateFlow(emptyList()),
            availableProviders = MutableStateFlow(listOf(LibraryProviderOption(LibraryProviderSelection.MDBLIST))),
            providerSnapshot = MutableStateFlow(
                LibraryProviderSnapshot(
                    provider = LibraryProviderSelection.MDBLIST,
                    sourceMode = LibrarySourceMode.TRAKT,
                    listTabs = listOf(
                        LibraryListTab(
                            key = "mdblist:list:10",
                            title = "Sci-Fi",
                            type = LibraryListTab.Type.PERSONAL,
                            mdbListId = 10,
                            mdbListType = "static",
                            isMutableStaticList = true
                        )
                    ),
                    selectedListKey = "mdblist:list:10",
                    supportsLists = true,
                    supportsListManagement = true,
                    listManagementMode = LibraryListManagementMode.MDBLIST_STATIC,
                    listSelectorLabel = "Sci-Fi"
                )
            )
        )
        val viewModel = viewModel(repository)

        advanceUntilIdle()
        viewModel.onOpenManageLists()
        viewModel.onStartEditList()
        viewModel.onUpdateEditorName("Sci-Fi Updated")
        viewModel.onSubmitEditor()
        advanceUntilIdle()

        assertEquals(listOf(LibraryProviderSelection.MDBLIST to "10"), repository.updatedLists)
    }

    private fun viewModel(repository: FakeLibraryRepository): LibraryViewModel {
        return LibraryViewModel(
            libraryRepository = repository,
            layoutPreferenceDataStore = layoutPreferenceDataStore(),
            torBoxDirectPlayHandler = mockk(relaxed = true),
            unifiedWatchlistResolvedDisplayProjector = unifiedWatchlistProjector(),
            unifiedWatchlistSurfacePublisher = unifiedWatchlistSurfacePublisher(),
            profileManager = profileManager(),
        )
    }

    private fun layoutPreferenceDataStore(): LayoutPreferenceDataStore {
        val store = mockk<LayoutPreferenceDataStore>()
        every { store.posterCardWidthDp } returns flowOf(126)
        every { store.posterCardCornerRadiusDp } returns flowOf(12)
        return store
    }

    private fun unifiedWatchlistProjector(): UnifiedWatchlistResolvedDisplayProjector {
        val projector = mockk<UnifiedWatchlistResolvedDisplayProjector>()
        every {
            projector.observeRows(any(), any<Flow<List<UnifiedWatchlistMembership>>>())
        } returns flowOf(emptyList())
        return projector
    }

    private fun unifiedWatchlistSurfacePublisher(): UnifiedWatchlistSurfacePublisher {
        val publisher = mockk<UnifiedWatchlistSurfacePublisher>()
        coEvery { publisher.publish(any(), any()) } returns true
        return publisher
    }

    private fun profileManager(): ProfileManager {
        val manager = mockk<ProfileManager>()
        every { manager.activeProfileId } returns MutableStateFlow(1)
        every { manager.activeProfileSession } returns MutableStateFlow(
            ActiveProfileSession(
                profileId = 1,
                sessionId = "test-session",
                sessionOrdinal = 1L,
                startedAtMs = 1L
            )
        )
        return manager
    }

    private class FakeLibraryRepository(
        override val sourceMode: MutableStateFlow<LibrarySourceMode>,
        override val isSyncing: MutableStateFlow<Boolean>,
        override val hasProviderCache: MutableStateFlow<Boolean>,
        override val libraryItems: MutableStateFlow<List<LibraryEntry>>,
        override val listTabs: MutableStateFlow<List<LibraryListTab>>,
        override val availableProviders: MutableStateFlow<List<LibraryProviderOption>> =
            MutableStateFlow(listOf(LibraryProviderOption(LibraryProviderSelection.UNIFIED))),
        override val unifiedWatchlistMemberships: MutableStateFlow<List<UnifiedWatchlistMembership>> = MutableStateFlow(emptyList()),
        private val providerSnapshot: MutableStateFlow<LibraryProviderSnapshot>? = null,
        private val refreshProviderNowBlock: suspend () -> Unit = {}
    ) : LibraryRepository {
        var refreshProviderNowCalls: Int = 0
        val refreshProviderCalls = mutableListOf<LibraryProviderSelection>()
        val updatedLists = mutableListOf<Pair<LibraryProviderSelection, String>>()

        override fun observeProviderSnapshot(
            provider: LibraryProviderSelection,
            selectedListKey: String?
        ): Flow<LibraryProviderSnapshot> {
            val explicitSnapshot = providerSnapshot
            if (explicitSnapshot != null) return explicitSnapshot
            return combine(sourceMode, libraryItems, listTabs) { sourceMode, items, tabs ->
                val nextSelectedList = selectedListKey
                    ?.takeIf { key -> tabs.any { it.key == key } }
                    ?: tabs.firstOrNull()?.key
                LibraryProviderSnapshot(
                    provider = provider,
                    sourceMode = sourceMode,
                    items = items,
                    listTabs = tabs,
                    selectedListKey = nextSelectedList,
                    supportsLists = tabs.isNotEmpty(),
                    listSelectorLabel = tabs.firstOrNull { it.key == nextSelectedList }?.title ?: "N/A"
                )
            }
        }

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

        override suspend fun refreshProviderNow() {
            refreshProviderNowCalls += 1
            refreshProviderNowBlock()
        }

        override suspend fun refreshDebridNow() = Unit

        override suspend fun refreshRealDebridNow() = Unit

        override suspend fun refreshPremiumizeNow() = Unit

        override suspend fun refreshTorBoxNow() = Unit

        override suspend fun refreshEasyDebridNow() = Unit

        override suspend fun refreshProviderNow(provider: LibraryProviderSelection, selectedListKey: String?) {
            refreshProviderCalls += provider
        }

        override suspend fun createProviderList(
            provider: LibraryProviderSelection,
            name: String,
            description: String?,
            privacy: TraktListPrivacy
        ) = Unit

        override suspend fun updateProviderList(
            provider: LibraryProviderSelection,
            listId: String,
            name: String,
            description: String?,
            privacy: TraktListPrivacy
        ) {
            updatedLists += provider to listId
        }

        override suspend fun deleteProviderList(provider: LibraryProviderSelection, listId: String) = Unit
    }
}
