package com.nexio.tv.ui.screens.home

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable

/**
 * Typed presentation state for Grid Home, derived from the
 * [ResolvedDisplaySurfaceRepository] home surface. Mirrors
 * [ClassicHomePresentationState] for Grid.
 *
 * Plan B Task 5b — Grid Home's resolved-item lookup is a single reference-stable
 * presentation StateFlow instead of [HomeUiState.resolvedRailRows] re-walked in
 * Compose on every emission. Reference identity is preserved across emissions
 * when content is unchanged, so Compose stability skipping and the consumer's
 * `===` guard short-circuit unchanged updates (CLAUDE.md rule #5).
 *
 * Unlike Classic, Grid renders from [HomeViewModel.displayGridItems] (the layout
 * shape built upstream by `buildGridItemsFromRowsPipeline`) rather than the
 * `CatalogRow` list, so this state intentionally exposes only the resolved-item
 * lookup needed by [GridHomeContent] to overlay typed art onto each
 * [GridItem.Content] card. Grid items were migrated off [HomeUiState] in Plan B
 * Task 5f to keep the hot list off the Compose state-record chain (rule #2).
 */
@Immutable
data class GridHomePresentationState(
    /**
     * Surface-level resolved-item lookup, keyed by [ModernHomeRowItem.itemKey].
     * Consumed by [GridHomeContent] to overlay resolved-display art onto each
     * [GridItem.Content] card via [com.nexio.tv.ui.components.overlayResolvedDisplay].
     */
    val resolvedItemsByItemKey: Map<String, ModernHomeRowItem> = emptyMap()
)

@Immutable
internal data class GridHomePresentationInput(
    val resolvedRailRows: List<ResolvedRailRow>
)

@Stable
internal class GridHomePresentationBuildCache {
    /**
     * Last presentation built. Used to short-circuit when content-equal builds
     * produce referentially-identical projections (the outer-map reuse path),
     * so the StateFlow emits the same instance.
     */
    var lastPresentation: GridHomePresentationState? = null
}

/**
 * Builds the [GridHomePresentationState] for the next emission. Mirrors
 * [buildClassicHomePresentation]:
 *  - Walks [ResolvedRailRow]s and populates the surface-level resolved-item
 *    lookup map keyed by [ModernHomeRowItem.itemKey].
 *  - Uses indexed-for loops (CLAUDE.md rule #4 — this is callable from a
 *    suspend context via [observeGridHomePresentationPipeline]; even though
 *    this body itself does not suspend, the rule's spirit is to avoid
 *    iterator allocations on the hot path).
 *
 * Reference stability: the OUTER map reference is preserved when every entry
 * is reference-identical to the previous emission's entry — mirrors the
 * [buildClassicHomePresentation] outer-list memoization pattern.
 */
internal fun buildGridHomePresentation(
    input: GridHomePresentationInput,
    cache: GridHomePresentationBuildCache
): GridHomePresentationState {
    val rails = input.resolvedRailRows
    val resolvedItemsByItemKey = HashMap<String, ModernHomeRowItem>()

    for (i in rails.indices) {
        val rail = rails[i]
        val items = rail.items
        for (j in items.indices) {
            val resolved = items[j]
            resolvedItemsByItemKey[resolved.itemKey] = resolved
        }
    }

    // Outer-map reference stabilisation: when every entry is `===` to the
    // previous emission's entry, return the previous map instance so the
    // downstream `===` short-circuit and Compose stability skipping fire.
    val previous = cache.lastPresentation
    val stableLookup: Map<String, ModernHomeRowItem> = if (
        previous != null &&
        previous.resolvedItemsByItemKey.size == resolvedItemsByItemKey.size &&
        previous.resolvedItemsByItemKey.allReferenceEqualTo(resolvedItemsByItemKey)
    ) {
        previous.resolvedItemsByItemKey
    } else {
        resolvedItemsByItemKey
    }

    val nextState = if (
        previous != null &&
        stableLookup === previous.resolvedItemsByItemKey
    ) {
        previous
    } else {
        GridHomePresentationState(
            resolvedItemsByItemKey = stableLookup
        )
    }
    cache.lastPresentation = nextState
    return nextState
}

private fun Map<String, ModernHomeRowItem>.allReferenceEqualTo(
    other: Map<String, ModernHomeRowItem>
): Boolean {
    if (size != other.size) return false
    for ((k, v) in this) {
        val ov = other[k] ?: return false
        if (ov !== v) return false
    }
    return true
}
