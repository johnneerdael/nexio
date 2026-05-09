package com.nexio.tv.data.repository

import androidx.annotation.VisibleForTesting
import com.nexio.tv.core.integration.ActiveProfileSession
import com.nexio.tv.core.profile.ProfileManager
import com.nexio.tv.domain.model.ResolvedDisplayItem
import com.nexio.tv.domain.model.TrailerDisplayState
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
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

    private val surfaces = MutableStateFlow<Map<String, Map<Int, List<ResolvedDisplayItem>>>>(emptyMap())

    fun observeHomeSurface(profileId: Int): Flow<List<ResolvedDisplayItem>> =
        observeSurface(HOME_SURFACE_KEY, profileId)

    fun observeScreensaverSurface(profileId: Int): Flow<List<ResolvedDisplayItem>> =
        observeSurface(SCREENSAVER_SURFACE_KEY, profileId)

    private fun observeSurface(surfaceKey: String, profileId: Int): Flow<List<ResolvedDisplayItem>> =
        surfaces
            .map { bySurface -> bySurface[surfaceKey]?.get(profileId).orEmpty() }
            .distinctUntilChanged()

    fun observeItem(profileId: Int, itemKey: String): Flow<ResolvedDisplayItem?> =
        observeHomeSurface(profileId).map { items -> items.firstOrNull { it.itemKey == itemKey } }

    suspend fun getSnapshot(profileId: Int): List<ResolvedDisplayItem> =
        getSnapshot(HOME_SURFACE_KEY, profileId)

    suspend fun getSnapshot(surfaceKey: String, profileId: Int): List<ResolvedDisplayItem> =
        surfaces.value[surfaceKey]?.get(profileId).orEmpty()

    fun snapshotNow(profileId: Int): List<ResolvedDisplayItem> =
        surfaces.value[HOME_SURFACE_KEY]?.get(profileId).orEmpty()

    @Synchronized
    fun publishResolvedItems(
        surfaceKey: String,
        items: List<ResolvedDisplayItem>
    ): Boolean {
        if (!isSupportedSurface(surfaceKey)) return false
        val active = activeProfileSession()
        var published = false
        surfaces.update { current ->
            val currentSurface = current[surfaceKey].orEmpty()
            val existing = currentSurface[active.profileId].orEmpty()
            val merged = mergeIncrementalItems(existing, items)
            val nextItems = merged.distinctBy { item -> item.itemKey }
            if (shouldSuppressSurfaceUpdate(surfaceKey, existing, nextItems)) {
                current
            } else {
                published = true
                current + (surfaceKey to (currentSurface + (active.profileId to nextItems)))
            }
        }
        return published
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
        items: List<ResolvedDisplayItem>,
        replace: Boolean = true
    ): Boolean {
        if (!isSupportedSurface(surfaceKey)) return false
        val active = activeProfileSession()
        if (active.profileId != profileSession.profileId || active.sessionId != profileSession.sessionId) {
            return false
        }

        var published = false
        surfaces.update { current ->
            val currentSurface = current[surfaceKey].orEmpty()
            val nextItems = if (replace) {
                items
            } else {
                val existing = currentSurface[profileSession.profileId].orEmpty()
                mergeIncrementalItems(existing, items)
            }.distinctBy { item -> item.itemKey }
            val existing = currentSurface[profileSession.profileId].orEmpty()
            if (shouldSuppressSurfaceUpdate(surfaceKey, existing, nextItems)) {
                current
            } else {
                published = true
                current + (surfaceKey to (currentSurface + (profileSession.profileId to nextItems)))
            }
        }
        return published
    }

    // Test-only seed path for repository projection tests that provide final display items directly.
    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    internal fun replaceForTest(
        surfaceKey: String = SCREENSAVER_SURFACE_KEY,
        profileId: Int,
        items: List<ResolvedDisplayItem>
    ) {
        surfaces.update { current ->
            val currentSurface = current[surfaceKey].orEmpty()
            current + (surfaceKey to (currentSurface + (profileId to items.distinctBy { item -> item.itemKey })))
        }
    }

    companion object {
        const val HOME_SURFACE_KEY = "home"
        const val SCREENSAVER_SURFACE_KEY = "screensaver"
    }
}

private fun isSupportedSurface(surfaceKey: String): Boolean =
    surfaceKey == ResolvedDisplaySurfaceRepository.HOME_SURFACE_KEY ||
        surfaceKey == ResolvedDisplaySurfaceRepository.SCREENSAVER_SURFACE_KEY

