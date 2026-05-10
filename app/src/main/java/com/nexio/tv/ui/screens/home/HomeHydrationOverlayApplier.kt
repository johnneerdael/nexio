package com.nexio.tv.ui.screens.home

import com.nexio.tv.domain.model.CatalogRow
import com.nexio.tv.domain.model.HydratedHomeOverlay
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.applyTo
import com.nexio.tv.domain.model.homeDisplayItemKey
import com.nexio.tv.domain.model.hydratedHomeDisplayHash
import com.nexio.tv.domain.model.toHomeDisplayMetadata

internal fun CatalogRow.applyHydratedHomeOverlays(
    overlaysByItemKey: Map<String, HydratedHomeOverlay>
): CatalogRow {
    if (overlaysByItemKey.isEmpty()) return this

    // Steady-state Modern Home has tens of overlays but thousands of inventory
    // CatalogRows; most rows have no items with a matching overlay. The inner
    // `items.map { ... }` allocates an N-item ArrayList per row regardless of
    // whether any change happens — at 10k rows × 20 items that is 200k pointless
    // backing allocations per pipeline emission. Pre-scan with an indexed-for
    // loop and bail out without allocating when no item in this row has an
    // overlay (the common case for background-catalog rails). Heap-dump impact
    // shows up in the dominator tree as `_fullCatalogRows` retaining 17.47 MiB.
    var anyOverlay = false
    for (i in items.indices) {
        if (items[i].overlayFromMap(overlaysByItemKey) != null) { anyOverlay = true; break }
    }
    if (!anyOverlay) return this

    var changed = false
    val updatedItems = ArrayList<MetaPreview>(items.size)
    for (i in items.indices) {
        val item = items[i]
        val overlay = item.overlayFromMap(overlaysByItemKey)
        if (overlay == null) {
            updatedItems += item
        } else {
            val updated = overlay.fields.applyTo(item)
            // Content equality, not reference equality — `applyTo` may return a
            // fresh data-class instance with identical fields when the overlay
            // value is content-equal to the existing item. Treat that as "no
            // change" so the row instance can be reused upstream.
            if (updated != item) changed = true
            updatedItems += updated
        }
    }
    return if (changed) copy(items = updatedItems) else this
}

/**
 * Looks up the hydrated overlay for this preview, probing the row's own
 * [homeOverlayItemKey] first and then falling through to the same alias set the
 * read scope used to subscribe ([HomeArtworkOverlayKeys.aliasesFor]). Without
 * this fall-through, overlays stored under a canonical alias (e.g.,
 * `series:tvdb:355567`) would never reach a row keyed by a different provider
 * (e.g., `series:trakt:171028`).
 */
internal fun MetaPreview.overlayFromMap(
    overlaysByItemKey: Map<String, HydratedHomeOverlay>
): HydratedHomeOverlay? {
    if (overlaysByItemKey.isEmpty()) return null
    val rowKey = homeOverlayItemKey()
    overlaysByItemKey[rowKey]?.let { return it }
    val aliases = HomeArtworkOverlayKeys.aliasesFor(
        rowItemKey = rowKey,
        contentId = id,
        itemType = apiType,
        providerIds = firstPaintStableIds,
        canonicalProvider = null,
        canonicalId = null
    )
    return aliases.asSequence()
        .filter { it != rowKey }
        .mapNotNull { overlaysByItemKey[it] }
        .firstOrNull()
}

internal fun List<CatalogRow>.applyHydratedHomeOverlays(
    overlaysByItemKey: Map<String, HydratedHomeOverlay>
): List<CatalogRow> {
    if (overlaysByItemKey.isEmpty()) return this

    // Same allocation-skip pattern as the per-row variant. Indexed-for + lazy
    // ArrayList allocation only when at least one row actually changes.
    var firstChangedIndex = -1
    val updatedAtChange = ArrayList<CatalogRow>(0)
    for (i in indices) {
        val row = this[i]
        val updated = row.applyHydratedHomeOverlays(overlaysByItemKey)
        if (updated !== row) {
            if (firstChangedIndex < 0) {
                firstChangedIndex = i
                updatedAtChange.ensureCapacity(size)
                for (j in 0 until i) updatedAtChange += this[j]
            }
            updatedAtChange += updated
        } else if (firstChangedIndex >= 0) {
            updatedAtChange += updated
        }
    }
    return if (firstChangedIndex >= 0) updatedAtChange else this
}

internal fun rowsForResolvedDisplaySurface(
    rows: List<CatalogRow>,
    overlaysByItemKey: Map<String, HydratedHomeOverlay>
): List<CatalogRow> = rows.applyHydratedHomeOverlays(overlaysByItemKey)

internal fun MetaPreview.homeOverlayItemKey(): String = homeDisplayItemKey(apiType, id)

internal fun MetaPreview.displayHashForHomeOverlay(): String =
    toHomeDisplayMetadata().hydratedHomeDisplayHash()
