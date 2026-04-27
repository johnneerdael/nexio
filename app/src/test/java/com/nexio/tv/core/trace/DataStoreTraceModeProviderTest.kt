package com.nexio.tv.core.trace

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class DataStoreTraceModeProviderTest {
    @Test
    fun `current updates as upstream emits`() = runTest {
        val source = MutableSharedFlow<TraceMode>(replay = 1)
        val scope: CoroutineScope = TestScope(UnconfinedTestDispatcher())
        val provider = DataStoreTraceModeProvider(source, scope)

        // Default before any emission is OFF
        assertEquals(TraceMode.OFF, provider.current)

        source.emit(TraceMode.SAFE_METADATA_RUNTIME)
        assertEquals(TraceMode.SAFE_METADATA_RUNTIME, provider.current)

        source.emit(TraceMode.INCLUDE_HTTP_SUMMARY)
        assertEquals(TraceMode.INCLUDE_HTTP_SUMMARY, provider.current)

        // observe() yields the same StateFlow
        assertEquals(TraceMode.INCLUDE_HTTP_SUMMARY, provider.observe().first())
    }
}
