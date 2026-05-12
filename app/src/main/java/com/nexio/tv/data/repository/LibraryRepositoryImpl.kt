// effectiveProvider still routes library writes here (watchlist, collections, etc).
// Phase 3 of the scrobble + CW dual-provider plan migrates this file to fan-out
// across activeProviders. Suppression is provisional and tracks that migration.
// Plan: docs/superpowers/plans/2026-05-12-scrobble-cw-dual-provider-overhaul.md
@file:Suppress("DEPRECATION")

package com.nexio.tv.data.repository

import com.nexio.tv.domain.model.LibraryEntry
import com.nexio.tv.domain.model.LibraryEntryInput
import com.nexio.tv.domain.model.LibraryListTab
import com.nexio.tv.domain.model.LibrarySourceMode
import com.nexio.tv.domain.model.ListMembershipChanges
import com.nexio.tv.domain.model.ListMembershipSnapshot
import com.nexio.tv.domain.model.TrackingProvider
import com.nexio.tv.domain.model.TraktListPrivacy
import com.nexio.tv.domain.repository.LibraryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LibraryRepositoryImpl @Inject constructor(
    private val trackingProviderStateService: TrackingProviderStateService,
    private val traktLibraryService: TraktLibraryService,
    private val simklLibraryService: SimklLibraryService,
    private val debridLibraryService: DebridLibraryService
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
        debridLibraryService.observeIsRefreshing()
    ) { traktRefreshing, simklRefreshing, debridRefreshing ->
        traktRefreshing || simklRefreshing || debridRefreshing
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
        val providerState = trackingProviderStateService.currentState()
        when (providerState.effectiveProvider) {
            TrackingProvider.SIMKL -> {
                if (providerState.simklAuthenticated) simklLibraryService.refreshNow()
            }
            TrackingProvider.TRAKT -> {
                if (providerState.traktAuthenticated) traktLibraryService.refreshNow()
            }
        }
        debridLibraryService.refreshNow(DebridLibraryService.RefreshTarget.ALL)
    }

    override suspend fun refreshProviderNow() {
        val providerState = trackingProviderStateService.currentState()
        when (providerState.effectiveProvider) {
            TrackingProvider.SIMKL -> {
                if (providerState.simklAuthenticated) simklLibraryService.refreshNow()
            }
            TrackingProvider.TRAKT -> {
                if (providerState.traktAuthenticated) traktLibraryService.refreshNow()
            }
        }
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

    private suspend fun requireTraktAuth() {
        if (!trackingProviderStateService.currentState().traktAuthenticated) {
            throw IllegalStateException("Trakt authentication required")
        }
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
