package com.nexio.tv.core.sync

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AccountSyncRefreshNotifier @Inject constructor() {
    private val _events = MutableSharedFlow<Long>(extraBufferCapacity = 1)
    val events: SharedFlow<Long> = _events

    fun notifyRefreshRequired() {
        _events.tryEmit(System.currentTimeMillis())
    }
}
