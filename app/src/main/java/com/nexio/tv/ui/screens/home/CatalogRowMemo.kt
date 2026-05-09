package com.nexio.tv.ui.screens.home

import com.nexio.tv.domain.model.CatalogRow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-wide memoization for [CatalogRow] instances. The Modern Home pipeline
 * re-allocates fresh CatalogRow + MetaPreview lists on every emission (~9/sec on
 * a populated home). Without interning, content-equal rows are reference-unequal,
 * causing downstream consumers (Compose, suspending coroutines, the snapshot
 * store) to retain stale versions across emissions and ultimately saturate the
 * heap.
 *
 * [intern] takes a freshly-built rows list and returns a list whose elements are
 * stable references when their content is unchanged from the previous call. The
 * cache is keyed by [signature], a low-cost content fingerprint:
 * `addonId|apiType|catalogId|isLoading|hasMore|currentPage|items.size|item-fingerprints`.
 *
 * Cache size is bounded by the active catalog set per call — entries whose
 * signature isn't in the current batch are evicted.
 *
 * Thread-safe via @Synchronized; the pipeline serializes its writes anyway, so
 * contention is negligible.
 */
@Singleton
class CatalogRowMemo @Inject constructor() {
    private val cache = mutableMapOf<String, CatalogRow>()

    @Synchronized
    fun intern(rows: List<CatalogRow>): List<CatalogRow> {
        if (rows.isEmpty()) {
            cache.clear()
            return rows
        }
        val activeKeys = HashSet<String>(rows.size)
        val result = rows.map { row ->
            val key = signature(row)
            activeKeys += key
            val cached = cache[key]
            if (cached != null && cached == row) {
                cached
            } else {
                cache[key] = row
                row
            }
        }
        cache.keys.retainAll(activeKeys)
        return result
    }

    private fun signature(row: CatalogRow): String = buildString(64 + row.items.size * 8) {
        append(row.addonId)
        append('|')
        append(row.apiType)
        append('|')
        append(row.catalogId)
        append('|')
        append(if (row.isLoading) '1' else '0')
        append('|')
        append(if (row.hasMore) '1' else '0')
        append('|')
        append(row.currentPage)
        append('|')
        append(row.items.size)
        append('|')
        // Per-item fingerprint: id + apiType is sufficient to detect order/membership
        // changes. Content changes within an item (artwork, hydration) are detected
        // by the value-equal check on the cached row — see [intern].
        for (i in row.items.indices) {
            val item = row.items[i]
            append(item.id)
            append(';')
            append(item.apiType)
            append(';')
        }
    }
}
