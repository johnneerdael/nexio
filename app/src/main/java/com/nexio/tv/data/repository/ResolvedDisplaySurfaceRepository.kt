package com.nexio.tv.data.repository

import com.nexio.tv.core.integration.ActiveProfileSession
import com.nexio.tv.core.profile.ProfileManager
import com.nexio.tv.domain.model.ResolvedDisplayItem
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

@Singleton
class ResolvedDisplaySurfaceRepository(
    private val activeProfileSession: () -> ActiveProfileSession
) {
    @Inject
    constructor(profileManager: ProfileManager) : this(
        activeProfileSession = { profileManager.activeProfileSession.value }
    )

    private val surfaces = MutableStateFlow<Map<Int, List<ResolvedDisplayItem>>>(emptyMap())

    fun observeHomeSurface(profileId: Int): Flow<List<ResolvedDisplayItem>> =
        surfaces.map { byProfile -> byProfile[profileId].orEmpty() }

    fun observeItem(profileId: Int, itemKey: String): Flow<ResolvedDisplayItem?> =
        observeHomeSurface(profileId).map { items -> items.firstOrNull { it.itemKey == itemKey } }

    suspend fun getSnapshot(profileId: Int): List<ResolvedDisplayItem> =
        surfaces.value[profileId].orEmpty()

    fun publishResolvedItems(
        profileSession: ActiveProfileSession,
        items: List<ResolvedDisplayItem>
    ): Boolean {
        val active = activeProfileSession()
        if (active.profileId != profileSession.profileId || active.sessionId != profileSession.sessionId) {
            return false
        }

        surfaces.update { current ->
            current + (profileSession.profileId to items.distinctBy { item -> item.itemKey })
        }
        return true
    }

    internal fun replaceForTest(
        profileId: Int,
        items: List<ResolvedDisplayItem>
    ) {
        surfaces.update { current -> current + (profileId to items.distinctBy { item -> item.itemKey }) }
    }
}