private fun mergeIncrementalItems(
    existing: List<ResolvedDisplayItem>,
    incoming: List<ResolvedDisplayItem>
): List<ResolvedDisplayItem> {
    if (existing.isEmpty()) return incoming
    // Fast path: incoming items are already reference-equal to existing in the same
    // order (HomeResolvedDisplayMapper memoization makes this the steady-state
    // case). Skip the associateBy / map / toSet / filterNot / + cascade — those
    // allocate ~5 collections per publish even when content is unchanged.
    if (existing.size == incoming.size) {
        var sameInPlace = true
        for (i in existing.indices) {
            if (existing[i] !== incoming[i]) { sameInPlace = false; break }
        }
        if (sameInPlace) return existing
    }
    val existingByKey = HashMap<String, ResolvedDisplayItem>(existing.size)
    for (i in existing.indices) {
        val item = existing[i]
        existingByKey[item.itemKey] = item
    }
    val mergedIncoming = ArrayList<ResolvedDisplayItem>(incoming.size)
    val incomingKeys = HashSet<String>(incoming.size)
    for (i in incoming.indices) {
        val item = incoming[i]
        mergedIncoming += item.withPreservedTrailerState(existingByKey[item.itemKey])
        incomingKeys += item.itemKey
    }
    val out = ArrayList<ResolvedDisplayItem>(existing.size + mergedIncoming.size)
    for (i in existing.indices) {
        val item = existing[i]
        if (item.itemKey !in incomingKeys) out += item
    }
    for (i in mergedIncoming.indices) out += mergedIncoming[i]
    return out
}

private fun ResolvedDisplayItem.withPreservedTrailerState(
    existing: ResolvedDisplayItem?
): ResolvedDisplayItem {
    val previousTrailer = existing?.trailer ?: return this
    if (trailer != TrailerDisplayState() || previousTrailer == TrailerDisplayState()) {
        return this
    }
    return copy(trailer = previousTrailer)
}

private fun shouldSuppressSurfaceUpdate(
    surfaceKey: String,
    existing: List<ResolvedDisplayItem>,
    nextItems: List<ResolvedDisplayItem>
): Boolean = when (surfaceKey) {
    ResolvedDisplaySurfaceRepository.SCREENSAVER_SURFACE_KEY ->
        existing.semanticallySameScreensaverSurface(nextItems)
    // After HomeResolvedDisplayMapper memoization, identical-content (MetaPreview, overlay)
    // tuples return the SAME ResolvedDisplayItem instance across emissions.
    // mergeIncrementalItems still allocates a fresh outer list each publish, so a
    // list-identity compare cannot work — but element-wise reference equality is
    // both correct and O(n). Deep equals() would be slow and could incorrectly
    // mask the ref-equality fast path.
    // withPreservedTrailerState only allocates a copy() when restoring a trailer
    // (rare); in the common no-op steady-state path it returns `this`, so element
    // refs stay stable and this short-circuit fires.
    ResolvedDisplaySurfaceRepository.HOME_SURFACE_KEY ->
        existing.refEqualsByIndex(nextItems)
    else -> false
}

// Indexed-for ref-equality compare. `existing.zip(other).all { (a, b) -> a === b }`
// allocates a List<Pair> ~equal in size to the surface (~300 items per publish);
// indexed-for allocates nothing.
private fun <T> List<T>.refEqualsByIndex(other: List<T>): Boolean {
    if (size != other.size) return false
    for (i in indices) {
        if (this[i] !== other[i]) return false
    }
    return true
}

private fun List<ResolvedDisplayItem>.semanticallySameScreensaverSurface(
    other: List<ResolvedDisplayItem>
): Boolean {
    // Avoid `map { stablePayload } == other.map { stablePayload }` — that allocates
    // 2 × N copies of ResolvedDisplayItem (one per side), each copy() walks every
    // field of the data class. Pairwise compare is allocation-free.
    if (size != other.size) return false
    for (i in indices) {
        if (this[i].screensaverStablePayload() != other[i].screensaverStablePayload()) return false
    }
    return true
}

private fun ResolvedDisplayItem.screensaverStablePayload(): ResolvedDisplayItem =
    copy(
        trailer = trailer.copy(lastResolvedAtMs = null),
        updatedAtMs = 0L
    )
