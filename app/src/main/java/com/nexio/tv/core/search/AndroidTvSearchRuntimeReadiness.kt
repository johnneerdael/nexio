package com.nexio.tv.core.search

import com.nexio.tv.domain.model.MetaPreview

private const val DEFAULT_RUNTIME_HYDRATION_LIMIT = 40

object AndroidTvSearchRuntimeReadiness {
    fun prioritizeMissingRuntimeCandidates(
        items: List<MetaPreview>,
        limit: Int = DEFAULT_RUNTIME_HYDRATION_LIMIT
    ): List<MetaPreview> {
        return items
            .distinctBy { "${it.apiType}:${it.id}" }
            .filter { it.isRuntimeHydrationCandidate() }
            .sortedWith(
                compareByDescending<MetaPreview> { it.poster != null || it.background != null }
                    .thenByDescending { AndroidTvSearchSuggestionMapper.parseProductionYear(it.releaseInfo) != null }
                    .thenBy { it.name.lowercase() }
            )
            .take(limit.coerceAtLeast(0))
    }

    fun hasReliableRuntime(item: MetaPreview): Boolean {
        return AndroidTvSearchSuggestionMapper.parseDurationMs(item.runtime) != null
    }

    private fun MetaPreview.isRuntimeHydrationCandidate(): Boolean {
        if (id.isBlank() || name.isBlank()) return false
        if (hasReliableRuntime(this)) return false
        return apiType.equals("movie", ignoreCase = true) ||
            apiType.equals("series", ignoreCase = true) ||
            apiType.equals("tv", ignoreCase = true)
    }
}
