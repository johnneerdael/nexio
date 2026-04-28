package com.nexio.tv.ui.screens.home

import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.RailItemPreview

object RailPreviewHydrationCoordinator {
    fun targetsForVisibleWindow(
        itemKeys: List<String>,
        visibleRange: IntRange,
        focusedIndex: Int?,
        adjacentCount: Int
    ): List<String> {
        if (itemKeys.isEmpty()) return emptyList()

        val lastIndex = itemKeys.lastIndex
        val targetIndices = mutableSetOf<Int>()

        val distance = adjacentCount.coerceAtLeast(0)
        val visibleStart = visibleRange.first.coerceAtLeast(0)
        val visibleEnd = visibleRange.last.coerceAtMost(lastIndex)
        if (visibleStart <= visibleEnd) {
            for (index in visibleStart..visibleEnd) {
                targetIndices += index
            }
        }

        focusedIndex?.takeIf { it in itemKeys.indices }?.let { focused ->
            val start = (focused - distance).coerceAtLeast(0)
            val end = (focused + distance).coerceAtMost(lastIndex)
            for (index in start..end) {
                targetIndices += index
            }
        }

        return targetIndices
            .sorted()
            .map(itemKeys::get)
    }
}

fun RailItemPreview.bestRoutingId(): String = when {
    stableIds.kitsu != null -> "kitsu:${stableIds.kitsu}"
    itemType == ContentType.MOVIE && stableIds.tmdb != null -> "tmdb:${stableIds.tmdb}"
    itemType == ContentType.SERIES && stableIds.tvdb != null -> "tvdb:${stableIds.tvdb}"
    stableIds.imdb != null -> stableIds.imdb
    else -> sourceItemId
}
