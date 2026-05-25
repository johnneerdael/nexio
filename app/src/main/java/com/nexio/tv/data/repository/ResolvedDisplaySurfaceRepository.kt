package com.nexio.tv.data.repository

import androidx.annotation.VisibleForTesting
import com.nexio.tv.core.artwork.ArtworkDisplayRef
import com.nexio.tv.core.artwork.ArtworkProviderId
import com.nexio.tv.core.artwork.ArtworkType
import com.nexio.tv.core.integration.ActiveProfileSession
import com.nexio.tv.core.profile.ProfileManager
import com.nexio.tv.core.trace.NoopRuntimeTraceSink
import com.nexio.tv.core.trace.TraceMetadataEvents
import com.nexio.tv.domain.model.DisplayBundle
import com.nexio.tv.domain.model.DisplayFeatureSignature
import com.nexio.tv.domain.model.HydrationState
import com.nexio.tv.domain.model.ProviderIds
import com.nexio.tv.domain.model.ResolvedDisplayItem
import com.nexio.tv.domain.model.ResolvedSlot
import com.nexio.tv.domain.model.TrailerDisplayState
import com.nexio.tv.domain.model.visibleDisplaySignature
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

    fun observeUnifiedWatchlistSurface(profileId: Int): Flow<List<ResolvedDisplayItem>> =
        observeSurface(UNIFIED_WATCHLIST_SURFACE_KEY, profileId)

    private fun observeSurface(surfaceKey: String, profileId: Int): Flow<List<ResolvedDisplayItem>> =
        surfaces
            .map { bySurface -> bySurface[surfaceKey]?.get(profileId).orEmpty() }
            .distinctUntilChanged { old, new -> shouldSuppressSurfaceEmission(surfaceKey, old, new) }

    fun observeItem(profileId: Int, itemKey: String): Flow<ResolvedDisplayItem?> =
        observeHomeSurface(profileId).map { items -> items.firstOrNull { it.matchesAuthorityAlias(itemKey) } }

    fun hasHomeAuthorityItem(
        profileId: Int,
        itemKey: String,
        includePreviewOnly: Boolean = false
    ): Boolean =
        snapshotNow(profileId).any { item ->
            item.matchesAuthorityAlias(itemKey) &&
                (includePreviewOnly || item.hydrationState != HydrationState.PREVIEW_ONLY)
        }

    fun homeAuthorityItemsByAlias(profileId: Int): Map<String, ResolvedDisplayItem> {
        val items = snapshotNow(profileId)
        if (items.isEmpty()) return emptyMap()
        val out = HashMap<String, ResolvedDisplayItem>(items.size * 2)
        for (i in items.indices) {
            val item = items[i]
            val aliases = item.toDisplayBundle().aliases
            for (alias in aliases) {
                out[alias] = item
            }
        }
        return out
    }

    fun homeAuthorityAliasKeys(
        profileId: Int,
        includePreviewOnly: Boolean = false
    ): Set<String> {
        val items = snapshotNow(profileId)
        if (items.isEmpty()) return emptySet()
        val out = HashSet<String>(items.size * 2)
        for (i in items.indices) {
            val item = items[i]
            if (!includePreviewOnly && item.hydrationState == HydrationState.PREVIEW_ONLY) continue
            out += item.toDisplayBundle().aliases
        }
        return out
    }

    suspend fun getSnapshot(profileId: Int): List<ResolvedDisplayItem> =
        getSnapshot(HOME_SURFACE_KEY, profileId)

    suspend fun getSnapshot(surfaceKey: String, profileId: Int): List<ResolvedDisplayItem> =
        surfaces.value[surfaceKey]?.get(profileId).orEmpty()

    fun snapshotNow(profileId: Int): List<ResolvedDisplayItem> =
        surfaces.value[HOME_SURFACE_KEY]?.get(profileId).orEmpty()

    @Synchronized
    fun clearSurface(surfaceKey: String, profileId: Int): Boolean {
        if (!isSupportedSurface(surfaceKey)) return false
        var cleared = false
        surfaces.update { current ->
            val currentSurface = current[surfaceKey].orEmpty()
            if (currentSurface[profileId].isNullOrEmpty()) {
                current
            } else {
                cleared = true
                current + (surfaceKey to (currentSurface - profileId))
            }
        }
        return cleared
    }

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
            val nextItems = merged.toAuthorityProjection()
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
            }.toAuthorityProjection()
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
            val existingAliases = HashSet<String>(existing.size * 2)
            for (i in existing.indices) existingAliases += existing[i].toDisplayBundle().aliases
            val newItems = ArrayList<ResolvedDisplayItem>(itemsList.size)
            for (i in itemsList.indices) {
                val item = itemsList[i]
                if (item.toDisplayBundle().aliases.none { alias -> alias in existingAliases }) {
                    newItems += item
                }
            }
            if (newItems.isEmpty()) return@update current
            val merged = (existing + newItems).toAuthorityProjection()
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
            current + (surfaceKey to (currentSurface + (profileId to items.toAuthorityProjection())))
        }
    }

    companion object {
        const val HOME_SURFACE_KEY = "home"
        const val SCREENSAVER_SURFACE_KEY = "screensaver"
        const val UNIFIED_WATCHLIST_SURFACE_KEY = "unified_watchlist"
    }
}

