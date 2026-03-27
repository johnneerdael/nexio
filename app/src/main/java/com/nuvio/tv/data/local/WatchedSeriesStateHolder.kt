package com.nuvio.tv.data.local

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Shared holder for fully-watched series IDs derived from the CW pipeline.
 * Updated by HomeViewModel, observed by any screen that needs series watched badges.
 */
@Singleton
class WatchedSeriesStateHolder @Inject constructor() {
    private val _fullyWatchedSeriesIds = MutableStateFlow<Set<String>>(emptySet())
    val fullyWatchedSeriesIds: StateFlow<Set<String>> = _fullyWatchedSeriesIds.asStateFlow()

    fun update(ids: Set<String>) {
        _fullyWatchedSeriesIds.value = ids
    }
}
