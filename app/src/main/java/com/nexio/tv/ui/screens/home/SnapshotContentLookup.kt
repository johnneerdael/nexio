package com.nexio.tv.ui.screens.home

import com.nexio.tv.data.local.HomeCatalogSnapshotStore.Snapshot
import com.nexio.tv.domain.model.CatalogRow
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.Rail

/**
 * Plan B Task 6f — reconstruct denormalized snapshot views (catalogRows,
 * fullCatalogRows, heroItems) from `(rails, typed surface items)`.
 *
 * Used by the snapshot apply pipeline, filter helpers, and search corpus
 * while the legacy reader sites are being migrated to consume [Rail] +
 * typed surface directly. Task 6f.5 drops the legacy fields once every
 * reader uses the structure-only [Rail] / [com.nexio.tv.domain.model.RailItemKey]
 * shape and looks up content via the typed-items map.
 *
 * Reference-stability: callers should pass `typedItemsByKey` as a
 * `remember`-stable map or compute it once per pipeline pass.
 */
internal fun Snapshot.reconstructHeroItems(
    typedItemsByKey: Map<String, MetaPreview>
): List<MetaPreview> {
    val keys = heroItemKeys
    if (keys.isEmpty()) return heroItems
    val out = ArrayList<MetaPreview>(keys.size)
    for (i in keys.indices) {
        typedItemsByKey[keys[i].key]?.let(out::add)
    }
    return out
}

/**
 * Resolve a [Rail]'s itemKeys to MetaPreview content via the typed surface.
 * Returns a CatalogRow-shaped view so legacy filter helpers and the search
 * corpus can operate unchanged during the migration window. Returns null
 * if no items resolve (caller treats as an absent/empty row).
 */
internal fun Rail.toCatalogRowOrNull(
    typedItemsByKey: Map<String, MetaPreview>
): CatalogRow? {
    val keys = items
    if (keys.isEmpty()) return null
    val resolved = ArrayList<MetaPreview>(keys.size)
    for (i in keys.indices) {
        typedItemsByKey[keys[i].key]?.let(resolved::add)
    }
    if (resolved.isEmpty()) return null
    return CatalogRow(
        addonId = addonId,
        addonName = addonName,
        addonBaseUrl = addonBaseUrl,
        catalogId = catalogId,
        catalogName = catalogName,
        type = type,
        rawType = rawType,
        items = resolved,
        isLoading = isLoading,
        hasMore = hasMore,
        currentPage = currentPage,
        supportsSkip = supportsSkip,
        skipStep = skipStep
    )
}
