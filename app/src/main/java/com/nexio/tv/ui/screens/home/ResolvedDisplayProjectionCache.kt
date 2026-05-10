package com.nexio.tv.ui.screens.home

import com.nexio.tv.domain.model.ResolvedDisplayItem
import com.nexio.tv.ui.components.ContinueWatchingResolvedDisplayItem
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
    // Hero-panel projection cache. Keyed by itemKey; the value's first slot is
    // the source `ResolvedDisplayItem` reference used as a fast-path identity
    // check. Upstream (`HomeResolvedDisplayMapper`) already memoizes resolved
    // instances so the same itemKey yields the same reference until content
    // actually changes — reference equality on the source is therefore both
    // sufficient and equivalent to `(itemKey, updatedAtMs)` keying used by
    // [projectItem].
    private val heroItemCache = mutableMapOf<String, Pair<ResolvedDisplayItem, HeroDisplayItem>>()
    // Single-slot cache of the most recently emitted rails list. When the next
    // emission produces a list whose elements are reference-identical to the
    // prior emission's, we return the prior list reference so consumers'
    // `===` guards (e.g. observeResolvedRailRows) skip redundant state updates.
    private var lastRailsList: List<ResolvedRailRow>? = null
    // Single-slot cache of the most recently emitted hero items list. Same
    // rationale as [lastRailsList] but for the hero surface (Plan B Task 9):
    // observeResolvedHeroItems' `===` guard relies on this so identical content
    // does not get pushed into the hero StateFlow on every upstream tick.
    private var lastHeroList: List<HeroDisplayItem>? = null
    // Continue Watching projection cache (Plan B Surface 4 Phase 2). Two-key
    // strategy keyed by itemKey; the cached triple records the source
    // ContinueWatchingItem reference (since upstream HomeResolvedDisplayMapper
    // memoizes ResolvedDisplayItem already, reference equality on
    // `(resolved, source)` is sufficient).
    private val cwInProgressItemCache =
        mutableMapOf<String, Triple<ResolvedDisplayItem, ContinueWatchingItem.InProgress, ContinueWatchingResolvedDisplayItem.InProgress>>()
    private val cwNextUpItemCache =
        mutableMapOf<String, Triple<ResolvedDisplayItem, ContinueWatchingItem.NextUp, ContinueWatchingResolvedDisplayItem.NextUp>>()
    // Single-slot cache of the most recently emitted Continue Watching list.
    // Same rationale as [lastRailsList] / [lastHeroList].
    private var lastContinueWatchingList: List<ContinueWatchingResolvedDisplayItem>? = null

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
     * Returns a [HeroDisplayItem] for [resolved], reusing the prior projection
     * when the source `ResolvedDisplayItem` reference is unchanged. Mirrors
     * [projectItem] but for the hero surface — see [HeroDisplayItem.from] for
     * the projection rules (notably `backdrop ?: poster` for the background ref).
     */
    @Synchronized
    fun projectHero(resolved: ResolvedDisplayItem): HeroDisplayItem {
        val cached = heroItemCache[resolved.itemKey]
        if (cached != null && cached.first === resolved) return cached.second
        val fresh = HeroDisplayItem.from(resolved)
        heroItemCache[resolved.itemKey] = resolved to fresh
        return fresh
    }

    @Synchronized
    fun retainOnlyHeroItems(activeItemKeys: Set<String>) {
        heroItemCache.keys.retainAll(activeItemKeys)
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

    /**
     * Hero-list counterpart to [internRailsList]. Reuses the prior emission's
     * outer list reference when every element is reference-identical, so the
     * consumer's `===` guard against `_resolvedHeroItems.value` short-circuits
     * when content is unchanged.
     */
    @Synchronized
    fun internHeroList(items: List<HeroDisplayItem>): List<HeroDisplayItem> {
        val prior = lastHeroList
        if (prior != null && prior.size == items.size) {
            var allSame = true
            for (i in items.indices) {
                if (prior[i] !== items[i]) {
                    allSame = false
                    break
                }
            }
            if (allSame) return prior
        }
        lastHeroList = items
        return items
    }

    /**
     * Returns a [ContinueWatchingResolvedDisplayItem.InProgress] for [resolved] paired with
     * [source], reusing the prior projection when both the source [ResolvedDisplayItem] and
     * [ContinueWatchingItem.InProgress] references are unchanged. Mirrors [projectHero] but
     * for the CW resume surface — see
     * [ContinueWatchingResolvedDisplayItem.fromInProgress] for projection rules.
     */
    @Synchronized
    fun projectCwInProgress(
        resolved: ResolvedDisplayItem,
        source: ContinueWatchingItem.InProgress
    ): ContinueWatchingResolvedDisplayItem.InProgress {
        val cached = cwInProgressItemCache[resolved.itemKey]
        if (cached != null && cached.first === resolved && cached.second === source) return cached.third
        val fresh = ContinueWatchingResolvedDisplayItem.fromInProgress(resolved, source)
        cwInProgressItemCache[resolved.itemKey] = Triple(resolved, source, fresh)
        return fresh
    }

    /**
     * Returns a [ContinueWatchingResolvedDisplayItem.NextUp] for [resolved] paired with
     * [source], reusing the prior projection when both source references are unchanged.
     */
    @Synchronized
    fun projectCwNextUp(
        resolved: ResolvedDisplayItem,
        source: ContinueWatchingItem.NextUp
    ): ContinueWatchingResolvedDisplayItem.NextUp {
        val cached = cwNextUpItemCache[resolved.itemKey]
        if (cached != null && cached.first === resolved && cached.second === source) return cached.third
        val fresh = ContinueWatchingResolvedDisplayItem.fromNextUp(resolved, source)
        cwNextUpItemCache[resolved.itemKey] = Triple(resolved, source, fresh)
        return fresh
    }

    @Synchronized
    fun retainOnlyContinueWatchingItems(activeItemKeys: Set<String>) {
        cwInProgressItemCache.keys.retainAll(activeItemKeys)
        cwNextUpItemCache.keys.retainAll(activeItemKeys)
    }

    /**
     * Continue Watching list counterpart to [internHeroList] / [internRailsList].
     * Reuses the prior emission's outer list reference when every element is
     * reference-identical, so the consumer's `===` guard against
     * `_resolvedContinueWatchingItems.value` short-circuits when content is unchanged.
     */
    @Synchronized
    fun internContinueWatchingList(
        items: List<ContinueWatchingResolvedDisplayItem>
    ): List<ContinueWatchingResolvedDisplayItem> {
        val prior = lastContinueWatchingList
        if (prior != null && prior.size == items.size) {
            var allSame = true
            for (i in items.indices) {
                if (prior[i] !== items[i]) {
                    allSame = false
                    break
                }
            }
            if (allSame) return prior
        }
        lastContinueWatchingList = items
        return items
    }
}
