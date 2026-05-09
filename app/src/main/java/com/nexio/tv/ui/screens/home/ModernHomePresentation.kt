package com.nexio.tv.ui.screens.home

internal fun buildModernHomePresentation(
    input: ModernHomePresentationInput,
    cache: ModernCarouselRowBuildCache
): ModernHomePresentationState {
    val visibleCatalogRows = modernVisibleCatalogRows(input.catalogRows)
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

        visibleCatalogRows.forEachIndexed { index, row ->
            val rowKey = catalogRowKey(row)
            activeCatalogKeys += rowKey
            val cached = cache.catalogRows[rowKey]
            val canReuseMappedRow =
                cached != null &&
                    cached.source == row &&
                    cached.useLandscapePosters == input.useLandscapePosters &&
                    cached.showCatalogTypeSuffix == input.showCatalogTypeSuffix

            val mappedRow = if (canReuseMappedRow) {
                val cachedMappedRow = checkNotNull(cached).mappedRow
                if (cachedMappedRow.globalRowIndex == index) {
                    cachedMappedRow
                } else {
                    cachedMappedRow.copy(globalRowIndex = index)
                }
            } else {
                val rowItemOccurrenceCounts = mutableMapOf<String, Int>()
                val rowItemCache = cache.catalogItemCache.getOrPut(rowKey) { mutableMapOf() }
                HeroCarouselRow(
                    key = rowKey,
                    title = catalogRowTitle(
                        row = row,
                        showCatalogTypeSuffix = input.showCatalogTypeSuffix
                    ),
                    globalRowIndex = index,
                    catalogId = row.catalogId,
                    addonId = row.addonId,
                    apiType = row.apiType,
                    supportsSkip = row.supportsSkip,
                    hasMore = row.hasMore,
                    isLoading = row.isLoading,
                    items = row.items.map { item ->
                        val occurrence = rowItemOccurrenceCounts.getOrDefault(item.id, 0)
                        rowItemOccurrenceCounts[item.id] = occurrence + 1
                        val cacheKey = "${item.id}_$occurrence"
                        val cachedItem = rowItemCache[cacheKey]
                        if (cachedItem != null &&
                            cachedItem.source == item &&
                            cachedItem.useLandscapePosters == input.useLandscapePosters
                        ) {
                            cachedItem.carouselItem
                        } else {
                            val built = buildCatalogItem(
                                item = item,
                                row = row,
                                useLandscapePosters = input.useLandscapePosters,
                                occurrence = occurrence,
                                previousCachedItem = cachedItem?.carouselItem
                            )
                            rowItemCache[cacheKey] = CachedCarouselItem(
                                source = item,
                                useLandscapePosters = input.useLandscapePosters,
                                carouselItem = built
                            )
                            built
                        }
                    }
                )
            }

            cache.catalogRows[rowKey] = ModernCatalogRowBuildCacheEntry(
                source = row,
                useLandscapePosters = input.useLandscapePosters,
                showCatalogTypeSuffix = input.showCatalogTypeSuffix,
                mappedRow = mappedRow
            )
            add(mappedRow)
        }

        cache.catalogRows.keys.retainAll(activeCatalogKeys)
        cache.catalogItemCache.keys.retainAll(activeCatalogKeys)
    }

    return ModernHomePresentationState(
        rows = rows,
        lookups = buildCarouselRowLookups(rows)
    )
}