private fun isSupportedSurface(surfaceKey: String): Boolean =
    surfaceKey == ResolvedDisplaySurfaceRepository.HOME_SURFACE_KEY ||
        surfaceKey == ResolvedDisplaySurfaceRepository.SCREENSAVER_SURFACE_KEY ||
        surfaceKey == ResolvedDisplaySurfaceRepository.UNIFIED_WATCHLIST_SURFACE_KEY

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
        val bundle = item.toDisplayBundle()
        for (alias in bundle.aliases) existingByKey[alias] = item
    }
    val replacementsByExistingKey = HashMap<String, ResolvedDisplayItem>(incoming.size)
    val additions = ArrayList<ResolvedDisplayItem>(incoming.size)
    val incomingKeys = HashSet<String>(incoming.size)
    for (i in incoming.indices) {
        val item = incoming[i]
        val incomingBundle = item.toDisplayBundle()
        val existingForKey = incomingBundle.aliases.firstNotNullOfOrNull { alias -> existingByKey[alias] }
        val rankProtected = applyNonDowngradeMerge(item, existingForKey, traceEvents)
        val merged = rankProtected.withPreservedTrailerState(existingForKey)
        if (existingForKey != null) {
            replacementsByExistingKey[existingForKey.itemKey] = if (item.itemKey != existingForKey.itemKey) {
                merged.copy(itemKey = existingForKey.itemKey)
            } else {
                merged
            }
        } else {
            additions += merged
        }
        incomingKeys += incomingBundle.aliases
    }
    val out = ArrayList<ResolvedDisplayItem>(existing.size + additions.size)
    for (i in existing.indices) {
        val item = existing[i]
        val replacement = replacementsByExistingKey[item.itemKey]
        if (replacement != null) {
            out += replacement
        } else if (item.toDisplayBundle().aliases.none { alias -> alias in incomingKeys }) {
            out += item
        }
    }
    for (i in additions.indices) out += additions[i]
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
    // Publish suppression is authority suppression. Only skip when the stored
    // item references are unchanged; visible-only suppression belongs at the
    // observer boundary so non-visible authority state can still update.
    ResolvedDisplaySurfaceRepository.HOME_SURFACE_KEY,
    ResolvedDisplaySurfaceRepository.UNIFIED_WATCHLIST_SURFACE_KEY ->
        existing.refEqualsByIndex(nextItems)
    else -> false
}

private fun shouldSuppressSurfaceEmission(
    surfaceKey: String,
    existing: List<ResolvedDisplayItem>,
    nextItems: List<ResolvedDisplayItem>
): Boolean = when (surfaceKey) {
    ResolvedDisplaySurfaceRepository.SCREENSAVER_SURFACE_KEY ->
        existing.semanticallySameScreensaverSurface(nextItems)
    ResolvedDisplaySurfaceRepository.HOME_SURFACE_KEY,
    ResolvedDisplaySurfaceRepository.UNIFIED_WATCHLIST_SURFACE_KEY ->
        existing.sameVisibleDisplaySurface(nextItems)
    else -> existing == nextItems
}

