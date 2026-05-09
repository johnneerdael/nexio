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

    var changed = false
    val updatedItems = items.map { item ->
        val overlay = item.overlayFromMap(overlaysByItemKey) ?: return@map item
        val updated = overlay.fields.applyTo(item)
        if (updated != item) changed = true
        updated
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

    var changed = false
    val updatedRows = map { row ->
        val updated = row.applyHydratedHomeOverlays(overlaysByItemKey)
        if (updated !== row) changed = true
        updated
    }

    return if (changed) updatedRows else this
}

internal fun rowsForResolvedDisplaySurface(
    rows: List<CatalogRow>,
    overlaysByItemKey: Map<String, HydratedHomeOverlay>
): List<CatalogRow> = rows.applyHydratedHomeOverlays(overlaysByItemKey)

internal fun MetaPreview.homeOverlayItemKey(): String = homeDisplayItemKey(apiType, id)

internal fun MetaPreview.displayHashForHomeOverlay(): String =
    toHomeDisplayMetadata().hydratedHomeDisplayHash()
