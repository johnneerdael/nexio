package com.nexio.tv.ui.screens.home

import com.nexio.tv.domain.model.CatalogRow
import com.nexio.tv.ui.screens.home.order.EffectiveHomeRailOrder
import com.nexio.tv.ui.screens.home.order.HomeRailKey
import com.nexio.tv.ui.screens.home.order.RailPublishPolicy

/**
 * Pure function that materializes Modern Home rows from the authoritative effective order
 * and per-source content maps. Row priority for each visible key:
 *
 * 1. `liveSyntheticGroupsByKey` — current live multi-row groups (e.g., trakt up next, simkl trending).
 * 2. `rawRowsByKey` — single-row catalog snapshot rows.
 * 3. `persistedSyntheticGroupsByKey` — persisted-cache fallback when no live group is available.
 * 4. `pendingRowsByKey` — loading placeholder rows for definitions whose content hasn't arrived.
 *
 * If a visible key has no content in any of the four sources, it produces no row (the rail is
 * effectively skipped this tick). The output order matches `effectiveOrder.visibleKeys` exactly.
 *
 * The `publishPolicyByKey` parameter is optional. When non-empty, pending rows are
 * only published if their key's policy is `PUBLISH_ON_FIRST_PAINT`. Empty map (or
 * missing entries) falls back to the legacy behaviour of unconditional publish,
 * preserving the upstream contract that the pipeline pre-filters `pendingRowsByKey`.
 *
 * `isPublishableRow` lets callers reject rows that are technically present but would
 * render as empty after surface-specific projection. When a higher-priority source has
 * only rejected rows, materialization falls through to the next source instead of
 * publishing an empty rail.
 */
internal fun materializeHomeRows(
    effectiveOrder: EffectiveHomeRailOrder,
    liveSyntheticGroupsByKey: Map<HomeRailKey, List<CatalogRow>>,
    persistedSyntheticGroupsByKey: Map<HomeRailKey, List<CatalogRow>>,
    rawRowsByKey: Map<HomeRailKey, CatalogRow>,
    pendingRowsByKey: Map<HomeRailKey, CatalogRow>,
    publishPolicyByKey: Map<HomeRailKey, RailPublishPolicy> = emptyMap(),
    isPublishableRow: (CatalogRow) -> Boolean = { true },
): List<CatalogRow> = buildList {
    effectiveOrder.visibleKeys.forEach { key ->
        liveSyntheticGroupsByKey[key]?.let { rows ->
            val publishableRows = rows.filter(isPublishableRow)
            if (publishableRows.isNotEmpty()) {
                addAll(publishableRows)
                return@forEach
            }
        }
        rawRowsByKey[key]?.let { row ->
            if (isPublishableRow(row)) {
                add(row)
                return@forEach
            }
        }
        persistedSyntheticGroupsByKey[key]?.let { rows ->
            val publishableRows = rows.filter(isPublishableRow)
            if (publishableRows.isNotEmpty()) {
                addAll(publishableRows)
                return@forEach
            }
        }
        pendingRowsByKey[key]?.let { row ->
            // Only publish the pending placeholder when the rail's publish policy
            // explicitly allows first-paint placeholders. If the caller didn't supply
            // a policy map (`emptyMap()`), fall back to the legacy behaviour of
            // unconditionally publishing — this preserves the upstream contract that
            // the caller filters pendingRowsByKey before passing it in.
            val policy = publishPolicyByKey[key]
            val allowedByPolicy = policy == null || policy == RailPublishPolicy.PUBLISH_ON_FIRST_PAINT
            if (allowedByPolicy) add(row)
        }
    }
}