private fun List<ResolvedDisplayItem>.toAuthorityProjection(): List<ResolvedDisplayItem> {
    if (isEmpty()) return this
    val activeBundles = LinkedHashMap<String, DisplayBundle>(size)
    val aliasToCanonical = HashMap<String, String>(size * 2)
    for (i in indices) {
        val incomingBundle = this[i].toDisplayBundle()
        val existingCanonical = incomingBundle.aliases.firstNotNullOfOrNull { alias -> aliasToCanonical[alias] }
        if (existingCanonical == null) {
            activeBundles[incomingBundle.canonicalKey] = incomingBundle
            for (alias in incomingBundle.aliases) aliasToCanonical[alias] = incomingBundle.canonicalKey
        } else {
            val existingBundle = activeBundles[existingCanonical] ?: incomingBundle
            val mergedItem = if (incomingBundle.item.itemKey == existingBundle.item.itemKey) {
                existingBundle.item
            } else {
                applyNonDowngradeMerge(
                    incoming = incomingBundle.item,
                    existing = existingBundle.item,
                    traceEvents = null
                )
                    .withPreservedTrailerState(existingBundle.item)
                    .copy(itemKey = existingBundle.item.itemKey)
            }
            val mergedAliases = existingBundle.aliases + incomingBundle.aliases
            val mergedBundle = existingBundle.copy(
                aliases = mergedAliases,
                item = mergedItem
            )
            activeBundles[existingCanonical] = mergedBundle
            for (alias in mergedAliases) aliasToCanonical[alias] = existingCanonical
        }
    }
    if (activeBundles.size == size) {
        var sameInPlace = true
        var index = 0
        for (bundle in activeBundles.values) {
            if (bundle.item !== this[index]) {
                sameInPlace = false
                break
            }
            index += 1
        }
        if (sameInPlace) return this
    }
    val out = ArrayList<ResolvedDisplayItem>(activeBundles.size)
    for (bundle in activeBundles.values) out += bundle.item
    return out
}

private fun ResolvedDisplayItem.matchesAuthorityAlias(itemKey: String): Boolean =
    toDisplayBundle().aliases.contains(itemKey)

private fun ResolvedDisplayItem.toDisplayBundle(): DisplayBundle {
    val aliases = authorityAliases()
    return DisplayBundle(
        canonicalKey = canonicalAuthorityKey(aliases),
        aliases = aliases,
        item = this
    )
}

private fun ResolvedDisplayItem.canonicalAuthorityKey(aliases: Set<String>): String =
    when {
        !canonicalProvider.isNullOrBlank() && !canonicalId.isNullOrBlank() ->
            "${itemType.toApiString()}:${canonicalProvider.lowercase()}:$canonicalId"
        !stableIds.imdb.isNullOrBlank() -> "${itemType.toApiString()}:imdb:${stableIds.imdb}"
        !stableIds.tmdb.isNullOrBlank() -> "${itemType.toApiString()}:tmdb:${stableIds.tmdb}"
        !stableIds.tvdb.isNullOrBlank() -> "${itemType.toApiString()}:tvdb:${stableIds.tvdb}"
        !stableIds.simkl.isNullOrBlank() -> "${itemType.toApiString()}:simkl:${stableIds.simkl}"
        !stableIds.kitsu.isNullOrBlank() -> "${itemType.toApiString()}:kitsu:${stableIds.kitsu}"
        !stableIds.trakt.isNullOrBlank() -> "${itemType.toApiString()}:trakt:${stableIds.trakt}"
        else -> aliases.first()
    }

private fun ResolvedDisplayItem.authorityAliases(): Set<String> = buildSet {
    itemKey.trim().takeIf { it.isNotBlank() }?.let(::add)
    contentId.trim().takeIf { it.isNotBlank() }?.let { id ->
        if (id.contains(':')) add("${itemType.toApiString()}:$id")
        addTypedProviderAlias(itemType.toApiString(), id)
    }
    addStableAliases(itemType.toApiString(), stableIds)
    if (!canonicalProvider.isNullOrBlank() && !canonicalId.isNullOrBlank()) {
        addStableAlias(itemType.toApiString(), canonicalProvider, canonicalId)
    }
}.ifEmpty { setOf(itemKey) }

