package com.nexio.tv.core.trace

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class LogcatTraceChannelsProvider(
    firstPaintSource: Flow<Boolean>,
    metaRouteSource: Flow<Boolean>,
    intRuntimeSource: Flow<Boolean>,
    scope: CoroutineScope
) {
    private val firstPaint: StateFlow<Boolean> =
        firstPaintSource.stateIn(scope, SharingStarted.Eagerly, false)
    private val metaRoute: StateFlow<Boolean> =
        metaRouteSource.stateIn(scope, SharingStarted.Eagerly, false)
    private val intRuntime: StateFlow<Boolean> =
        intRuntimeSource.stateIn(scope, SharingStarted.Eagerly, false)

    fun isEnabled(channel: LogcatTraceChannel): Boolean = when (channel) {
        LogcatTraceChannel.FIRST_PAINT -> firstPaint.value
        LogcatTraceChannel.META_ROUTE -> metaRoute.value
        LogcatTraceChannel.INT_RUNTIME -> intRuntime.value
    }
}
