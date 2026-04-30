package com.nexio.tv.core.trace

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LogcatTraceChannelsProviderTest {

    @Test
    fun `defaults all channels to disabled before flows emit`() = runTest {
        val firstPaint = MutableStateFlow(false)
        val metaRoute = MutableStateFlow(false)
        val intRuntime = MutableStateFlow(false)
        val scope: CoroutineScope = TestScope(UnconfinedTestDispatcher())
        val provider = LogcatTraceChannelsProvider(
            firstPaintSource = firstPaint,
            metaRouteSource = metaRoute,
            intRuntimeSource = intRuntime,
            scope = scope
        )
        try {
            assertFalse(provider.isEnabled(LogcatTraceChannel.FIRST_PAINT))
            assertFalse(provider.isEnabled(LogcatTraceChannel.META_ROUTE))
            assertFalse(provider.isEnabled(LogcatTraceChannel.INT_RUNTIME))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `propagates updates from each source flow to corresponding channel`() = runTest {
        val firstPaint = MutableStateFlow(false)
        val metaRoute = MutableStateFlow(false)
        val intRuntime = MutableStateFlow(false)
        val scope: CoroutineScope = TestScope(UnconfinedTestDispatcher())
        val provider = LogcatTraceChannelsProvider(
            firstPaintSource = firstPaint,
            metaRouteSource = metaRoute,
            intRuntimeSource = intRuntime,
            scope = scope
        )
        try {
            firstPaint.value = true
            assertTrue(provider.isEnabled(LogcatTraceChannel.FIRST_PAINT))
            assertFalse(provider.isEnabled(LogcatTraceChannel.META_ROUTE))
            assertFalse(provider.isEnabled(LogcatTraceChannel.INT_RUNTIME))

            metaRoute.value = true
            intRuntime.value = true
            assertTrue(provider.isEnabled(LogcatTraceChannel.META_ROUTE))
            assertTrue(provider.isEnabled(LogcatTraceChannel.INT_RUNTIME))

            firstPaint.value = false
            assertFalse(provider.isEnabled(LogcatTraceChannel.FIRST_PAINT))
            assertTrue(provider.isEnabled(LogcatTraceChannel.META_ROUTE))
        } finally {
            scope.cancel()
        }
    }
}
