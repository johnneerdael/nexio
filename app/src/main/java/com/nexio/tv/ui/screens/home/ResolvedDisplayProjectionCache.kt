package com.nexio.tv.ui.screens.home

import com.nexio.tv.domain.model.ResolvedDisplayItem
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Memoizes [ModernHomeRowItem] and [ResolvedRailRow] projections so that
 * unchanged content keeps the same instance reference across emissions.
 *
 * Plan B (Resolved Display UI Consumption Migration) wires per-surface
 * `combine(...)` mappings that emit fresh projections at the home flow's
 * tick rate (~5/s × 76 rows × 25 items ≈ 9.5K projections/sec). Without
 * memoization that throughput would silently undo the allocation-rate
 * mitigation from PR #16. This cache returns the same instance whenever
 * the underlying [ResolvedDisplayItem] is unchanged (same `itemKey` and
 * same `updatedAtMs`) so Compose's structural-equality skip can elide
 * recomposition.
 */
@Singleton
class ResolvedDisplayProjectionCache @Inject constructor() {
    private val itemCache = mutableMapOf<String, Pair<Long, ModernHomeRowItem>>()
    private val railCache = mutableMapOf<String, Pair<Int, ResolvedRailRow>>()

    @Synchronized
    fun projectItem(resolved: ResolvedDisplayItem): ModernHomeRowItem {
        val cached = itemCache[resolved.itemKey]
        if (cached != null && cached.first == resolved.updatedAtMs) return cached.second
        val fresh = ModernHomeRowItem.from(resolved)
        itemCache[resolved.itemKey] = resolved.updatedAtMs to fresh
        return fresh
    }

    @Synchronized
    fun projectRail(catalogId: String, title: String, items: List<ModernHomeRowItem>): ResolvedRailRow {
        val key = catalogId
        val contentHash = items.map { it.itemKey to it.hashCode() }.hashCode()
        val cached = railCache[key]
        if (cached != null && cached.first == contentHash) return cached.second
        val fresh = ResolvedRailRow(catalogId = catalogId, title = title, items = items)
        railCache[key] = contentHash to fresh
        return fresh
    }

    @Synchronized
    fun retainOnly(activeItemKeys: Set<String>) {
        itemCache.keys.retainAll(activeItemKeys)
    }

    @Synchronized
    fun retainOnlyRails(activeCatalogIds: Set<String>) {
        railCache.keys.retainAll(activeCatalogIds)
    }
}
