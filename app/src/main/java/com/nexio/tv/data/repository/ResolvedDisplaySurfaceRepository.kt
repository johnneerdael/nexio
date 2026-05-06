package com.nexio.tv.data.repository

import androidx.annotation.VisibleForTesting
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

    @Synchronized
    fun publishResolvedItems(
        surfaceKey: String,
        items: List<ResolvedDisplayItem>
    ): Boolean {
        if (surfaceKey != HOME_SURFACE_KEY) return false
        val active = activeProfileSession()
        surfaces.update { current ->
            val existing = current[active.profileId].orEmpty()
            val incomingKeys = items.map { item -> item.itemKey }.toSet()
            val merged = existing
                .filterNot { item -> item.itemKey in incomingKeys } +
                items
            current + (active.profileId to merged.distinctBy { item -> item.itemKey })
        }
        return true
    }

    @Synchronized
    fun publishResolvedItems(
        profileSession: ActiveProfileSession,
        items: List<ResolvedDisplayItem>
    ): Boolean = publishResolvedItems(
        surfaceKey = HOME_SURFACE_KEY,
        profileSession = profileSession,
        items = items
    )

    @Synchronized
    fun publishResolvedItems(
        surfaceKey: String,
        profileSession: ActiveProfileSession,
        items: List<ResolvedDisplayItem>
    ): Boolean {
        if (surfaceKey != HOME_SURFACE_KEY) return false
        val active = activeProfileSession()
        if (active.profileId != profileSession.profileId || active.sessionId != profileSession.sessionId) {
            return false
        }

        surfaces.update { current ->
            current + (profileSession.profileId to items.distinctBy { item -> item.itemKey })
        }
        return true
    }

    // Test-only seed path for repository projection tests that provide final display items directly.
    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    internal fun replaceForTest(
        profileId: Int,
        items: List<ResolvedDisplayItem>
    ) {
        surfaces.update { current -> current + (profileId to items.distinctBy { item -> item.itemKey }) }
    }

    companion object {
        const val HOME_SURFACE_KEY = "home"
    }
}
