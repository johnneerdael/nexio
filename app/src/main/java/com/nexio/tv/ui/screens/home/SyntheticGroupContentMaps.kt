package com.nexio.tv.ui.screens.home

import com.nexio.tv.domain.model.CatalogRow

/**
 * Pure-function builder for the three by-orderKey maps the pipeline needs alongside
 * `materializeHomeRows`. Replaces the pre-rewrite "concatenate persisted + live, drop
 * duplicates, derive default order from synthetic group iteration" code with a
 * by-key construction whose iteration order does not influence Modern Home row order.
 */
internal data class SyntheticGroupContentMaps(
    val syntheticRowsByKey: Map<String, List<CatalogRow>>,
    val existingRowsByOrderKey: Map<String, CatalogRow>,
    val rowOrderKeyByGlobalKey: Map<String, String>,
)

internal fun buildSyntheticGroupContentMaps(
    persistedSyntheticGroupsByKey: Map<String, List<CatalogRow>>,
    liveSyntheticGroupsByKey: Map<String, List<CatalogRow>>,
    rawRowsByOrderKey: Map<String, CatalogRow>,
    homeCatalogGlobalKey: (CatalogRow) -> String,
): SyntheticGroupContentMaps {
    // Live synthetic content takes precedence over persisted synthetic content,
    // matching the materializer's priority — the by-key merge is "live wins, persisted fills".
    val syntheticRowsByKey: Map<String, List<CatalogRow>> = buildMap {
        putAll(persistedSyntheticGroupsByKey)
        putAll(liveSyntheticGroupsByKey) // overwrites persisted entries for the same orderKey
    }
    val existingRowsByOrderKey: Map<String, CatalogRow> = buildMap {
        putAll(rawRowsByOrderKey)
        syntheticRowsByKey.forEach { (orderKey, rows) ->
            rows.firstOrNull()?.let { put(orderKey, it) }
        }
    }
    val rowOrderKeyByGlobalKey: Map<String, String> = buildMap {
        rawRowsByOrderKey.keys.forEach { globalKey -> put(globalKey, globalKey) }
        syntheticRowsByKey.forEach { (orderKey, rows) ->
            rows.forEach { row -> put(homeCatalogGlobalKey(row), orderKey) }
        }
    }
    return SyntheticGroupContentMaps(
        syntheticRowsByKey = syntheticRowsByKey,
        existingRowsByOrderKey = existingRowsByOrderKey,
        rowOrderKeyByGlobalKey = rowOrderKeyByGlobalKey,
    )
}