private fun MutableSet<String>.addTypedProviderAlias(itemType: String, contentId: String) {
    val parts = contentId.trim().split(':').filter { it.isNotBlank() }
    if (parts.size < 2) return
    val provider = parts[0].lowercase()
    val id = when {
        parts.size >= 3 && parts[1].equals("tv", ignoreCase = true) -> parts[2]
        parts.size >= 3 && parts[1].equals("movie", ignoreCase = true) -> parts[2]
        else -> parts[1]
    }
    normalizedAuthorityTypes(itemType).forEach { type -> add("$type:$provider:$id") }
}

private fun MutableSet<String>.addStableAliases(type: String, ids: ProviderIds) {
    ids.imdb?.takeIf { it.isNotBlank() }?.let { id -> addStableAlias(type, "imdb", id) }
    ids.tmdb?.takeIf { it.isNotBlank() }?.let { id -> addStableAlias(type, "tmdb", id) }
    ids.tvdb?.takeIf { it.isNotBlank() }?.let { id -> addStableAlias(type, "tvdb", id) }
    ids.trakt?.takeIf { it.isNotBlank() }?.let { id -> addStableAlias(type, "trakt", id) }
    ids.simkl?.takeIf { it.isNotBlank() }?.let { id -> addStableAlias(type, "simkl", id) }
    ids.kitsu?.takeIf { it.isNotBlank() }?.let { id -> addStableAlias(type, "kitsu", id) }
}

private fun MutableSet<String>.addStableAlias(type: String, provider: String, id: String) {
    val normalizedProvider = provider.lowercase()
    normalizedAuthorityTypes(type).forEach { normalizedType ->
        add("$normalizedType:$normalizedProvider:$id")
    }
}

