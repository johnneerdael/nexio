package com.nexio.tv.core.trace

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class LogcatTraceChannelsProvider(
    firstPaintSource: Flow<Boolean>,
    metaRouteSource: Flow<Boolean>,
    intRuntimeSource: Flow<Boolean>,
    scope: CoroutineScope
) {
    private val firstPaint = MutableStateFlow(false)
    private val metaRoute = MutableStateFlow(false)
    private val intRuntime = MutableStateFlow(false)

    init {
        scope.launch { firstPaintSource.collect { firstPaint.value = it } }
        scope.launch { metaRouteSource.collect { metaRoute.value = it } }
        scope.launch { intRuntimeSource.collect { intRuntime.value = it } }
    }

    fun isEnabled(channel: LogcatTraceChannel): Boolean = when (channel) {
        LogcatTraceChannel.FIRST_PAINT -> firstPaint.value
        LogcatTraceChannel.META_ROUTE -> metaRoute.value
        LogcatTraceChannel.INT_RUNTIME -> intRuntime.value
    }
}
