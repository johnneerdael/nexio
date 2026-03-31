package com.nexio.tv.ui.screens.home

import android.content.Context
import androidx.compose.runtime.Immutable
import com.nexio.tv.R
import com.nexio.tv.domain.model.CatalogRow

@Immutable
internal data class ModernHomePresentationInput(
    val catalogRows: List<CatalogRow>,
    val continueWatchingItems: List<ContinueWatchingItem>,
    val useLandscapePosters: Boolean,
    val showCatalogTypeSuffix: Boolean,
    val localeTag: String
)

internal fun buildModernHomePresentation(
    input: ModernHomePresentationInput,
    cache: ModernCarouselRowBuildCache,
    context: Context,
    maxCatalogRows: Int? = null,
    cancellationCheck: () -> Unit = {}
): ModernHomePresentationState {
    cancellationCheck()
    val visibleCatalogRows = input.catalogRows.filter { it.items.isNotEmpty() }
    val catalogRowsToRender = maxCatalogRows
        ?.coerceAtLeast(0)
        ?.let(visibleCatalogRows::take)
        ?: visibleCatalogRows
    val strContinueWatching = context.getString(R.string.continue_watching)
    val strAirsDate = context.getString(R.string.cw_airs_date)
    val strUpcoming = context.getString(R.string.cw_upcoming)
    val strTypeMovie = context.getString(R.string.type_movie)
    val strTypeSeries = context.getString(R.string.type_series)

    val rows = buildList {
        cancellationCheck()
        val activeCatalogKeys = LinkedHashSet<String>(catalogRowsToRender.size)

        if (input.continueWatchingItems.isNotEmpty()) {
            cancellationCheck()
            val reuseContinueWatchingRow =
                cache.continueWatchingRow != null &&
                    cache.continueWatchingItems == input.continueWatchingItems &&
                    cache.continueWatchingTitle == strContinueWatching &&
                    cache.continueWatchingAirsDateTemplate == strAirsDate &&
                    cache.continueWatchingUpcomingLabel == strUpcoming &&
                    cache.continueWatchingUseLandscapePosters == input.useLandscapePosters &&
                    cache.continueWatchingLocaleTag == input.localeTag
            val continueWatchingRow = if (reuseContinueWatchingRow) {
                checkNotNull(cache.continueWatchingRow)
            } else {
                HeroCarouselRow(
                    key = "continue_watching",
                    title = strContinueWatching,
                    globalRowIndex = -1,
                    items = input.continueWatchingItems.map { item ->
                        cancellationCheck()
                        buildContinueWatchingItem(
                            item = item,
                            useLandscapePosters = input.useLandscapePosters,
                            airsDateTemplate = strAirsDate,
                            upcomingLabel = strUpcoming
                        )
                    }
                )
            }
            cache.continueWatchingItems = input.continueWatchingItems
            cache.continueWatchingTitle = strContinueWatching
            cache.continueWatchingAirsDateTemplate = strAirsDate
            cache.continueWatchingUpcomingLabel = strUpcoming
            cache.continueWatchingUseLandscapePosters = input.useLandscapePosters
            cache.continueWatchingLocaleTag = input.localeTag
            cache.continueWatchingRow = continueWatchingRow
            add(continueWatchingRow)
        } else {
            cache.continueWatchingItems = emptyList()
            cache.continueWatchingLocaleTag = ""
            cache.continueWatchingRow = null
        }

        catalogRowsToRender.forEachIndexed { index, row ->
            cancellationCheck()
            val rowKey = catalogRowKey(row)
            activeCatalogKeys += rowKey
            val cached = cache.catalogRows[rowKey]
            val canReuseMappedRow =
                cached != null &&
                    cached.source == row &&
                    cached.useLandscapePosters == input.useLandscapePosters &&
                    cached.showCatalogTypeSuffix == input.showCatalogTypeSuffix &&
                    cached.localeTag == input.localeTag

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
                        showCatalogTypeSuffix = input.showCatalogTypeSuffix,
                        strTypeMovie = strTypeMovie,
                        strTypeSeries = strTypeSeries
                    ),
                    globalRowIndex = index,
                    catalogId = row.catalogId,
                    addonId = row.addonId,
                    apiType = row.apiType,
                    supportsSkip = row.supportsSkip,
                    hasMore = row.hasMore,
                    isLoading = row.isLoading,
                    items = row.items.map { item ->
                        cancellationCheck()
                        val occurrence = rowItemOccurrenceCounts.getOrDefault(item.id, 0)
                        rowItemOccurrenceCounts[item.id] = occurrence + 1
                        val cacheKey = "${item.id}_$occurrence"
                        val cachedItem = rowItemCache[cacheKey]
                        if (cachedItem != null &&
                            cachedItem.source == item &&
                            cachedItem.useLandscapePosters == input.useLandscapePosters &&
                            cachedItem.localeTag == input.localeTag
                        ) {
                            cachedItem.carouselItem
                        } else {
                            val built = buildCatalogItem(
                                item = item,
                                row = row,
                                useLandscapePosters = input.useLandscapePosters,
                                occurrence = occurrence
                            )
                            rowItemCache[cacheKey] = CachedCarouselItem(
                                source = item,
                                useLandscapePosters = input.useLandscapePosters,
                                localeTag = input.localeTag,
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
                localeTag = input.localeTag,
                mappedRow = mappedRow
            )
            add(mappedRow)
        }

        cancellationCheck()
        cache.catalogRows.keys.retainAll(activeCatalogKeys)
        cache.catalogItemCache.keys.retainAll(activeCatalogKeys)
    }

    cancellationCheck()
    return ModernHomePresentationState(
        rows = rows,
        lookups = buildCarouselRowLookups(rows)
    )
}

internal fun buildCarouselRowLookups(carouselRows: List<HeroCarouselRow>): CarouselRowLookups {
    val rowIndexByKey = LinkedHashMap<String, Int>(carouselRows.size)
    val rowByKey = LinkedHashMap<String, HeroCarouselRow>(carouselRows.size)
    val rowKeyByGlobalRowIndex = LinkedHashMap<Int, String>(carouselRows.size)
    val firstHeroPreviewByRow = LinkedHashMap<String, HeroPreview>(carouselRows.size)
    val fallbackBackdropByRow = LinkedHashMap<String, String>(carouselRows.size)
    val activeRowKeys = LinkedHashSet<String>(carouselRows.size)
    val activeItemKeysByRow = LinkedHashMap<String, Set<String>>(carouselRows.size)
    val activeCatalogItemIds = LinkedHashSet<String>()

    carouselRows.forEachIndexed { index, row ->
        rowIndexByKey[row.key] = index
        rowByKey[row.key] = row
        if (row.globalRowIndex >= 0) {
            rowKeyByGlobalRowIndex[row.globalRowIndex] = row.key
        }
        row.items.firstOrNull()?.heroPreview?.let { firstHeroPreviewByRow[row.key] = it }
        row.items.firstNotNullOfOrNull { item ->
            item.heroPreview.backdrop?.takeIf { it.isNotBlank() }
        }?.let { fallbackBackdropByRow[row.key] = it }
        activeRowKeys += row.key

        val itemKeys = LinkedHashSet<String>(row.items.size)
        row.items.forEach { item ->
            itemKeys += item.key
            val payload = item.payload
            if (payload is ModernPayload.Catalog) {
                activeCatalogItemIds += payload.itemId
            }
        }
        activeItemKeysByRow[row.key] = itemKeys
    }

    return CarouselRowLookups(
        rowIndexByKey = rowIndexByKey,
        rowByKey = rowByKey,
        rowKeyByGlobalRowIndex = rowKeyByGlobalRowIndex,
        firstHeroPreviewByRow = firstHeroPreviewByRow,
        fallbackBackdropByRow = fallbackBackdropByRow,
        activeRowKeys = activeRowKeys,
        activeItemKeysByRow = activeItemKeysByRow,
        activeCatalogItemIds = activeCatalogItemIds
    )
}
