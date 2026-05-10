package com.nexio.tv.ui.screens.home

import com.nexio.tv.domain.model.HydratedHomeOverlay
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.homeDisplayItemKey
import com.nexio.tv.domain.model.hydratedHomeDisplayHash
import com.nexio.tv.domain.model.toHomeDisplayMetadata

// Phase 4 — the compose-time overlay-application functions
// (`CatalogRow.applyHydratedHomeOverlays`, `List<CatalogRow>.applyHydratedHomeOverlays`,
// `rowsForResolvedDisplaySurface`) were deleted after the Phase 3.6.5
// producer flip (commit `63aa16286`) made the producer a passthrough.
// The remaining helpers below are overlay key/hash utilities still used by
// downstream consumers (HomeResolvedDisplayMapper.overlayFromMap,
// HomeViewModelPresentationPipeline.homeOverlayItemKey,
// HomeHydrationCoordinator.displayHashForHomeOverlay).

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

internal fun MetaPreview.homeOverlayItemKey(): String = homeDisplayItemKey(apiType, id)

internal fun MetaPreview.displayHashForHomeOverlay(): String =
    toHomeDisplayMetadata().hydratedHomeDisplayHash()
