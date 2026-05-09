package com.nexio.tv.ui.screens.home

import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.homeDisplayItemKey

internal fun buildModernHomePresentation(
    input: ModernHomePresentationInput,
    cache: ModernCarouselRowBuildCache
): ModernHomePresentationState {
    val visibleCatalogRows = modernVisibleCatalogRows(input.catalogRows)
    // Index catalog rows by catalogId so each ResolvedRailRow can locate its parallel
    // CatalogRow (for addonBaseUrl, addonId, focus key context, and the per-item
    // MetaPreview lookup used to populate ModernCarouselItem.metaPreview for callbacks).
    val catalogRowByCatalogId = LinkedHashMap<String, CatalogRow_>(visibleCatalogRows.size).also { map ->
        visibleCatalogRows.forEach { row -> map[row.catalogId] = row }
    }
    val rows = buildList {
        val activeCatalogKeys = LinkedHashSet<String>(visibleCatalogRows.size)

        if (input.continueWatchingItems.isNotEmpty()) {
            val reuseContinueWatchingRow =
                cache.continueWatchingRow != null &&
                    cache.continueWatchingItems == input.continueWatchingItems &&
                    cache.continueWatchingTitle == input.continueWatchingTitle &&
                    cache.continueWatchingAirsDateTemplate == input.airsDateTemplate &&
                    cache.continueWatchingUpcomingLabel == input.upcomingLabel &&
                    cache.continueWatchingUseLandscapePosters == input.useLandscapePosters

            val continueWatchingRow = if (reuseContinueWatchingRow) {
                checkNotNull(cache.continueWatchingRow)
            } else {
                HeroCarouselRow(
                    key = "continue_watching",
                    title = input.continueWatchingTitle,
                    globalRowIndex = -1,
                    items = input.continueWatchingItems.map { item ->
                        buildContinueWatchingItem(
                            item = item,
                            useLandscapePosters = input.useLandscapePosters,
                            airsDateTemplate = input.airsDateTemplate,
                            upcomingLabel = input.upcomingLabel
                        )
                    }
                )
            }

            cache.continueWatchingItems = input.continueWatchingItems
            cache.continueWatchingTitle = input.continueWatchingTitle
            cache.continueWatchingAirsDateTemplate = input.airsDateTemplate
            cache.continueWatchingUpcomingLabel = input.upcomingLabel
            cache.continueWatchingUseLandscapePosters = input.useLandscapePosters
            cache.continueWatchingRow = continueWatchingRow
            add(continueWatchingRow)
        } else {
            cache.continueWatchingItems = emptyList()
            cache.continueWatchingRow = null
        }

        // Iterate ResolvedRailRows (the rendering authority). For each rail, resolve the
        // parallel CatalogRow so we can: (1) build a stable rowKey + apply addon context,
        // (2) pull MetaPreview values to populate ModernCarouselItem.metaPreview for
        // callback compatibility (focus, watched-state, long press, preload).
        var globalIndex = 0
        input.resolvedRailRows.forEach { resolvedRail ->
            val sourceRow = catalogRowByCatalogId[resolvedRail.catalogId] ?: return@forEach
            val rowKey = catalogRowKey(sourceRow)
            activeCatalogKeys += rowKey

            val cached = cache.catalogRows[rowKey]
            // Resolved authority gates cache reuse: the rail row instance returned by
            // ResolvedDisplayProjectionCache is stable when content is unchanged, so a
            // referential identity check on the projection is the right invalidation key.
            // We additionally pin the source CatalogRow to keep MetaPreview lookups (used
            // for ModernCarouselItem.metaPreview) consistent with the resolved snapshot.
            val canReuseMappedRow =
                cached != null &&
                    cached.source === sourceRow &&
                    cached.useLandscapePosters == input.useLandscapePosters &&
                    cached.showCatalogTypeSuffix == input.showCatalogTypeSuffix &&
                    cached.resolvedRail === resolvedRail

            val mappedRow = if (canReuseMappedRow) {
                val cachedMappedRow = checkNotNull(cached).mappedRow
                if (cachedMappedRow.globalRowIndex == globalIndex) {
                    cachedMappedRow
                } else {
                    cachedMappedRow.copy(globalRowIndex = globalIndex)
                }
            } else {
                // Build a per-rail MetaPreview lookup: resolved itemKey -> MetaPreview
                // (homeDisplayItemKey of (apiType, id)). MetaPreview may be missing during
                // a transient race; buildCatalogItem tolerates null.
                val metaByItemKey = HashMap<String, MetaPreview>(sourceRow.items.size)
                sourceRow.items.forEach { meta ->
                    metaByItemKey[homeDisplayItemKey(meta.apiType, meta.id)] = meta
                }

                val rowItemOccurrenceCounts = mutableMapOf<String, Int>()
                val rowItemCache = cache.catalogItemCache.getOrPut(rowKey) { mutableMapOf() }
                val activeItemCacheKeys = mutableSetOf<String>()

                HeroCarouselRow(
                    key = rowKey,
                    title = catalogRowTitle(
                        row = sourceRow,
                        showCatalogTypeSuffix = input.showCatalogTypeSuffix
                    ),
                    globalRowIndex = globalIndex,
                    catalogId = sourceRow.catalogId,
                    addonId = sourceRow.addonId,
                    apiType = sourceRow.apiType,
                    supportsSkip = sourceRow.supportsSkip,
                    hasMore = sourceRow.hasMore,
                    isLoading = sourceRow.isLoading,
                    items = resolvedRail.items.map { resolvedItem ->
                        val metaPreview = metaByItemKey[resolvedItem.itemKey]
                        val itemIdForKey = metaPreview?.id ?: resolvedItem.contentId
                        val occurrence = rowItemOccurrenceCounts.getOrDefault(itemIdForKey, 0)
                        rowItemOccurrenceCounts[itemIdForKey] = occurrence + 1
                        val cacheKey = "${itemIdForKey}_$occurrence"
                        activeItemCacheKeys += cacheKey
                        val cachedItem = rowItemCache[cacheKey]
                        if (cachedItem != null &&
                            cachedItem.resolvedSource === resolvedItem &&
                            cachedItem.metaSource === metaPreview &&
                            cachedItem.useLandscapePosters == input.useLandscapePosters
                        ) {
                            cachedItem.carouselItem
                        } else {
                            val built = buildCatalogItem(
                                resolved = resolvedItem,
                                metaPreview = metaPreview,
                                row = sourceRow,
                                useLandscapePosters = input.useLandscapePosters,
                                occurrence = occurrence,
                                previousCachedItem = cachedItem?.carouselItem
                            )
                            rowItemCache[cacheKey] = CachedCarouselItem(
                                resolvedSource = resolvedItem,
                                metaSource = metaPreview,
                                useLandscapePosters = input.useLandscapePosters,
                                carouselItem = built
                            )
                            built
                        }
                    }
                ).also {
                    // Trim per-rail cache to active items only (drop entries that disappeared
                    // when the rail recomposes with fewer/different items).
                    rowItemCache.keys.retainAll(activeItemCacheKeys)
                }
            }

            cache.catalogRows[rowKey] = ModernCatalogRowBuildCacheEntry(
                source = sourceRow,
                resolvedRail = resolvedRail,
                useLandscapePosters = input.useLandscapePosters,
                showCatalogTypeSuffix = input.showCatalogTypeSuffix,
                mappedRow = mappedRow
            )
            add(mappedRow)
            globalIndex += 1
        }

        cache.catalogRows.keys.retainAll(activeCatalogKeys)
        cache.catalogItemCache.keys.retainAll(activeCatalogKeys)
    }

    return ModernHomePresentationState(
        rows = rows,
        lookups = buildCarouselRowLookups(rows)
    )
}

// Type alias to avoid an extra import for CatalogRow at the top of this file. The
// alias keeps the buildModernHomePresentation body readable without re-importing the
// already-resolved type from buildCatalogItem's signature.
private typealias CatalogRow_ = com.nexio.tv.domain.model.CatalogRow
