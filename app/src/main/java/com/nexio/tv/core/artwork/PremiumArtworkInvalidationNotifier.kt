package com.nexio.tv.core.artwork

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

@Singleton
class PremiumArtworkInvalidationNotifier @Inject constructor() {
    private val _events = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val events: SharedFlow<Unit> = _events.asSharedFlow()

    fun notifyInvalidated() {
        _events.tryEmit(Unit)
    }
}
