package com.nexio.tv.ui.screens.home

import com.nexio.tv.core.artwork.ArtworkBundle
import com.nexio.tv.core.artwork.emptyOrNull
import com.nexio.tv.core.artwork.enforceArtworkTypeBoundaries
import com.nexio.tv.core.artwork.toLegacyArtworkString
import com.nexio.tv.domain.model.CatalogRow
import com.nexio.tv.domain.model.HydratedHomeOverlay
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.ResolvedDisplayFieldSlots
import com.nexio.tv.domain.model.homeDisplayItemKey
import com.nexio.tv.domain.model.hydratedHomeDisplayHash
import com.nexio.tv.domain.model.toHomeDisplayMetadata

internal fun CatalogRow.applyHydratedHomeOverlays(
    overlaysByItemKey: Map<String, HydratedHomeOverlay>
): CatalogRow {
    if (overlaysByItemKey.isEmpty()) return this
    val nowMs = System.currentTimeMillis()

    var changed = false
    val updatedItems = items.map { item ->
        val overlay = item.overlayFromMap(overlaysByItemKey) ?: return@map item
        val firstPaintSlots = item.toFirstPaintSlots(nowMs)
        val overlaySlots = overlay.toResolvedSlots(nowMs, isStale = overlay.isStale(nowMs))
        val merged = HomeRailProjectionReducer.reduce(
            firstPaint = firstPaintSlots,
            overlay = overlaySlots,
            existing = null,
            profile = null
        )
        val updated = item.applyMergedSlots(merged, overlay)
        if (updated != item) changed = true
        updated
    }

    return if (changed) copy(items = updatedItems) else this
}

/**
 * Down-projects a reduced [ResolvedDisplayFieldSlots] back onto a [MetaPreview]
 * for legacy consumers that still read the mutable row. Once Plan B (UI
 * consumption migration) lands and consumers move to [ResolvedDisplayItem],
 * this function and its callers can be deleted.
 */
private fun MetaPreview.applyMergedSlots(
    slots: ResolvedDisplayFieldSlots,
    overlay: HydratedHomeOverlay
): MetaPreview {
    val posterRef = slots.poster.value
    val backdropRef = slots.backdrop.value
    val logoRef = slots.logo.value
    val thumbnailRef = slots.thumbnail.value
    val ratingValue = slots.rating.value?.value?.toFloat()
    val ratingSource = slots.rating.value?.source
    // tomatoesRating is not modelled in ResolvedDisplayFieldSlots; apply directly from overlay.
    val appliedTomatoes = overlay.fields.tomatoesRating ?: tomatoesRating
    return copy(
        name = slots.title.value ?: name,
        description = slots.overview.value ?: description,
        genres = slots.genres.value ?: genres,
        releaseInfo = slots.releaseInfo.value ?: releaseInfo,
        runtime = slots.runtime.value ?: runtime,
        imdbRating = ratingValue ?: imdbRating,
        ratingSource = ratingSource ?: this.ratingSource,
        tomatoesRating = appliedTomatoes,
        poster = posterRef.toLegacyArtworkString() ?: poster,
        background = backdropRef.toLegacyArtworkString() ?: background,
        logo = logoRef.toLegacyArtworkString() ?: logo,
        posterProviderTag = slots.posterProviderTag.value ?: posterProviderTag,
        artwork = ArtworkBundle(
            poster = posterRef,
            backdrop = backdropRef,
            logo = logoRef,
            thumbnail = thumbnailRef
        ).enforceArtworkTypeBoundaries().emptyOrNull()
    )
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
