// effectiveProvider still routes library writes here (watchlist, collections, etc).
// Phase 3 of the scrobble + CW dual-provider plan migrates this file to fan-out
// across activeProviders. Suppression is provisional and tracks that migration.
// Plan: docs/superpowers/plans/2026-05-12-scrobble-cw-dual-provider-overhaul.md
@file:Suppress("DEPRECATION")

package com.nexio.tv.data.repository

import com.nexio.tv.data.integration.mdblist.MDBListLibraryService
import com.nexio.tv.domain.model.LibraryEntry
import com.nexio.tv.domain.model.LibraryEntryInput
import com.nexio.tv.domain.model.LibraryEmptyReason
import com.nexio.tv.domain.model.LibraryListTab
import com.nexio.tv.domain.model.LibraryListManagementMode
import com.nexio.tv.domain.model.LibraryProviderOption
import com.nexio.tv.domain.model.LibraryProviderSelection
import com.nexio.tv.domain.model.LibraryProviderSnapshot
import com.nexio.tv.domain.model.LibrarySourceMode
import com.nexio.tv.domain.model.ListMembershipChanges
import com.nexio.tv.domain.model.ListMembershipSnapshot
import com.nexio.tv.domain.model.TrackingProvider
import com.nexio.tv.domain.model.TraktListPrivacy
import com.nexio.tv.domain.model.UnifiedWatchlistMembership
import com.nexio.tv.domain.repository.LibraryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LibraryRepositoryImpl @Inject constructor(
    private val trackingProviderStateService: TrackingProviderStateService,
    private val traktLibraryService: TraktLibraryService,
    private val simklLibraryService: SimklLibraryService,
    private val mdbListLibraryService: MDBListLibraryService,
    private val debridLibraryService: DebridLibraryService,
    private val unifiedWatchlistRepository: UnifiedWatchlistRepository = UnifiedWatchlistRepository(
        traktLibraryService = traktLibraryService,
        simklLibraryService = simklLibraryService,
        mdbListLibraryService = mdbListLibraryService,
    )
) : LibraryRepository {
    override val sourceMode: Flow<LibrarySourceMode> = combine(
        trackingProviderStateService.state,
        traktLibraryService.observeHasCache(),
        simklLibraryService.observeHasCache(),
        debridLibraryService.observeIsConnected()
    ) { providerState, hasProviderCache, hasSimklCache, isDebridConnected ->
        when {
            providerState.effectiveProvider == TrackingProvider.TRAKT &&
                (providerState.traktAuthenticated || hasProviderCache) -> LibrarySourceMode.TRAKT
            providerState.effectiveProvider == TrackingProvider.SIMKL &&
                (providerState.simklAuthenticated || hasSimklCache) -> LibrarySourceMode.SIMKL
            isDebridConnected -> LibrarySourceMode.DEBRID
            else -> LibrarySourceMode.LOCAL
        }
    }.distinctUntilChanged()

    override val isSyncing: Flow<Boolean> = combine(
        traktLibraryService.observeIsRefreshing(),
        simklLibraryService.observeIsRefreshing(),
        mdbListLibraryService.observeIsRefreshing(),
        debridLibraryService.observeIsRefreshing()
    ) { traktRefreshing, simklRefreshing, mdbListRefreshing, debridRefreshing ->
        traktRefreshing || simklRefreshing || mdbListRefreshing || debridRefreshing
    }
        .distinctUntilChanged()

    override val hasProviderCache: Flow<Boolean> =
        combine(
            sourceMode,
            traktLibraryService.observeHasCache(),
            simklLibraryService.observeHasCache()
        ) { mode, hasProviderCache, hasSimklCache ->
            when (mode) {
                LibrarySourceMode.SIMKL -> hasSimklCache
                LibrarySourceMode.TRAKT -> hasProviderCache
                else -> false
            }
        }.distinctUntilChanged()

    override val libraryItems: Flow<List<LibraryEntry>> =
        combine(
            sourceMode,
            traktLibraryService.observeAllItems(),
            simklLibraryService.observeAllItems(),
            debridLibraryService.observeItems().onStart { emit(emptyList()) }
        ) { mode, traktItems, simklItems, debridItems ->
            mergeLibraryItemsForMode(
                mode = mode,
                traktItems = traktItems,
                simklItems = simklItems,
                debridItems = debridItems
            )
        }.distinctUntilChanged()

    override val listTabs: Flow<List<LibraryListTab>> =
        combine(
            sourceMode,
            traktLibraryService.observeListTabs(),
            simklLibraryService.observeListTabs(),
            debridLibraryService.observeListTabs().onStart { emit(emptyList()) }
        ) { mode, traktTabs, simklTabs, debridTabs ->
            mergeLibraryTabsForMode(
                mode = mode,
                traktTabs = traktTabs,
                simklTabs = simklTabs,
                debridTabs = debridTabs
            )
        }.distinctUntilChanged()

    override val unifiedWatchlistMemberships: Flow<List<UnifiedWatchlistMembership>> =
        unifiedWatchlistRepository.memberships

    override val availableProviders: Flow<List<LibraryProviderOption>> = combine(
        trackingProviderStateService.state,
        debridLibraryService.observeAvailableProviders()
    ) { providerState, debridProviders ->
        buildList {
            add(LibraryProviderOption(LibraryProviderSelection.UNIFIED))
            if (providerState.traktAuthenticated) add(LibraryProviderOption(LibraryProviderSelection.TRAKT))
            if (providerState.simklAuthenticated) add(LibraryProviderOption(LibraryProviderSelection.SIMKL))
            if (providerState.mdbListAuthenticated) add(LibraryProviderOption(LibraryProviderSelection.MDBLIST))
            if (LibraryProviderSelection.REAL_DEBRID in debridProviders) {
                add(LibraryProviderOption(LibraryProviderSelection.REAL_DEBRID))
            }
            if (LibraryProviderSelection.PREMIUMIZE in debridProviders) {
                add(LibraryProviderOption(LibraryProviderSelection.PREMIUMIZE))
            }
            if (LibraryProviderSelection.TORBOX in debridProviders) {
                add(LibraryProviderOption(LibraryProviderSelection.TORBOX))
            }
            if (LibraryProviderSelection.EASY_DEBRID in debridProviders) {
                add(LibraryProviderOption(LibraryProviderSelection.EASY_DEBRID))
            }
        }
    }
        .onStart { emit(listOf(LibraryProviderOption(LibraryProviderSelection.UNIFIED))) }
        .distinctUntilChanged()

    override fun observeProviderSnapshot(
        provider: LibraryProviderSelection,
        selectedListKey: String?
    ): Flow<LibraryProviderSnapshot> {
        return when (provider) {
            LibraryProviderSelection.UNIFIED -> trackingProviderStateService.state
                .map { providerState ->
                    providerSnapshotFor(
                        provider = provider,
                        selectedListKey = selectedListKey,
                        providerState = providerState,
                        traktItems = emptyList(),
                        simklItems = emptyList(),
                        mdbItems = emptyList(),
                        debridItems = emptyList(),
                        traktTabs = emptyList(),
                        simklTabs = emptyList(),
                        mdbTabs = emptyList(),
                        debridTabs = emptyList()
                    )
                }
                .onStart {
                    emit(
                        LibraryProviderSnapshot(
                            provider = LibraryProviderSelection.UNIFIED,
                            sourceMode = LibrarySourceMode.LOCAL
                        )
                    )
                }
            LibraryProviderSelection.TRAKT -> combine(
                trackingProviderStateService.state,
                traktLibraryService.observeAllItems(),
                traktLibraryService.observeListTabs()
            ) { providerState, items, tabs ->
                providerSnapshotFor(
                    provider = provider,
                    selectedListKey = selectedListKey,
                    providerState = providerState,
                    traktItems = items,
                    simklItems = emptyList(),
                    mdbItems = emptyList(),
                    debridItems = emptyList(),
                    traktTabs = tabs,
                    simklTabs = emptyList(),
                    mdbTabs = emptyList(),
                    debridTabs = emptyList()
                )
            }
            LibraryProviderSelection.SIMKL -> combine(
                trackingProviderStateService.state,
                simklLibraryService.observeAllItems(),
                simklLibraryService.observeListTabs()
            ) { providerState, items, tabs ->
                providerSnapshotFor(
                    provider = provider,
                    selectedListKey = selectedListKey,
                    providerState = providerState,
                    traktItems = emptyList(),
                    simklItems = items,
                    mdbItems = emptyList(),
                    debridItems = emptyList(),
                    traktTabs = emptyList(),
                    simklTabs = tabs,
                    mdbTabs = emptyList(),
                    debridTabs = emptyList()
                )
            }
            LibraryProviderSelection.MDBLIST -> combine(
                trackingProviderStateService.state,
                mdbListLibraryService.observeAllItems(),
                mdbListLibraryService.observeListTabs()
            ) { providerState, items, tabs ->
                providerSnapshotFor(
                    provider = provider,
                    selectedListKey = selectedListKey,
                    providerState = providerState,
                    traktItems = emptyList(),
                    simklItems = emptyList(),
                    mdbItems = items,
                    debridItems = emptyList(),
                    traktTabs = emptyList(),
                    simklTabs = emptyList(),
                    mdbTabs = tabs,
                    debridTabs = emptyList()
                )
            }
            LibraryProviderSelection.REAL_DEBRID,
            LibraryProviderSelection.PREMIUMIZE,
            LibraryProviderSelection.TORBOX,
            LibraryProviderSelection.EASY_DEBRID -> combine(
                trackingProviderStateService.state,
                debridLibraryService.observeItems(),
                debridLibraryService.observeListTabs()
            ) { providerState, items, tabs ->
                providerSnapshotFor(
                    provider = provider,
                    selectedListKey = selectedListKey,
                    providerState = providerState,
                    traktItems = emptyList(),
                    simklItems = emptyList(),
                    mdbItems = emptyList(),
                    debridItems = items,
                    traktTabs = emptyList(),
                    simklTabs = emptyList(),
                    mdbTabs = emptyList(),
                    debridTabs = tabs
                )
            }
        }.distinctUntilChanged()
    }

    override fun isInLibrary(itemId: String, itemType: String): Flow<Boolean> {
        return combine(
            sourceMode,
            traktLibraryService.observeMembership(itemId, itemType),
            simklLibraryService.observeMembership(itemId, itemType)
        ) { mode, traktMembership, simklMembership ->
            when (mode) {
                LibrarySourceMode.SIMKL -> simklMembership.isNotEmpty()
                LibrarySourceMode.TRAKT -> traktMembership.isNotEmpty()
                else -> false
            }
        }
            .distinctUntilChanged()
    }

    override fun isInWatchlist(itemId: String, itemType: String): Flow<Boolean> {
        return combine(
            sourceMode,
            traktLibraryService.observeMembership(itemId, itemType),
            simklLibraryService.observeMembership(itemId, itemType)
        ) { mode, traktMembership, simklMembership ->
            when (mode) {
                LibrarySourceMode.SIMKL -> simklMembership.contains(SimklLibraryService.WATCHLIST_KEY)
                LibrarySourceMode.TRAKT -> traktMembership.contains(TraktLibraryService.WATCHLIST_KEY)
                else -> false
            }
        }
            .distinctUntilChanged()
    }

    override suspend fun toggleDefault(item: LibraryEntryInput) {
        val providerState = trackingProviderStateService.currentState()
        when (providerState.effectiveProvider) {
            TrackingProvider.SIMKL -> {
                if (!providerState.simklAuthenticated) return
                simklLibraryService.toggleWatchlist(item)
            }
            TrackingProvider.TRAKT -> {
                if (!providerState.traktAuthenticated) return
                traktLibraryService.toggleWatchlist(item)
            }
            TrackingProvider.MDBLIST -> return
        }
    }

    override suspend fun getMembershipSnapshot(item: LibraryEntryInput): ListMembershipSnapshot {
        val providerState = trackingProviderStateService.currentState()
        return when (providerState.effectiveProvider) {
            TrackingProvider.SIMKL -> {
                if (providerState.simklAuthenticated) simklLibraryService.getMembershipSnapshot(item)
                else ListMembershipSnapshot(listMembership = emptyMap())
            }
            TrackingProvider.TRAKT -> {
                if (providerState.traktAuthenticated) traktLibraryService.getMembershipSnapshot(item)
                else ListMembershipSnapshot(listMembership = emptyMap())
            }
            TrackingProvider.MDBLIST -> ListMembershipSnapshot(listMembership = emptyMap())
        }
    }

    override suspend fun applyMembershipChanges(item: LibraryEntryInput, changes: ListMembershipChanges) {
        val providerState = trackingProviderStateService.currentState()
        when (providerState.effectiveProvider) {
            TrackingProvider.SIMKL -> {
                if (!providerState.simklAuthenticated) return
                simklLibraryService.applyMembershipChanges(item, changes)
            }
            TrackingProvider.TRAKT -> {
                if (!providerState.traktAuthenticated) return
                traktLibraryService.applyMembershipChanges(item, changes)
            }
            TrackingProvider.MDBLIST -> return
        }
    }

    override suspend fun createPersonalList(name: String, description: String?, privacy: TraktListPrivacy) {
        requireTraktAuth()
        traktLibraryService.createPersonalList(name = name, description = description, privacy = privacy)
    }

    override suspend fun updatePersonalList(
        listId: String,
        name: String,
        description: String?,
        privacy: TraktListPrivacy
    ) {
        requireTraktAuth()
        traktLibraryService.updatePersonalList(
            listId = listId,
            name = name,
            description = description,
            privacy = privacy
        )
    }

    override suspend fun deletePersonalList(listId: String) {
        requireTraktAuth()
        traktLibraryService.deletePersonalList(listId)
    }

    override suspend fun reorderPersonalLists(orderedListIds: List<String>) {
        requireTraktAuth()
        traktLibraryService.reorderPersonalLists(orderedListIds)
    }

    override suspend fun refreshNow() {
        refreshUnifiedTrackers()
        debridLibraryService.refreshNow(DebridLibraryService.RefreshTarget.ALL)
    }

    override suspend fun refreshProviderNow() {
        refreshUnifiedTrackers()
    }

    override suspend fun refreshDebridNow() {
        debridLibraryService.refreshNow(DebridLibraryService.RefreshTarget.ALL)
    }

    override suspend fun refreshRealDebridNow() {
        debridLibraryService.refreshNow(DebridLibraryService.RefreshTarget.REAL_DEBRID)
    }

    override suspend fun refreshPremiumizeNow() {
        debridLibraryService.refreshNow(DebridLibraryService.RefreshTarget.PREMIUMIZE)
    }

    override suspend fun refreshTorBoxNow() {
        debridLibraryService.refreshNow(DebridLibraryService.RefreshTarget.TORBOX)
    }

    override suspend fun refreshEasyDebridNow() {
        debridLibraryService.refreshNow(DebridLibraryService.RefreshTarget.EASY_DEBRID)
    }

    override suspend fun refreshProviderNow(provider: LibraryProviderSelection, selectedListKey: String?) {
        when (provider) {
            LibraryProviderSelection.UNIFIED -> refreshUnifiedTrackers()
            LibraryProviderSelection.TRAKT -> traktLibraryService.refreshNow()
            LibraryProviderSelection.SIMKL -> simklLibraryService.refreshNow()
            LibraryProviderSelection.MDBLIST -> mdbListLibraryService.refreshNow(force = false, selectedListKey = selectedListKey)
            LibraryProviderSelection.REAL_DEBRID -> refreshRealDebridNow()
            LibraryProviderSelection.PREMIUMIZE -> refreshPremiumizeNow()
            LibraryProviderSelection.TORBOX -> refreshTorBoxNow()
            LibraryProviderSelection.EASY_DEBRID -> refreshEasyDebridNow()
        }
    }

    private suspend fun refreshUnifiedTrackers() {
        supervisorScope {
            launch {
                runCatching { traktLibraryService.ensureFresh() }
            }
            launch {
                runCatching { simklLibraryService.refreshNow(force = false) }
            }
            launch {
                runCatching { mdbListLibraryService.refreshNow(force = false) }
            }
        }
    }

    override suspend fun createProviderList(
        provider: LibraryProviderSelection,
        name: String,
        description: String?,
        privacy: TraktListPrivacy
    ) {
        when (provider) {
            LibraryProviderSelection.TRAKT -> createPersonalList(name, description, privacy)
            LibraryProviderSelection.MDBLIST -> mdbListLibraryService.createStaticList(
                name = name,
                private = privacy == TraktListPrivacy.PRIVATE
            )
            else -> throw IllegalStateException("${provider.label} list creation is not supported")
        }
    }

    override suspend fun updateProviderList(
        provider: LibraryProviderSelection,
        listId: String,
        name: String,
        description: String?,
        privacy: TraktListPrivacy
    ) {
        when (provider) {
            LibraryProviderSelection.TRAKT -> updatePersonalList(listId, name, description, privacy)
            LibraryProviderSelection.MDBLIST -> mdbListLibraryService.updateStaticList(
                listId = listId,
                name = name,
                private = privacy == TraktListPrivacy.PRIVATE
            )
            else -> throw IllegalStateException("${provider.label} list update is not supported")
        }
    }

    override suspend fun deleteProviderList(provider: LibraryProviderSelection, listId: String) {
        when (provider) {
            LibraryProviderSelection.TRAKT -> deletePersonalList(listId)
            LibraryProviderSelection.MDBLIST -> mdbListLibraryService.deleteStaticList(listId)
            else -> throw IllegalStateException("${provider.label} list deletion is not supported")
        }
    }

    private suspend fun requireTraktAuth() {
        if (!trackingProviderStateService.currentState().traktAuthenticated) {
            throw IllegalStateException("Trakt authentication required")
        }
    }

    private fun providerSnapshotFor(
        provider: LibraryProviderSelection,
        selectedListKey: String?,
        providerState: EffectiveTrackingProviderState,
        traktItems: List<LibraryEntry>,
        simklItems: List<LibraryEntry>,
        mdbItems: List<LibraryEntry>,
        debridItems: List<LibraryEntry>,
        traktTabs: List<LibraryListTab>,
        simklTabs: List<LibraryListTab>,
        mdbTabs: List<LibraryListTab>,
        debridTabs: List<LibraryListTab>
    ): LibraryProviderSnapshot {
        return when (provider) {
            LibraryProviderSelection.UNIFIED -> LibraryProviderSnapshot(
                provider = provider,
                sourceMode = LibrarySourceMode.LOCAL,
                emptyReason = if (!providerState.hasAuthenticatedProvider) {
                    LibraryEmptyReason.UNIFIED_NEEDS_TRACKER_AUTH
                } else {
                    LibraryEmptyReason.NONE
                }
            )
            LibraryProviderSelection.TRAKT -> trackerSnapshot(
                provider = provider,
                sourceMode = LibrarySourceMode.TRAKT,
                items = traktItems,
                tabs = traktTabs,
                selectedListKey = selectedListKey,
                managementMode = LibraryListManagementMode.TRAKT_PERSONAL
            )
            LibraryProviderSelection.SIMKL -> trackerSnapshot(
                provider = provider,
                sourceMode = LibrarySourceMode.SIMKL,
                items = simklItems,
                tabs = simklTabs,
                selectedListKey = selectedListKey,
                managementMode = LibraryListManagementMode.SIMKL_STATUS
            )
            LibraryProviderSelection.MDBLIST -> trackerSnapshot(
                provider = provider,
                sourceMode = LibrarySourceMode.TRAKT,
                items = mdbItems,
                tabs = mdbTabs,
                selectedListKey = selectedListKey,
                managementMode = LibraryListManagementMode.MDBLIST_STATIC
            )
            LibraryProviderSelection.REAL_DEBRID -> debridSnapshot(
                provider = provider,
                items = debridItems,
                listKey = DebridLibraryService.REAL_DEBRID_LIST_KEY
            )
            LibraryProviderSelection.PREMIUMIZE -> debridSnapshot(
                provider = provider,
                items = debridItems,
                listKey = DebridLibraryService.PREMIUMIZE_LIST_KEY
            )
            LibraryProviderSelection.TORBOX -> debridSnapshot(
                provider = provider,
                items = debridItems,
                listKey = DebridLibraryService.TORBOX_LIST_KEY
            )
            LibraryProviderSelection.EASY_DEBRID -> debridSnapshot(
                provider = provider,
                items = debridItems,
                listKey = DebridLibraryService.EASY_DEBRID_LIST_KEY
            )
        }
    }

    private fun trackerSnapshot(
        provider: LibraryProviderSelection,
        sourceMode: LibrarySourceMode,
        items: List<LibraryEntry>,
        tabs: List<LibraryListTab>,
        selectedListKey: String?,
        managementMode: LibraryListManagementMode
    ): LibraryProviderSnapshot {
        val nextSelected = selectedListKey?.takeIf { key -> tabs.any { it.key == key } } ?: tabs.firstOrNull()?.key
        return LibraryProviderSnapshot(
            provider = provider,
            sourceMode = sourceMode,
            items = items,
            listTabs = tabs,
            selectedListKey = nextSelected,
            supportsLists = tabs.isNotEmpty(),
            supportsListManagement = managementMode != LibraryListManagementMode.NONE,
            listManagementMode = managementMode,
            emptyReason = LibraryEmptyReason.PROVIDER_EMPTY,
            listSelectorLabel = tabs.firstOrNull { it.key == nextSelected }?.title ?: "Select"
        )
    }

    private fun debridSnapshot(
        provider: LibraryProviderSelection,
        items: List<LibraryEntry>,
        listKey: String
    ): LibraryProviderSnapshot {
        return LibraryProviderSnapshot(
            provider = provider,
            sourceMode = LibrarySourceMode.DEBRID,
            items = items.filter { it.listKeys.contains(listKey) },
            listTabs = emptyList(),
            selectedListKey = null,
            supportsLists = false,
            supportsListManagement = false,
            listManagementMode = LibraryListManagementMode.NONE,
            emptyReason = LibraryEmptyReason.PROVIDER_EMPTY,
            listSelectorLabel = "N/A"
        )
    }
}

internal fun mergeLibraryItemsForMode(
    mode: LibrarySourceMode,
    traktItems: List<LibraryEntry>,
    simklItems: List<LibraryEntry>,
    debridItems: List<LibraryEntry>
): List<LibraryEntry> {
    return when (mode) {
        LibrarySourceMode.TRAKT -> traktItems + debridItems
        LibrarySourceMode.SIMKL -> simklItems + debridItems
        LibrarySourceMode.DEBRID,
        LibrarySourceMode.LOCAL -> debridItems
    }
}

internal fun mergeLibraryTabsForMode(
    mode: LibrarySourceMode,
    traktTabs: List<LibraryListTab>,
    simklTabs: List<LibraryListTab>,
    debridTabs: List<LibraryListTab>
): List<LibraryListTab> {
    return when (mode) {
        LibrarySourceMode.TRAKT -> traktTabs + debridTabs
        LibrarySourceMode.SIMKL -> simklTabs + debridTabs
        LibrarySourceMode.DEBRID,
        LibrarySourceMode.LOCAL -> debridTabs
    }
}
