package com.nexio.tv.core.sync

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Gateway for priority catalog hydration. ONLY emit from explicit UI actions
 * (auth approved, catalog feed enabled, first login). Never from background
 * auth state observers or boot-time code — that would bypass startup gates on every boot.
 */
@Singleton
class CatalogPriorityHydrationNotifier @Inject constructor() {
    private val _events = MutableSharedFlow<Long>(extraBufferCapacity = 1)
    val events: SharedFlow<Long> = _events

    fun notifyPriorityHydrationRequired() {
        _events.tryEmit(System.currentTimeMillis())
    }
}
