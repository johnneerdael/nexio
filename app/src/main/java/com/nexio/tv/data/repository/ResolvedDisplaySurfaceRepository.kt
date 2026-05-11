package com.nexio.tv.data.repository

import androidx.annotation.VisibleForTesting
import com.nexio.tv.core.artwork.ArtworkDisplayRef
import com.nexio.tv.core.artwork.ArtworkProviderId
import com.nexio.tv.core.artwork.ArtworkType
import com.nexio.tv.core.integration.ActiveProfileSession
import com.nexio.tv.core.profile.ProfileManager
import com.nexio.tv.core.trace.NoopRuntimeTraceSink
import com.nexio.tv.core.trace.TraceMetadataEvents
import com.nexio.tv.domain.model.ProviderIds
import com.nexio.tv.domain.model.ResolvedDisplayItem
import com.nexio.tv.domain.model.ResolvedSlot
import com.nexio.tv.domain.model.TrailerDisplayState
import com.nexio.tv.ui.screens.home.HomeRailProjectionReducer
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

@Singleton
class ResolvedDisplaySurfaceRepository(
    private val activeProfileSession: () -> ActiveProfileSession,
    private val traceEvents: TraceMetadataEvents = TraceMetadataEvents(
        sink = NoopRuntimeTraceSink,
        sessionId = { null }
    )
) {
    @Inject
    constructor(
        profileManager: ProfileManager,
        traceEvents: TraceMetadataEvents
    ) : this(
        activeProfileSession = { profileManager.activeProfileSession.value },
        traceEvents = traceEvents
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
            val merged = mergeIncrementalItems(existing, items, traceEvents)
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
            val existingList = currentSurface[profileSession.profileId].orEmpty()
            val nextItems = if (replace) {
                applyNonDowngradeMergeForReplace(existingList, items, traceEvents)
            } else {
                mergeIncrementalItems(existingList, items, traceEvents)
            }.distinctBy { item -> item.itemKey }
            if (shouldSuppressSurfaceUpdate(surfaceKey, existingList, nextItems)) {
                current
            } else {
                published = true
                current + (surfaceKey to (currentSurface + (profileSession.profileId to nextItems)))
            }
        }
        return published
    }

    /**
     * Phase 3.7 — cold-start restore. Seeds the home-surface in-memory state
     * for [profileId] with [items] previously persisted by
     * [com.nexio.tv.data.local.ResolvedDisplaySnapshotStore]. Bypasses the
     * `shouldSuppressSurfaceUpdate` gate: restore IS authoritative (we're
     * recovering the typed authority's last-known state from disk), not a
     * competing fresh emission.
     *
     * Idempotent — restored items never overwrite items already in memory
     * (e.g. if the producer beat us to it). Only fills gaps.
     */
    @Synchronized
    fun restoreFromDisk(items: Map<String, ResolvedDisplayItem>, profileId: Int) {
        if (items.isEmpty()) return
        val itemsList = items.values.toList()
        surfaces.update { current ->
            val currentSurface = current[HOME_SURFACE_KEY].orEmpty()
            val existing = currentSurface[profileId].orEmpty()
            val existingKeys = existing.map { it.itemKey }.toSet()
            val newItems = itemsList.filter { it.itemKey !in existingKeys }
            if (newItems.isEmpty()) return@update current
            val merged = existing + newItems
            current + (HOME_SURFACE_KEY to (currentSurface + (profileId to merged)))
        }
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
    incoming: List<ResolvedDisplayItem>,
    traceEvents: TraceMetadataEvents?
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
        val existingForKey = existingByKey[item.itemKey]
        val rankProtected = applyNonDowngradeMerge(item, existingForKey, traceEvents)
        mergedIncoming += rankProtected.withPreservedTrailerState(existingForKey)
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

/**
 * Non-downgrade per-itemKey merge — the load-bearing rule #1 enforcement point.
 *
 * For each [incoming] item that has a counterpart [existing] (matched by itemKey
 * upstream), runs HomeRailProjectionReducer over (firstPaint=incoming.slots,
 * existing=existing.slots) and rebuilds the incoming item with the rank-winning
 * slots — preserving non-slot fields (trailer, hydrationState, etc.) from the
 * incoming side, since the producer's view of those is more current than the
 * repository's prior emission.
 *
 * Reference-stability: when the reducer's output equals the existing item's
 * slots field-for-field AND incoming's slot-derived flat fields match existing,
 * returns the existing instance so downstream `===` short-circuits hold.
 *
 * Slot-aware merge is skipped (incoming returned as-is) when either side has
 * `slots == null` — that signals a legacy code path that hasn't migrated to
 * the typed slot model. Once Phase 4 retires the legacy paths, this fallback
 * becomes dead code and can be removed.
 */
private fun applyNonDowngradeMerge(
    incoming: ResolvedDisplayItem,
    existing: ResolvedDisplayItem?,
    traceEvents: TraceMetadataEvents?
): ResolvedDisplayItem {
    if (existing == null) return incoming
    val incomingSlots = incoming.slots ?: return incoming
    val existingSlots = existing.slots ?: return incoming

    val reducerMerged = HomeRailProjectionReducer.reduce(
        firstPaint = incomingSlots,
        overlay = null,
        existing = existingSlots,
        profile = null
    )

    // Apply preferred-provider tie-break for artwork slots only.
    // Reducer stays pure: settings/preferences are consulted only at this
    // merge boundary, not inside pickHigherRanked. (Bug A — Task 12)
    val itemKey = incoming.itemKey
    val mergedSlots = reducerMerged.copy(
        poster    = preferredAwareSlot(incomingSlots.poster,    existingSlots.poster,    incoming.preferredArtworkProviders[ArtworkType.POSTER],    itemKey, "POSTER", traceEvents),
        backdrop  = preferredAwareSlot(incomingSlots.backdrop,  existingSlots.backdrop,  incoming.preferredArtworkProviders[ArtworkType.BACKDROP],  itemKey, "BACKDROP", traceEvents),
        logo      = preferredAwareSlot(incomingSlots.logo,      existingSlots.logo,      incoming.preferredArtworkProviders[ArtworkType.LOGO],      itemKey, "LOGO", traceEvents),
        thumbnail = preferredAwareSlot(incomingSlots.thumbnail, existingSlots.thumbnail, incoming.preferredArtworkProviders[ArtworkType.THUMBNAIL], itemKey, "THUMBNAIL", traceEvents)
    )

    // Strengthen-only ProviderIds union. The cross-id enricher publishes the
    // same item with progressively richer IDs (TMDB → +IMDB → +TVDB etc.) as
    // Room resolves them. Slots can stay identical across these emissions, so
    // the post-first-paint invariant ("hydrated items carry IMDB + native id")
    // requires this merge to be ID-aware, not slot-only.
    val mergedStableIds = strengthenProviderIds(existing.stableIds, incoming.stableIds)
    val mergedImdbId = incoming.imdbId ?: existing.imdbId

    if (mergedSlots == existingSlots &&
        incoming.slotDerivedFieldsMatch(existing) &&
        mergedStableIds == existing.stableIds &&
        mergedImdbId == existing.imdbId
    ) {
        return existing
    }

    val mergedArtwork = mergedSlots.toArtworkBundle()
    val mergedDisplay = mergedSlots.toResolvedDisplayFields(
        fallbackTitle = incoming.display.title.orEmpty(),
        fallbackTomatoesRating = incoming.display.tomatoesRating ?: existing.display.tomatoesRating
    )
    val mergedRating = mergedSlots.toRating() ?: incoming.rating

    return incoming.copy(
        slots = mergedSlots,
        artwork = mergedArtwork,
        display = mergedDisplay,
        rating = mergedRating,
        stableIds = mergedStableIds,
        imdbId = mergedImdbId
    )
}

private fun ResolvedDisplayItem.slotDerivedFieldsMatch(other: ResolvedDisplayItem): Boolean =
    artwork == other.artwork && display == other.display && rating == other.rating

private fun strengthenProviderIds(existing: ProviderIds, incoming: ProviderIds): ProviderIds {
    if (existing == incoming) return existing
    return ProviderIds(
        imdb = incoming.imdb ?: existing.imdb,
        tmdb = incoming.tmdb ?: existing.tmdb,
        tvdb = incoming.tvdb ?: existing.tvdb,
        trakt = incoming.trakt ?: existing.trakt,
        simkl = incoming.simkl ?: existing.simkl,
        kitsu = incoming.kitsu ?: existing.kitsu,
        slug = incoming.slug ?: existing.slug,
        mal = incoming.mal ?: existing.mal,
        anilist = incoming.anilist ?: existing.anilist,
        anidb = incoming.anidb ?: existing.anidb
    )
}

/**
 * Preferred-provider-aware tie-breaker for an artwork slot (Bug A — Task 12).
 *
 * Rank-priority is honoured first (RESOLVED beats FIRST_PAINT, etc). On a true
 * rank tie, consult [preferred]:
 * - if incoming matches preferred and existing does not → incoming wins (upgrade).
 * - if existing matches preferred and incoming does not → existing wins
 *   (REJECT REGRESSION — the cause of RPDB ↔ addon popping).
 * - both match → incoming wins (newer side; "B" was the more recent decision).
 * - neither matches → existing wins (avoids needless churn between equivalent
 *   fallbacks; without this branch Bug A still manifests as addon-A ↔ addon-B
 *   popping when the preferred provider is unreachable for the item).
 *
 * [preferred] is `null` when no preference is declared for this slot type
 * (e.g. cold-start restore items publish with `emptyMap()`), in which case the
 * function falls back to "incoming wins" on tie.
 */
private fun preferredAwareSlot(
    incoming: ResolvedSlot<ArtworkDisplayRef>,
    existing: ResolvedSlot<ArtworkDisplayRef>,
    preferred: ArtworkProviderId?,
    itemKey: String,
    slotType: String,
    traceEvents: TraceMetadataEvents?
): ResolvedSlot<ArtworkDisplayRef> {
    if (incoming.rank.ordinal > existing.rank.ordinal) return incoming
    if (incoming.rank.ordinal < existing.rank.ordinal) return existing
    // Rank tie. Consult preferred.
    if (preferred == null) return incoming  // no preference declared → newer wins
    val incomingMatches = incoming.provider == preferred.key
    val existingMatches = existing.provider == preferred.key
    return when {
        incomingMatches && !existingMatches -> incoming   // upgrade
        !incomingMatches && existingMatches -> {           // REJECT REGRESSION (Bug A)
            traceEvents?.emitSurfaceMergeTieBreakRejected(
                itemKey = itemKey,
                slotType = slotType,
                existingProvider = existing.provider,
                incomingProvider = incoming.provider,
                preferredProvider = preferred.key
            )
            existing
        }
        incomingMatches && existingMatches  -> incoming   // both preferred → newer wins
        else                                -> existing   // neither preferred → existing stays
    }
}

/**
 * Wholesale-replace path: the surface becomes exactly [incoming], but per-item
 * slots that appear on both sides are rank-merged via [applyNonDowngradeMerge]
 * so [incoming]'s FIRST_PAINT cannot overwrite a previously-published RESOLVED
 * slot. Items in [existing] whose itemKey is NOT in [incoming] are dropped —
 * that's still wholesale set replacement; only per-item slot data is rank-
 * protected.
 *
 * Allocation-tuned: when [incoming] is element-wise reference-equal to
 * [existing], returns [existing] unchanged.
 */
private fun applyNonDowngradeMergeForReplace(
    existing: List<ResolvedDisplayItem>,
    incoming: List<ResolvedDisplayItem>,
    traceEvents: TraceMetadataEvents?
): List<ResolvedDisplayItem> {
    if (existing.isEmpty()) return incoming
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
    val out = ArrayList<ResolvedDisplayItem>(incoming.size)
    for (i in incoming.indices) {
        val item = incoming[i]
        val existingForKey = existingByKey[item.itemKey]
        val rankProtected = applyNonDowngradeMerge(item, existingForKey, traceEvents)
        out += rankProtected.withPreservedTrailerState(existingForKey)
    }
    return out
}
