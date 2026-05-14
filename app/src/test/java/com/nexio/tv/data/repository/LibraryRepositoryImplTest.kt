package com.nexio.tv.data.repository

import com.nexio.tv.domain.model.TrackingProvider
import com.nexio.tv.domain.model.LibraryEntry
import com.nexio.tv.domain.model.LibraryEntryInput
import com.nexio.tv.domain.model.LibraryListTab
import com.nexio.tv.domain.model.LibraryProviderSelection
import com.nexio.tv.domain.model.LibrarySourceMode
import com.nexio.tv.domain.model.ListMembershipChanges
import com.nexio.tv.domain.model.PosterShape
import io.mockk.every
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryRepositoryImplTest {
    @Test
    fun `available providers include unified authenticated trackers and configured debrid providers`() = runTest {
        val fixture = repositoryFixture(
            providerState = EffectiveTrackingProviderState(
                effectiveProvider = TrackingProvider.TRAKT,
                traktAuthenticated = true,
                simklAuthenticated = true,
                mdbListAuthenticated = true
            ),
            debridTabs = listOf(
                LibraryListTab(DebridLibraryService.REAL_DEBRID_LIST_KEY, "Real-Debrid", LibraryListTab.Type.SERVICE),
                LibraryListTab(DebridLibraryService.PREMIUMIZE_LIST_KEY, "Premiumize", LibraryListTab.Type.SERVICE),
                LibraryListTab(DebridLibraryService.TORBOX_LIST_KEY, "TorBox", LibraryListTab.Type.SERVICE),
                LibraryListTab(DebridLibraryService.EASY_DEBRID_LIST_KEY, "EasyDebrid", LibraryListTab.Type.SERVICE)
            )
        )

        val providers = fixture.repository.availableProviders.first().map { it.provider }

        assertEquals(
            listOf(
                LibraryProviderSelection.UNIFIED,
                LibraryProviderSelection.TRAKT,
                LibraryProviderSelection.SIMKL,
                LibraryProviderSelection.MDBLIST,
                LibraryProviderSelection.REAL_DEBRID,
                LibraryProviderSelection.PREMIUMIZE,
                LibraryProviderSelection.TORBOX,
                LibraryProviderSelection.EASY_DEBRID
            ),
            providers
        )
    }

    @Test
    fun `trakt provider snapshot excludes debrid tabs and items`() = runTest {
        val traktItem = libraryEntry(id = "trakt:1", listKeys = setOf(TraktLibraryService.WATCHLIST_KEY))
        val debridItem = libraryEntry(id = "rd:1", listKeys = setOf(DebridLibraryService.REAL_DEBRID_LIST_KEY))
        val fixture = repositoryFixture(
            providerState = EffectiveTrackingProviderState(
                effectiveProvider = TrackingProvider.TRAKT,
                traktAuthenticated = true,
                simklAuthenticated = false
            ),
            traktItems = listOf(traktItem),
            debridItems = listOf(debridItem),
            traktTabs = listOf(LibraryListTab(TraktLibraryService.WATCHLIST_KEY, "Watchlist", LibraryListTab.Type.WATCHLIST)),
            debridTabs = listOf(LibraryListTab(DebridLibraryService.REAL_DEBRID_LIST_KEY, "Real-Debrid", LibraryListTab.Type.SERVICE))
        )

        val snapshot = fixture.repository.observeProviderSnapshot(
            provider = LibraryProviderSelection.TRAKT,
            selectedListKey = TraktLibraryService.WATCHLIST_KEY
        ).first()

        assertEquals(LibraryProviderSelection.TRAKT, snapshot.provider)
        assertEquals(listOf(traktItem), snapshot.items)
        assertEquals(listOf("Watchlist"), snapshot.listTabs.map { it.title })
    }

    @Test
    fun `debrid provider snapshot filters to selected provider and disables lists`() = runTest {
        val rdItem = libraryEntry(id = "rd:1", listKeys = setOf(DebridLibraryService.REAL_DEBRID_LIST_KEY))
        val torBoxItem = libraryEntry(id = "tb:1", listKeys = setOf(DebridLibraryService.TORBOX_LIST_KEY))
        val fixture = repositoryFixture(
            debridItems = listOf(rdItem, torBoxItem),
            debridTabs = listOf(
                LibraryListTab(DebridLibraryService.REAL_DEBRID_LIST_KEY, "Real-Debrid", LibraryListTab.Type.SERVICE),
                LibraryListTab(DebridLibraryService.TORBOX_LIST_KEY, "TorBox", LibraryListTab.Type.SERVICE)
            )
        )

        val snapshot = fixture.repository.observeProviderSnapshot(
            provider = LibraryProviderSelection.TORBOX,
            selectedListKey = null
        ).first()

        assertEquals(listOf(torBoxItem), snapshot.items)
        assertEquals(emptyList<LibraryListTab>(), snapshot.listTabs)
        assertFalse(snapshot.supportsLists)
        assertEquals("N/A", snapshot.listSelectorLabel)
    }

    @Test
    fun `restored trakt cache is surfaced before auth flow settles`() = runTest {
        val traktAuthState = MutableStateFlow(false)
        val trackingProviderStateService = mockk<TrackingProviderStateService>()
        val traktLibraryService = mockk<TraktLibraryService>()
        val simklLibraryService = mockk<SimklLibraryService>()
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

        every { trackingProviderStateService.state } returns traktAuthState.map {
            EffectiveTrackingProviderState(
                effectiveProvider = TrackingProvider.TRAKT,
                traktAuthenticated = it,
                simklAuthenticated = false
            )
        }
        coEvery { trackingProviderStateService.currentState() } answers {
            EffectiveTrackingProviderState(
                effectiveProvider = TrackingProvider.TRAKT,
                traktAuthenticated = traktAuthState.value,
                simklAuthenticated = false
            )
        }
        every { traktLibraryService.observeHasCache() } returns flowOf(true)
        every { traktLibraryService.observeAllItems() } returns flowOf(traktItems)
        every { traktLibraryService.observeListTabs() } returns flowOf(traktTabs)
        every { traktLibraryService.observeIsRefreshing() } returns flowOf(false)
        every { simklLibraryService.observeHasCache() } returns flowOf(false)
        every { simklLibraryService.observeAllItems() } returns flowOf(emptyList())
        every { simklLibraryService.observeListTabs() } returns flowOf(emptyList())
        every { simklLibraryService.observeIsRefreshing() } returns flowOf(false)
        every { debridLibraryService.observeIsConnected() } returns flowOf(false)
        every { debridLibraryService.observeIsRefreshing() } returns flowOf(false)
        every { debridLibraryService.observeItems() } returns flowOf(emptyList())
        every { debridLibraryService.observeListTabs() } returns flowOf(emptyList())

        val repository = LibraryRepositoryImpl(
            trackingProviderStateService = trackingProviderStateService,
            traktLibraryService = traktLibraryService,
            simklLibraryService = simklLibraryService,
            mdbListLibraryService = mockk<MDBListLibraryService>(relaxed = true),
            debridLibraryService = debridLibraryService
        )

        assertTrue(repository.hasProviderCache.first())
        assertEquals(LibrarySourceMode.TRAKT, repository.sourceMode.first())
        assertEquals(traktTabs, repository.listTabs.first())
        assertEquals(traktItems, repository.libraryItems.first())
    }

    @Test
    fun `debrid startup refresh cannot block restored trakt cache emission`() = runTest {
        val traktAuthState = MutableStateFlow(false)
        val trackingProviderStateService = mockk<TrackingProviderStateService>()
        val traktLibraryService = mockk<TraktLibraryService>()
        val simklLibraryService = mockk<SimklLibraryService>()
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
                id = "tt7654321",
                type = "movie",
                name = "Warm Cache Movie",
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
        val blockedListTabs = flow<List<LibraryListTab>> { awaitCancellation() }
        val blockedItems = flow<List<LibraryEntry>> { awaitCancellation() }

        every { trackingProviderStateService.state } returns traktAuthState.map {
            EffectiveTrackingProviderState(
                effectiveProvider = TrackingProvider.TRAKT,
                traktAuthenticated = it,
                simklAuthenticated = false
            )
        }
        coEvery { trackingProviderStateService.currentState() } answers {
            EffectiveTrackingProviderState(
                effectiveProvider = TrackingProvider.TRAKT,
                traktAuthenticated = traktAuthState.value,
                simklAuthenticated = false
            )
        }
        every { traktLibraryService.observeHasCache() } returns flowOf(true)
        every { traktLibraryService.observeAllItems() } returns flowOf(traktItems)
        every { traktLibraryService.observeListTabs() } returns flowOf(traktTabs)
        every { traktLibraryService.observeIsRefreshing() } returns flowOf(false)
        every { simklLibraryService.observeHasCache() } returns flowOf(false)
        every { simklLibraryService.observeAllItems() } returns flowOf(emptyList())
        every { simklLibraryService.observeListTabs() } returns flowOf(emptyList())
        every { simklLibraryService.observeIsRefreshing() } returns flowOf(false)
        every { debridLibraryService.observeIsConnected() } returns flowOf(false)
        every { debridLibraryService.observeIsRefreshing() } returns flowOf(false)
        every { debridLibraryService.observeItems() } returns blockedItems
        every { debridLibraryService.observeListTabs() } returns blockedListTabs

        val repository = LibraryRepositoryImpl(
            trackingProviderStateService = trackingProviderStateService,
            traktLibraryService = traktLibraryService,
            simklLibraryService = simklLibraryService,
            mdbListLibraryService = mockk<MDBListLibraryService>(relaxed = true),
            debridLibraryService = debridLibraryService
        )

        assertEquals(traktTabs, withTimeout(100) { repository.listTabs.first() })
        assertEquals(traktItems, withTimeout(100) { repository.libraryItems.first() })
    }

    @Test
    fun `simkl provider surfaces simkl cache and watchlist tabs`() = runTest {
        val providerState = MutableStateFlow(
            EffectiveTrackingProviderState(
                effectiveProvider = TrackingProvider.SIMKL,
                traktAuthenticated = false,
                simklAuthenticated = true
            )
        )
        val trackingProviderStateService = mockk<TrackingProviderStateService>()
        val traktLibraryService = mockk<TraktLibraryService>()
        val simklLibraryService = mockk<SimklLibraryService>()
        val debridLibraryService = mockk<DebridLibraryService>()

        val simklTabs = listOf(
            LibraryListTab(
                key = SimklLibraryService.WATCHLIST_KEY,
                title = "SIMKL Watchlist",
                type = LibraryListTab.Type.WATCHLIST
            )
        )
        val simklItems = listOf(
            LibraryEntry(
                id = "tt1375666",
                type = "movie",
                name = "Inception",
                poster = null,
                posterShape = PosterShape.POSTER,
                background = null,
                logo = null,
                description = null,
                releaseInfo = null,
                imdbRating = null,
                genres = emptyList(),
                addonBaseUrl = null,
                listKeys = setOf(SimklLibraryService.WATCHLIST_KEY)
            )
        )

        every { trackingProviderStateService.state } returns providerState
        coEvery { trackingProviderStateService.currentState() } answers { providerState.value }
        every { traktLibraryService.observeHasCache() } returns flowOf(false)
        every { traktLibraryService.observeAllItems() } returns flowOf(emptyList())
        every { traktLibraryService.observeListTabs() } returns flowOf(emptyList())
        every { traktLibraryService.observeIsRefreshing() } returns flowOf(false)
        every { simklLibraryService.observeHasCache() } returns flowOf(true)
        every { simklLibraryService.observeAllItems() } returns flowOf(simklItems)
        every { simklLibraryService.observeListTabs() } returns flowOf(simklTabs)
        every { simklLibraryService.observeIsRefreshing() } returns flowOf(false)
        every { debridLibraryService.observeIsConnected() } returns flowOf(false)
        every { debridLibraryService.observeIsRefreshing() } returns flowOf(false)
        every { debridLibraryService.observeItems() } returns flowOf(emptyList())
        every { debridLibraryService.observeListTabs() } returns flowOf(emptyList())

        val repository = LibraryRepositoryImpl(
            trackingProviderStateService = trackingProviderStateService,
            traktLibraryService = traktLibraryService,
            simklLibraryService = simklLibraryService,
            mdbListLibraryService = mockk<MDBListLibraryService>(relaxed = true),
            debridLibraryService = debridLibraryService
        )

        assertTrue(repository.hasProviderCache.first())
        assertEquals(LibrarySourceMode.SIMKL, repository.sourceMode.first())
        assertEquals(simklTabs, repository.listTabs.first())
        assertEquals(simklItems, repository.libraryItems.first())
    }

    @Test
    fun `toggleDefault routes to simkl service when simkl provider is active`() = runTest {
        val providerState = EffectiveTrackingProviderState(
            effectiveProvider = TrackingProvider.SIMKL,
            traktAuthenticated = false,
            simklAuthenticated = true
        )
        val trackingProviderStateService = mockk<TrackingProviderStateService>()
        val traktLibraryService = mockk<TraktLibraryService>(relaxed = true)
        val simklLibraryService = mockk<SimklLibraryService>(relaxed = true)
        val debridLibraryService = mockk<DebridLibraryService>(relaxed = true)

        every { trackingProviderStateService.state } returns flowOf(providerState)
        coEvery { trackingProviderStateService.currentState() } returns providerState
        every { traktLibraryService.observeHasCache() } returns flowOf(false)
        every { simklLibraryService.observeHasCache() } returns flowOf(true)
        every { debridLibraryService.observeIsConnected() } returns flowOf(false)
        every { traktLibraryService.observeIsRefreshing() } returns flowOf(false)
        every { simklLibraryService.observeIsRefreshing() } returns flowOf(false)
        every { debridLibraryService.observeIsRefreshing() } returns flowOf(false)
        every { traktLibraryService.observeAllItems() } returns flowOf(emptyList())
        every { simklLibraryService.observeAllItems() } returns flowOf(emptyList())
        every { debridLibraryService.observeItems() } returns flowOf(emptyList())
        every { traktLibraryService.observeListTabs() } returns flowOf(emptyList())
        every { simklLibraryService.observeListTabs() } returns flowOf(emptyList())
        every { debridLibraryService.observeListTabs() } returns flowOf(emptyList())

        val repository = LibraryRepositoryImpl(
            trackingProviderStateService = trackingProviderStateService,
            traktLibraryService = traktLibraryService,
            simklLibraryService = simklLibraryService,
            mdbListLibraryService = mockk<MDBListLibraryService>(relaxed = true),
            debridLibraryService = debridLibraryService
        )

        repository.toggleDefault(sampleInput())

        coVerify(exactly = 1) { simklLibraryService.toggleWatchlist(any()) }
        coVerify(exactly = 0) { traktLibraryService.toggleWatchlist(any()) }
    }

    @Test
    fun `toggleDefault uses one route scoped provider state snapshot`() = runTest {
        val simklState = EffectiveTrackingProviderState(
            effectiveProvider = TrackingProvider.SIMKL,
            traktAuthenticated = false,
            simklAuthenticated = true
        )
        val traktState = EffectiveTrackingProviderState(
            effectiveProvider = TrackingProvider.TRAKT,
            traktAuthenticated = true,
            simklAuthenticated = false
        )
        val trackingProviderStateService = mockk<TrackingProviderStateService>()
        val traktLibraryService = mockk<TraktLibraryService>(relaxed = true)
        val simklLibraryService = mockk<SimklLibraryService>(relaxed = true)
        val debridLibraryService = mockk<DebridLibraryService>(relaxed = true)

        every { trackingProviderStateService.state } returns flowOf(simklState)
        coEvery { trackingProviderStateService.currentState() } returnsMany listOf(simklState, traktState)
        every { traktLibraryService.observeHasCache() } returns flowOf(false)
        every { simklLibraryService.observeHasCache() } returns flowOf(true)
        every { debridLibraryService.observeIsConnected() } returns flowOf(false)
        every { traktLibraryService.observeIsRefreshing() } returns flowOf(false)
        every { simklLibraryService.observeIsRefreshing() } returns flowOf(false)
        every { debridLibraryService.observeIsRefreshing() } returns flowOf(false)
        every { traktLibraryService.observeAllItems() } returns flowOf(emptyList())
        every { simklLibraryService.observeAllItems() } returns flowOf(emptyList())
        every { debridLibraryService.observeItems() } returns flowOf(emptyList())
        every { traktLibraryService.observeListTabs() } returns flowOf(emptyList())
        every { simklLibraryService.observeListTabs() } returns flowOf(emptyList())
        every { debridLibraryService.observeListTabs() } returns flowOf(emptyList())

        val repository = LibraryRepositoryImpl(
            trackingProviderStateService = trackingProviderStateService,
            traktLibraryService = traktLibraryService,
            simklLibraryService = simklLibraryService,
            mdbListLibraryService = mockk<MDBListLibraryService>(relaxed = true),
            debridLibraryService = debridLibraryService
        )

        repository.toggleDefault(sampleInput())

        coVerify(exactly = 1) { trackingProviderStateService.currentState() }
        coVerify(exactly = 1) { simklLibraryService.toggleWatchlist(any()) }
        coVerify(exactly = 0) { traktLibraryService.toggleWatchlist(any()) }
    }

    @Test
    fun `applyMembershipChanges routes to simkl service when simkl provider is active`() = runTest {
        val providerState = EffectiveTrackingProviderState(
            effectiveProvider = TrackingProvider.SIMKL,
            traktAuthenticated = false,
            simklAuthenticated = true
        )
        val trackingProviderStateService = mockk<TrackingProviderStateService>()
        val traktLibraryService = mockk<TraktLibraryService>(relaxed = true)
        val simklLibraryService = mockk<SimklLibraryService>(relaxed = true)
        val debridLibraryService = mockk<DebridLibraryService>(relaxed = true)

        every { trackingProviderStateService.state } returns flowOf(providerState)
        coEvery { trackingProviderStateService.currentState() } returns providerState
        every { traktLibraryService.observeHasCache() } returns flowOf(false)
        every { simklLibraryService.observeHasCache() } returns flowOf(true)
        every { debridLibraryService.observeIsConnected() } returns flowOf(false)
        every { traktLibraryService.observeIsRefreshing() } returns flowOf(false)
        every { simklLibraryService.observeIsRefreshing() } returns flowOf(false)
        every { debridLibraryService.observeIsRefreshing() } returns flowOf(false)
        every { traktLibraryService.observeAllItems() } returns flowOf(emptyList())
        every { simklLibraryService.observeAllItems() } returns flowOf(emptyList())
        every { debridLibraryService.observeItems() } returns flowOf(emptyList())
        every { traktLibraryService.observeListTabs() } returns flowOf(emptyList())
        every { simklLibraryService.observeListTabs() } returns flowOf(emptyList())
        every { debridLibraryService.observeListTabs() } returns flowOf(emptyList())

        val repository = LibraryRepositoryImpl(
            trackingProviderStateService = trackingProviderStateService,
            traktLibraryService = traktLibraryService,
            simklLibraryService = simklLibraryService,
            mdbListLibraryService = mockk<MDBListLibraryService>(relaxed = true),
            debridLibraryService = debridLibraryService
        )

        repository.applyMembershipChanges(
            item = sampleInput(),
            changes = ListMembershipChanges(mapOf(SimklLibraryService.WATCHLIST_KEY to true))
        )

        coVerify(exactly = 1) { simklLibraryService.applyMembershipChanges(any(), any()) }
        coVerify(exactly = 0) { traktLibraryService.applyMembershipChanges(any(), any()) }
    }

    private fun sampleInput(): LibraryEntryInput {
        return LibraryEntryInput(
            itemId = "tt1375666",
            itemType = "movie",
            title = "Inception",
            year = 2010
        )
    }

    private data class RepositoryFixture(val repository: LibraryRepositoryImpl)

    private fun repositoryFixture(
        providerState: EffectiveTrackingProviderState = EffectiveTrackingProviderState(
            effectiveProvider = TrackingProvider.TRAKT,
            traktAuthenticated = false,
            simklAuthenticated = false,
            mdbListAuthenticated = false
        ),
        traktItems: List<LibraryEntry> = emptyList(),
        simklItems: List<LibraryEntry> = emptyList(),
        mdbItems: List<LibraryEntry> = emptyList(),
        debridItems: List<LibraryEntry> = emptyList(),
        traktTabs: List<LibraryListTab> = emptyList(),
        simklTabs: List<LibraryListTab> = emptyList(),
        mdbTabs: List<LibraryListTab> = emptyList(),
        debridTabs: List<LibraryListTab> = emptyList()
    ): RepositoryFixture {
        val trackingProviderStateService = mockk<TrackingProviderStateService>()
        val traktLibraryService = mockk<TraktLibraryService>(relaxed = true)
        val simklLibraryService = mockk<SimklLibraryService>(relaxed = true)
        val mdbListLibraryService = mockk<MDBListLibraryService>(relaxed = true)
        val debridLibraryService = mockk<DebridLibraryService>(relaxed = true)
        every { trackingProviderStateService.state } returns flowOf(providerState)
        coEvery { trackingProviderStateService.currentState() } returns providerState
        every { traktLibraryService.observeHasCache() } returns flowOf(traktItems.isNotEmpty())
        every { simklLibraryService.observeHasCache() } returns flowOf(simklItems.isNotEmpty())
        every { traktLibraryService.observeAllItems() } returns flowOf(traktItems)
        every { simklLibraryService.observeAllItems() } returns flowOf(simklItems)
        every { mdbListLibraryService.observeAllItems() } returns flowOf(mdbItems)
        every { debridLibraryService.observeItems() } returns flowOf(debridItems)
        every { traktLibraryService.observeListTabs() } returns flowOf(traktTabs)
        every { simklLibraryService.observeListTabs() } returns flowOf(simklTabs)
        every { mdbListLibraryService.observeListTabs() } returns flowOf(mdbTabs)
        every { debridLibraryService.observeListTabs() } returns flowOf(debridTabs)
        every { traktLibraryService.observeIsRefreshing() } returns flowOf(false)
        every { simklLibraryService.observeIsRefreshing() } returns flowOf(false)
        every { mdbListLibraryService.observeIsRefreshing() } returns flowOf(false)
        every { debridLibraryService.observeIsRefreshing() } returns flowOf(false)
        every { debridLibraryService.observeIsConnected() } returns flowOf(debridTabs.isNotEmpty())
        return RepositoryFixture(
            LibraryRepositoryImpl(
                trackingProviderStateService = trackingProviderStateService,
                traktLibraryService = traktLibraryService,
                simklLibraryService = simklLibraryService,
                mdbListLibraryService = mdbListLibraryService,
                debridLibraryService = debridLibraryService
            )
        )
    }

    private fun libraryEntry(id: String, listKeys: Set<String>): LibraryEntry {
        return LibraryEntry(
            id = id,
            type = "movie",
            name = id,
            poster = null,
            background = null,
            logo = null,
            description = null,
            releaseInfo = null,
            imdbRating = null,
            genres = emptyList(),
            addonBaseUrl = null,
            listKeys = listKeys
        )
    }
}
