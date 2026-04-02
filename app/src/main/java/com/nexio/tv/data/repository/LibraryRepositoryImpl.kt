package com.nexio.tv.data.repository

import com.nexio.tv.data.local.TraktAuthDataStore
import com.nexio.tv.domain.model.LibraryEntry
import com.nexio.tv.domain.model.LibraryEntryInput
import com.nexio.tv.domain.model.LibraryListTab
import com.nexio.tv.domain.model.LibrarySourceMode
import com.nexio.tv.domain.model.ListMembershipChanges
import com.nexio.tv.domain.model.ListMembershipSnapshot
import com.nexio.tv.domain.model.TraktListPrivacy
import com.nexio.tv.domain.repository.LibraryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LibraryRepositoryImpl @Inject constructor(
    private val traktAuthDataStore: TraktAuthDataStore,
    private val traktLibraryService: TraktLibraryService,
    private val debridLibraryService: DebridLibraryService
) : LibraryRepository {

    override val sourceMode: Flow<LibrarySourceMode> = combine(
        traktAuthDataStore.isEffectivelyAuthenticated,
        traktLibraryService.observeHasCache(),
        debridLibraryService.observeIsConnected()
    ) { isTraktAuthenticated, hasTraktCache, isDebridConnected ->
        when {
            isTraktAuthenticated || hasTraktCache -> LibrarySourceMode.TRAKT
            isDebridConnected -> LibrarySourceMode.DEBRID
            else -> LibrarySourceMode.LOCAL
        }
    }.distinctUntilChanged()

    override val isSyncing: Flow<Boolean> = combine(
        traktLibraryService.observeIsRefreshing(),
        debridLibraryService.observeIsRefreshing()
    ) { traktRefreshing, debridRefreshing ->
        traktRefreshing || debridRefreshing
    }
        .distinctUntilChanged()

    override val hasTraktCache: Flow<Boolean> =
        traktLibraryService.observeHasCache().distinctUntilChanged()

    override val libraryItems: Flow<List<LibraryEntry>> =
        combine(
            traktLibraryService.observeAllItems(),
            debridLibraryService.observeItems().onStart { emit(emptyList()) }
        ) { traktItems, debridItems ->
            traktItems + debridItems
        }.distinctUntilChanged()

    override val listTabs: Flow<List<LibraryListTab>> =
        combine(
            traktLibraryService.observeListTabs(),
            debridLibraryService.observeListTabs().onStart { emit(emptyList()) }
        ) { traktTabs, debridTabs ->
            traktTabs + debridTabs
        }.distinctUntilChanged()

    override fun isInLibrary(itemId: String, itemType: String): Flow<Boolean> {
        return traktLibraryService.observeMembership(itemId, itemType)
            .map { memberships -> memberships.isNotEmpty() }
            .distinctUntilChanged()
    }

    override fun isInWatchlist(itemId: String, itemType: String): Flow<Boolean> {
        return traktLibraryService.observeMembership(itemId, itemType)
            .map { memberships -> memberships.contains(TraktLibraryService.WATCHLIST_KEY) }
            .distinctUntilChanged()
    }

    override suspend fun toggleDefault(item: LibraryEntryInput) {
        if (!traktAuthDataStore.isEffectivelyAuthenticated.first()) return
        traktLibraryService.toggleWatchlist(item)
    }

    override suspend fun getMembershipSnapshot(item: LibraryEntryInput): ListMembershipSnapshot {
        if (traktAuthDataStore.isEffectivelyAuthenticated.first()) {
            return traktLibraryService.getMembershipSnapshot(item)
        }
        return ListMembershipSnapshot(listMembership = emptyMap())
    }

    override suspend fun applyMembershipChanges(item: LibraryEntryInput, changes: ListMembershipChanges) {
        if (!traktAuthDataStore.isEffectivelyAuthenticated.first()) return
        traktLibraryService.applyMembershipChanges(item, changes)
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
        if (traktAuthDataStore.isEffectivelyAuthenticated.first()) {
            traktLibraryService.refreshNow()
        }
        debridLibraryService.refreshNow(DebridLibraryService.RefreshTarget.ALL)
    }

    override suspend fun refreshTraktNow() {
        if (traktAuthDataStore.isEffectivelyAuthenticated.first()) {
            traktLibraryService.refreshNow()
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
        if (!traktAuthDataStore.isEffectivelyAuthenticated.first()) {
            throw IllegalStateException("Trakt authentication required")
        }
    }
}
