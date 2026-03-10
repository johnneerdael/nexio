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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryRepositoryImpl @Inject constructor(
    private val traktAuthDataStore: TraktAuthDataStore,
    private val traktLibraryService: TraktLibraryService,
    private val debridLibraryService: DebridLibraryService
) : LibraryRepository {

    override val sourceMode: Flow<LibrarySourceMode> = combine(
        traktAuthDataStore.isEffectivelyAuthenticated,
        debridLibraryService.observeIsConnected()
    ) { isTraktAuthenticated, isDebridConnected ->
        when {
            isTraktAuthenticated -> LibrarySourceMode.TRAKT
            isDebridConnected -> LibrarySourceMode.DEBRID
            else -> LibrarySourceMode.LOCAL
        }
    }.distinctUntilChanged()

    override val isSyncing: Flow<Boolean> = combine(
        traktAuthDataStore.isEffectivelyAuthenticated.flatMapLatest { isAuthenticated ->
            if (isAuthenticated) traktLibraryService.observeIsRefreshing() else flowOf(false)
        },
        debridLibraryService.observeIsRefreshing()
    ) { traktRefreshing, debridRefreshing ->
        traktRefreshing || debridRefreshing
    }
        .distinctUntilChanged()

    override val libraryItems: Flow<List<LibraryEntry>> = combine(
        traktAuthDataStore.isEffectivelyAuthenticated.flatMapLatest { isAuthenticated ->
            if (isAuthenticated) traktLibraryService.observeAllItems() else flowOf(emptyList())
        },
        debridLibraryService.observeItems()
    ) { traktItems, debridItems ->
        traktItems + debridItems
    }
        .distinctUntilChanged()

    override val listTabs: Flow<List<LibraryListTab>> = combine(
        traktAuthDataStore.isEffectivelyAuthenticated.flatMapLatest { isAuthenticated ->
            if (isAuthenticated) traktLibraryService.observeListTabs() else flowOf(emptyList())
        },
        debridLibraryService.observeListTabs()
    ) { traktTabs, debridTabs ->
        traktTabs + debridTabs
    }
        .distinctUntilChanged()

    override fun isInLibrary(itemId: String, itemType: String): Flow<Boolean> {
        return traktAuthDataStore.isEffectivelyAuthenticated.flatMapLatest { isAuthenticated ->
            if (isAuthenticated) {
                traktLibraryService.observeMembership(itemId, itemType)
                    .map { memberships -> memberships.isNotEmpty() }
            } else {
                flowOf(false)
            }
        }.distinctUntilChanged()
    }

    override fun isInWatchlist(itemId: String, itemType: String): Flow<Boolean> {
        return traktAuthDataStore.isEffectivelyAuthenticated.flatMapLatest { isAuthenticated ->
            if (isAuthenticated) {
                traktLibraryService.observeMembership(itemId, itemType)
                    .map { memberships -> memberships.contains(TraktLibraryService.WATCHLIST_KEY) }
            } else {
                flowOf(false)
            }
        }.distinctUntilChanged()
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
        debridLibraryService.refreshNow()
    }

    private suspend fun requireTraktAuth() {
        if (!traktAuthDataStore.isEffectivelyAuthenticated.first()) {
            throw IllegalStateException("Trakt authentication required")
        }
    }
}