private fun normalizedAuthorityTypes(type: String): Set<String> =
    when (type.lowercase()) {
        "series", "tv", "show" -> setOf("series", "tv")
        "movie" -> setOf("movie")
        else -> setOf(type.lowercase())
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

private val REPOSITORY_DISPLAY_FEATURE_SIGNATURE = DisplayFeatureSignature(
    languageTag = null,
    artworkSettingsSignature = "repository-authority",
    ratingProviderPolicy = "repository-authority",
    displayPolicyVersion = 1
)

private fun List<ResolvedDisplayItem>.sameVisibleDisplaySurface(
    other: List<ResolvedDisplayItem>
): Boolean {
    if (refEqualsByIndex(other)) return true
    if (size != other.size) return false
    for (i in indices) {
        val left = this[i]
        val right = other[i]
        if (left.itemKey != right.itemKey) return false
        if (
            left.visibleDisplaySignature(REPOSITORY_DISPLAY_FEATURE_SIGNATURE) !=
            right.visibleDisplaySignature(REPOSITORY_DISPLAY_FEATURE_SIGNATURE)
        ) {
            return false
        }
    }
    return true
}

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
    val stableSignatureChanged = incoming.displayLanguageTag != existing.displayLanguageTag ||
        incoming.preferredArtworkProviders != existing.preferredArtworkProviders ||
        incoming.rating?.source != existing.rating?.source
    val canonicalContentChanged = incoming.hydrationState != HydrationState.PREVIEW_ONLY &&
        existing.hydrationState != HydrationState.PREVIEW_ONLY &&
        incoming.updatedAtMs != existing.updatedAtMs
    val allowSameRankReplacement = stableSignatureChanged || canonicalContentChanged

    // Apply preferred-provider tie-break for artwork slots only.
    // Reducer stays pure: settings/preferences are consulted only at this
    // merge boundary, not inside pickHigherRanked. (Bug A — Task 12)
    val itemKey = incoming.itemKey
    val mergedSlots = reducerMerged.copy(
        title = stableSameRankSlot(reducerMerged.title, incomingSlots.title, existingSlots.title, allowSameRankReplacement),
        originalTitle = stableSameRankSlot(reducerMerged.originalTitle, incomingSlots.originalTitle, existingSlots.originalTitle, allowSameRankReplacement),
        overview = stableSameRankSlot(reducerMerged.overview, incomingSlots.overview, existingSlots.overview, allowSameRankReplacement),
        genres = stableSameRankSlot(reducerMerged.genres, incomingSlots.genres, existingSlots.genres, allowSameRankReplacement),
        releaseInfo = stableSameRankSlot(reducerMerged.releaseInfo, incomingSlots.releaseInfo, existingSlots.releaseInfo, allowSameRankReplacement),
        runtime = stableSameRankSlot(reducerMerged.runtime, incomingSlots.runtime, existingSlots.runtime, allowSameRankReplacement),
        rating = stableSameRankSlot(reducerMerged.rating, incomingSlots.rating, existingSlots.rating, allowSameRankReplacement),
        poster = preferredAwareSlot(incomingSlots.poster, existingSlots.poster, incoming.preferredArtworkProviders[ArtworkType.POSTER], itemKey, "POSTER", traceEvents),
        backdrop = preferredAwareSlot(incomingSlots.backdrop, existingSlots.backdrop, incoming.preferredArtworkProviders[ArtworkType.BACKDROP], itemKey, "BACKDROP", traceEvents),
        logo = preferredAwareSlot(incomingSlots.logo, existingSlots.logo, incoming.preferredArtworkProviders[ArtworkType.LOGO], itemKey, "LOGO", traceEvents),
        thumbnail = preferredAwareSlot(incomingSlots.thumbnail, existingSlots.thumbnail, incoming.preferredArtworkProviders[ArtworkType.THUMBNAIL], itemKey, "THUMBNAIL", traceEvents),
        posterProviderTag = stableSameRankSlot(reducerMerged.posterProviderTag, incomingSlots.posterProviderTag, existingSlots.posterProviderTag, allowSameRankReplacement)
    )

    // Strengthen-only ProviderIds union. The cross-id enricher publishes the
    // same item with progressively richer IDs (TMDB → +IMDB → +TVDB etc.) as
    // Room resolves them. Slots can stay identical across these emissions, so
    // the post-first-paint invariant ("hydrated items carry IMDB + native id")
    // requires this merge to be ID-aware, not slot-only.
    val mergedStableIds = strengthenProviderIds(existing.stableIds, incoming.stableIds)
    val mergedImdbId = incoming.imdbId ?: existing.imdbId
    val mergedCanonicalProvider = incoming.canonicalProvider ?: existing.canonicalProvider
    val mergedCanonicalId = incoming.canonicalId ?: existing.canonicalId

    if (mergedSlots == existingSlots &&
        incoming.slotDerivedFieldsMatch(existing) &&
        mergedStableIds == existing.stableIds &&
        mergedImdbId == existing.imdbId &&
        mergedCanonicalProvider == existing.canonicalProvider &&
        mergedCanonicalId == existing.canonicalId
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
        imdbId = mergedImdbId,
        canonicalProvider = mergedCanonicalProvider,
        canonicalId = mergedCanonicalId
    )
}

private fun <T> stableSameRankSlot(
    selected: ResolvedSlot<T>,
    incoming: ResolvedSlot<T>,
    existing: ResolvedSlot<T>,
    allowSameRankReplacement: Boolean
): ResolvedSlot<T> {
    if (allowSameRankReplacement) return selected
    if (incoming.rank == existing.rank && incoming.value != existing.value) return existing
    return selected
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
        incomingMatches && existingMatches  -> {
            val incomingEvidence = incoming.preferredProviderEvidence(preferred)
            val existingEvidence = existing.preferredProviderEvidence(preferred)
            when {
                incomingEvidence > existingEvidence -> incoming
                existingEvidence > incomingEvidence -> existing
                else -> incoming   // both preferred with equivalent evidence → newer wins
            }
        }
        else                                -> existing   // neither preferred → existing stays
    }
}

