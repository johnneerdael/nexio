package com.nexio.tv.ui.screens.home

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import com.nexio.tv.domain.model.CatalogRow

/**
 * Typed presentation state for Classic Home, derived from the
 * [ResolvedDisplaySurfaceRepository] home surface joined with the rendering-shape
 * [CatalogRow] list. Mirrors [ModernHomePresentationState] for Classic.
 *
 * Plan B Task 5a — Classic Home's input is a single reference-stable presentation
 * StateFlow instead of [HomeViewModel.displayCatalogRows] + [HomeUiState.resolvedRailRows]
 * recomputed in Compose. Reference identity is preserved across emissions when content
 * is unchanged, so Compose stability skipping and the consumer's `===` guard short-circuit
 * unchanged updates (CLAUDE.md rule #5).
 *
 * The legacy [HomeViewModel._displayCatalogRows] flow remains alive for Grid and other
 * consumers; Plan B Task 5e retires it after Task 5b migrates Grid.
 */
@Immutable
data class ClassicHomePresentationState(
    /**
     * Ordered, deduped list of rails to render. Each entry pins a [ResolvedRailRow]
     * (rendering authority — drives focus item count and resolved-item lookup) with
     * its parallel [CatalogRow] (carries addonId/apiType/catalogId/title context for
     * [com.nexio.tv.ui.components.CatalogRowSection]).
     */
    val visibleRows: List<ClassicHomeRailEntry> = emptyList(),
    /**
     * Surface-level resolved-item lookup, keyed by [ModernHomeRowItem.itemKey].
     * Consumed by `CatalogRowSection.resolvedItemsByItemKey` for typed render.
     */
    val resolvedItemsByItemKey: Map<String, ModernHomeRowItem> = emptyMap(),
    /**
     * Set of `"${addonId}_${apiType}_${catalogId}"` keys for the currently visible
     * rails. Used by Compose-side cleanup of per-row caches.
     */
    val visibleCatalogKeys: Set<String> = emptySet()
)

@Immutable
data class ClassicHomeRailEntry(
    val railKey: String,
    val resolvedRail: ResolvedRailRow,
    val catalogRow: CatalogRow
)

@Immutable
internal data class ClassicHomePresentationInput(
    val catalogRows: List<CatalogRow>,
    val resolvedRailRows: List<ResolvedRailRow>
)

/**
 * Per-rail build cache entry, mirroring [ModernCatalogRowBuildCacheEntry]. Reuse of
 * a cached [ClassicHomeRailEntry] is gated on identity of both the parallel
 * [CatalogRow] and the [ResolvedRailRow] — both upstream sources are reference-stable
 * when content is unchanged (CatalogRowMemo + ResolvedDisplayProjectionCache).
 */
internal data class ClassicCatalogRowBuildCacheEntry(
    val source: CatalogRow,
    val resolvedRail: ResolvedRailRow,
    val entry: ClassicHomeRailEntry
)

@Stable
internal class ClassicHomePresentationBuildCache {
    val railEntries = mutableMapOf<String, ClassicCatalogRowBuildCacheEntry>()
    /**
     * Last presentation built. Used to short-circuit when content-equal builds produce
     * referentially-identical projections (the outer-list reuse path), so the
     * StateFlow emits the same instance.
     */
    var lastPresentation: ClassicHomePresentationState? = null
}

/**
 * Builds the [ClassicHomePresentationState] for the next emission. Mirrors
 * [buildModernHomePresentation]:
 *  - Iterates [ResolvedRailRow]s (rendering authority) and looks up the parallel
 *    [CatalogRow] by `catalogId` from the input list. Rails without a parallel
 *    CatalogRow are dropped (transient race during snapshot apply).
 *  - Dedupes by `"${addonId}_${apiType}_${catalogId}"` defensively — upstream
 *    pipelines occasionally produce two CatalogRow entries with the same triple
 *    (synthetic group + raw rail with overlapping config).
 *  - Reuses cached [ClassicHomeRailEntry] instances when both the source [CatalogRow]
 *    and [ResolvedRailRow] are reference-identical to the prior build (content-stable
 *    via upstream memoization).
 *  - Builds the surface-level [resolvedItemsByItemKey] map in a single pass.
 *
 * Reference stability: the OUTER list reference is preserved when every entry is
 * reference-identical to the previous emission (mirroring
 * [com.nexio.tv.ui.screens.home.ResolvedDisplayProjectionCache.internRailsList]).
 */
