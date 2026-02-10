package com.nuvio.tv.ui.screens.search

sealed interface SearchEvent {
    data class QueryChanged(val query: String) : SearchEvent

    data class LoadMoreCatalog(
        val catalogId: String,
        val addonId: String,
        val type: String
    ) : SearchEvent

    data class SelectDiscoverType(val type: String) : SearchEvent
    data class SelectDiscoverCatalog(val catalogKey: String) : SearchEvent
    data class SelectDiscoverGenre(val genre: String?) : SearchEvent
    data object ShowMoreDiscoverResults : SearchEvent
    data object LoadMoreDiscoverResults : SearchEvent

    data object Retry : SearchEvent
}
