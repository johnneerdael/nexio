package com.nexio.tv.ui.screens.home

import com.nexio.tv.domain.model.MetaPreview

object HomePreviewHydrationPlanner {
    fun targetsForVisibleWindow(
        items: List<MetaPreview>,
        visibleRange: IntRange,
        focusedIndex: Int?,
        adjacentCount: Int
    ): List<MetaPreview> {
        if (items.isEmpty()) return emptyList()

        val lastIndex = items.lastIndex
        val targetIndices = mutableSetOf<Int>()

        val distance = adjacentCount.coerceAtLeast(0)
        val visibleStart = visibleRange.first.coerceAtLeast(0)
        val visibleEnd = visibleRange.last.coerceAtMost(lastIndex)
        if (visibleStart <= visibleEnd) {
            for (index in visibleStart..visibleEnd) {
                targetIndices += index
            }
        }

        focusedIndex?.takeIf { it in items.indices }?.let { focused ->
            val start = (focused - distance).coerceAtLeast(0)
            val end = (focused + distance).coerceAtMost(lastIndex)
            for (index in start..end) {
                targetIndices += index
            }
        }

        return targetIndices
            .sorted()
            .map(items::get)
    }
}
