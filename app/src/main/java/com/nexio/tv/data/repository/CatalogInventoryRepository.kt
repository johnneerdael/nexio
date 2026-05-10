package com.nexio.tv.data.repository

import com.nexio.tv.domain.model.CatalogRow
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Owns the catalog inventory — every rail across every addon, Trakt synthetic
 * group, MDBList, Simkl, TMDB, Kitsu — keyed by `addonId_apiType_catalogId`.
 *
 * Replaces `HomeViewModel._fullCatalogRows: MutableStateFlow<List<CatalogRow>>`,
 * which retained 17-28 MiB on `HomeViewModel`'s dominator subtree under
 * sustained Modern Home use. Internal pipeline consumers read in their native
 * shape (snapshot map, set of item keys, emptiness flag); `CatalogSeeAllScreen`
 * observes a single rail by key instead of the full inventory list.
 *
 * Push model: `HomeViewModelCatalogPipeline.applyHomeSnapshotToUiPipeline`
 * keeps building the inventory and calls [publish] each emission. Repo does
 * not subscribe to upstream sources itself.
 *
 * Spec: docs/superpowers/specs/2026-05-10-catalog-inventory-repository-design.md
 */
@Singleton
class CatalogInventoryRepository @Inject constructor() {

    private val _inventory = MutableStateFlow<Map<String, CatalogRow>>(emptyMap())
    val inventory: StateFlow<Map<String, CatalogRow>> = _inventory.asStateFlow()

    /** Synchronous read for `HomeViewModelCatalogPipeline` internal use. */
    fun snapshot(): Map<String, CatalogRow> = _inventory.value

    /** Single-rail observation for `CatalogSeeAllScreen`. Filtered + distinct
     *  so only rail-content changes propagate to the consumer. */
    fun observeRail(key: String): Flow<CatalogRow?> =
        _inventory.map { it[key] }.distinctUntilChanged()

    /** Used in place of `_fullCatalogRows.value.isEmpty()` for the
     *  `rawFirstPaintBatchActive` flag in `runSerializedPostStartupRefreshPipeline`. */
    fun isEmpty(): Boolean = _inventory.value.isEmpty()

    /**
     * Aggregates `"${apiType}:${id}"` strings across every item in every rail.
     * Replaces the inventory portion of the inline build at
     * `HomeViewModelCatalogPipeline.kt:1623`. The call site unions this with
     * `catalogsMap.values.flatMap { ... }.map { ... }.toSet()` to get the
     * full active-keys set.
     */
    fun activeItemKeys(): Set<String> {
        val current = _inventory.value
        val out = HashSet<String>()
        for ((_, row) in current) {
            for (i in row.items.indices) {
                val item = row.items[i]
                out += "${item.apiType}:${item.id}"
            }
        }
        return out
    }

    /**
     * Replace the inventory atomically. Built as `LinkedHashMap` so insertion
     * order is preserved (matches the prior `List<CatalogRow>` ordering used
     * by upstream). Rows with any blank component of the triple key are
     * skipped defensively — pipeline shouldn't produce these but the gate
     * prevents silent corruption.
     *
     * `@Synchronized` atomicizes build + StateFlow assignment from the writer
     * side; `_inventory.value`'s volatile semantics guarantee readers never
     * see a torn map.
     *
     * Stores rows by reference; callers MUST NOT mutate `CatalogRow`
     * instances after handing them off (CatalogRow is a data class, so
     * this is convention rather than enforcement).
     *
     * **Reference stability deferral:** every publish allocates a fresh
     * `LinkedHashMap` and fresh String keys (`addonId_apiType_catalogId`).
     * The inventory `StateFlow` does not achieve `===` skip on content-equal
     * emissions; `observeRail` mitigates for downstream consumers via
     * `distinctUntilChanged` on the rail value. A future
     * `CatalogInventoryMemo` (analogous to `CatalogRowMemo`) could intern
     * the LinkedHashMap and the keyed entries when content is unchanged —
     * deferred to a follow-up; see spec §Reference stability.
     */
    @Synchronized
    fun publish(rows: List<CatalogRow>) {
        val next = LinkedHashMap<String, CatalogRow>(rows.size)
        // Indexed-for: avoids Iterable iterator allocation (CLAUDE.md rule #4).
        for (i in rows.indices) {
            val row = rows[i]
            if (row.addonId.isBlank() || row.apiType.isBlank() || row.catalogId.isBlank()) continue
            next["${row.addonId}_${row.apiType}_${row.catalogId}"] = row
        }
        _inventory.value = next
    }

    /** Reset path for profile switch / sign-out. */
    fun clear() {
        _inventory.value = emptyMap()
    }
}
