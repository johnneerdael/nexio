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
    // Single-slot cache of the most recently emitted rails list. When the next
    // emission produces a list whose elements are reference-identical to the
    // prior emission's, we return the prior list reference so consumers'
    // `===` guards (e.g. observeResolvedRailRows) skip redundant state updates.
    private var lastRailsList: List<ResolvedRailRow>? = null

    @Synchronized
    fun projectItem(resolved: ResolvedDisplayItem): ModernHomeRowItem {
        val cached = itemCache[resolved.itemKey]
        if (cached != null && cached.first == resolved.updatedAtMs) return cached.second
        val fresh = ModernHomeRowItem.from(resolved)
        itemCache[resolved.itemKey] = resolved.updatedAtMs to fresh
        return fresh
    }

    // Cache key is catalogId alone — assumes catalog display titles are stable
    // per catalogId (true in this codebase). If catalog titles ever become
    // mutable, fold `title` into the version hash.
    @Synchronized
    fun projectRail(catalogId: String, title: String, items: List<ModernHomeRowItem>): ResolvedRailRow {
        val key = catalogId
        val contentHash = items.hashCode()
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

    /**
     * Returns a [List] reference equal to [rails] in content but reusing the
     * prior emission's list when every element is reference-identical and the
     * list size is unchanged. This complements [projectRail] by also stabilising
     * the OUTER list, so `_uiState.update { it.copy(resolvedRailRows = rails) }`
     * can short-circuit via a `===` guard when nothing changed.
     */
    @Synchronized
    fun internRailsList(rails: List<ResolvedRailRow>): List<ResolvedRailRow> {
        val prior = lastRailsList
        if (prior != null && prior.size == rails.size) {
            var allSame = true
            for (i in rails.indices) {
                if (prior[i] !== rails[i]) {
                    allSame = false
                    break
                }
            }
            if (allSame) return prior
        }
        lastRailsList = rails
        return rails
    }
}