internal fun buildClassicHomePresentation(
    input: ClassicHomePresentationInput,
    cache: ClassicHomePresentationBuildCache
): ClassicHomePresentationState {
    val catalogRowByCatalogId = HashMap<String, CatalogRow>(input.catalogRows.size)
    for (i in input.catalogRows.indices) {
        val row = input.catalogRows[i]
        catalogRowByCatalogId[row.catalogId] = row
    }

    val rails = input.resolvedRailRows
    val activeKeys = LinkedHashSet<String>(rails.size)
    val visibleRows = ArrayList<ClassicHomeRailEntry>(rails.size)
    val resolvedItemsByItemKey = HashMap<String, ModernHomeRowItem>()

    for (i in rails.indices) {
        val resolvedRail = rails[i]
        if (resolvedRail.items.isEmpty()) continue
        val sourceRow = catalogRowByCatalogId[resolvedRail.catalogId] ?: continue
        if (sourceRow.items.isEmpty()) continue
        val railKey = "${sourceRow.addonId}_${sourceRow.apiType}_${sourceRow.catalogId}"
        if (!activeKeys.add(railKey)) continue

        // Populate the surface-level lookup unconditionally so the map is correct
        // even when the rail entry below is reused from cache.
        val items = resolvedRail.items
        for (j in items.indices) {
            val resolved = items[j]
            resolvedItemsByItemKey[resolved.itemKey] = resolved
        }

        val cached = cache.railEntries[railKey]
        val entry = if (
            cached != null &&
            cached.source === sourceRow &&
            cached.resolvedRail === resolvedRail
        ) {
            cached.entry
        } else {
            ClassicHomeRailEntry(
                railKey = railKey,
                resolvedRail = resolvedRail,
                catalogRow = sourceRow
            )
        }

        cache.railEntries[railKey] = ClassicCatalogRowBuildCacheEntry(
            source = sourceRow,
            resolvedRail = resolvedRail,
            entry = entry
        )
        visibleRows += entry
    }
    cache.railEntries.keys.retainAll(activeKeys)

    // Outer-list reference stabilisation: when every entry is `===` to the
    // previous emission's entry list, return the previous list instance so
    // downstream `===` short-circuits and Compose stability skipping fire.
    val previous = cache.lastPresentation
    val stableVisibleRows: List<ClassicHomeRailEntry> = if (
        previous != null &&
        previous.visibleRows.size == visibleRows.size &&
        previous.visibleRows.refEqualsByIndex(visibleRows)
    ) {
        previous.visibleRows
    } else {
        visibleRows
    }

    val stableLookup: Map<String, ModernHomeRowItem> = if (
        previous != null &&
        previous.resolvedItemsByItemKey.size == resolvedItemsByItemKey.size &&
        previous.resolvedItemsByItemKey.allReferenceEqualTo(resolvedItemsByItemKey)
    ) {
        previous.resolvedItemsByItemKey
    } else {
        resolvedItemsByItemKey
    }

    val stableKeys: Set<String> = if (
        previous != null &&
        previous.visibleCatalogKeys == activeKeys
    ) {
        previous.visibleCatalogKeys
    } else {
        activeKeys
    }

    val nextState = if (
        previous != null &&
        stableVisibleRows === previous.visibleRows &&
        stableLookup === previous.resolvedItemsByItemKey &&
        stableKeys === previous.visibleCatalogKeys
    ) {
        previous
    } else {
        ClassicHomePresentationState(
            visibleRows = stableVisibleRows,
            resolvedItemsByItemKey = stableLookup,
            visibleCatalogKeys = stableKeys
        )
    }
    cache.lastPresentation = nextState
    return nextState
}

private fun List<ClassicHomeRailEntry>.refEqualsByIndex(other: List<ClassicHomeRailEntry>): Boolean {
    if (size != other.size) return false
    for (i in indices) {
        if (this[i] !== other[i]) return false
    }
    return true
}

private fun Map<String, ModernHomeRowItem>.allReferenceEqualTo(other: Map<String, ModernHomeRowItem>): Boolean {
    if (size != other.size) return false
    for ((k, v) in this) {
        val ov = other[k] ?: return false
        if (ov !== v) return false
    }
    return true
}
