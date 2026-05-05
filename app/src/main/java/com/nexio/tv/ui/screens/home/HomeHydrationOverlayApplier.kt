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
        val overlay = overlaysByItemKey[item.homeOverlayItemKey()] ?: return@map item
        val updated = overlay.fields.applyTo(item)
        if (updated != item) changed = true
        updated
    }

    return if (changed) copy(items = updatedItems) else this
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