private fun ResolvedSlot<ArtworkDisplayRef>.preferredProviderEvidence(
    preferred: ArtworkProviderId
): Int {
    if (provider != preferred.key) return 0
    return when (val ref = value) {
        is ArtworkDisplayRef.RuntimeAsset -> {
            if (ref.selectedProvider?.key == preferred.key) PREFERRED_PROVIDER_DECISION_EVIDENCE else PREFERRED_PROVIDER_LABEL_EVIDENCE
        }
        is ArtworkDisplayRef.LegacyString -> {
            val value = ref.value
            when {
                value.isDecisionUriFor(preferred) -> PREFERRED_PROVIDER_DECISION_EVIDENCE
                value.startsWith(ARTWORK_DECISION_URI_PREFIX) -> PREFERRED_PROVIDER_DECISION_EVIDENCE
                else -> PREFERRED_PROVIDER_LABEL_EVIDENCE
            }
        }
        is ArtworkDisplayRef.Placeholder, null -> PREFERRED_PROVIDER_LABEL_EVIDENCE
    }
}

private fun String.isDecisionUriFor(preferred: ArtworkProviderId): Boolean =
    startsWith(ARTWORK_DECISION_URI_PREFIX) && contains("provider:${preferred.key}")

private const val ARTWORK_DECISION_URI_PREFIX = "nexio-artwork://decision/"
private const val PREFERRED_PROVIDER_LABEL_EVIDENCE = 1
private const val PREFERRED_PROVIDER_DECISION_EVIDENCE = 2

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
        val bundle = item.toDisplayBundle()
        for (alias in bundle.aliases) existingByKey[alias] = item
    }
    val out = ArrayList<ResolvedDisplayItem>(incoming.size)
    val outIndexByExistingKey = HashMap<String, Int>(incoming.size)
    for (i in incoming.indices) {
        val item = incoming[i]
        val existingForKey = item.toDisplayBundle().aliases.firstNotNullOfOrNull { alias -> existingByKey[alias] }
        val outKey = existingForKey?.itemKey
        val priorOutIndex = outKey?.let { outIndexByExistingKey[it] }
        val mergeBase = if (priorOutIndex != null) out[priorOutIndex] else existingForKey
        val mergeInput = if (
            priorOutIndex != null &&
            mergeBase != null &&
            item.authorityStrengthScore() < mergeBase.authorityStrengthScore()
        ) {
            mergeBase
        } else {
            item
        }
        val rankProtected = applyNonDowngradeMerge(mergeInput, mergeBase, traceEvents)
        val merged = rankProtected.withPreservedTrailerState(mergeBase)
        val projected = if (existingForKey != null && merged.itemKey != existingForKey.itemKey) {
            merged.copy(itemKey = existingForKey.itemKey)
        } else {
            merged
        }
        if (priorOutIndex != null) {
            out[priorOutIndex] = projected
        } else {
            if (outKey != null) outIndexByExistingKey[outKey] = out.size
            out += projected
        }
    }
    return out
}

private fun ResolvedDisplayItem.authorityStrengthScore(): Int {
    var score = 0
    if (!imdbId.isNullOrBlank()) score += 2
    if (!canonicalProvider.isNullOrBlank() && !canonicalId.isNullOrBlank()) score += 2
    if (!stableIds.imdb.isNullOrBlank()) score += 2
    if (!stableIds.tmdb.isNullOrBlank()) score += 1
    if (!stableIds.tvdb.isNullOrBlank()) score += 1
    if (!stableIds.trakt.isNullOrBlank()) score += 1
    if (!stableIds.simkl.isNullOrBlank()) score += 1
    if (!stableIds.kitsu.isNullOrBlank()) score += 1
    if (!stableIds.slug.isNullOrBlank()) score += 1
    if (!stableIds.mal.isNullOrBlank()) score += 1
    if (!stableIds.anilist.isNullOrBlank()) score += 1
    if (!stableIds.anidb.isNullOrBlank()) score += 1
    score += when (hydrationState) {
        HydrationState.CANONICAL_READY,
        HydrationState.STALE_READY -> 4
        HydrationState.IDENTITY_READY,
        HydrationState.HYDRATING -> 2
        HydrationState.FAILED_USING_PREVIEW -> 1
        HydrationState.PREVIEW_ONLY -> 0
    }
    return score
}
