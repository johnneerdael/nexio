package com.nexio.tv.core.trace

import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.core.integration.IntegrationScope
import com.nexio.tv.core.integration.IntegrationWorkClass
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.coroutines.coroutineContext

class RuntimeTraceContextElementTest {
    @Test
    fun `current returns null without element`() = runTest {
        assertNull(RuntimeTraceContextElement.current(coroutineContext))
    }

    @Test
    fun `current returns element value`() = runTest {
        val ctx = RuntimeTraceContext(
            traceSessionId = "s",
            runtimeOperationId = "op_1",
            provider = IntegrationProvider.TMDB,
            apiShapeId = "tmdb.movie.core",
            operationKey = "tmdb.movie.core:550:en",
            cacheKey = null,
            workClass = IntegrationWorkClass.USER_VISIBLE,
            scope = IntegrationScope.GlobalContent,
            profileHash = null,
            accountProvider = null,
            credentialHash = null
        )
        withContext(RuntimeTraceContextElement(ctx)) {
            assertEquals("op_1", RuntimeTraceContextElement.current(coroutineContext)?.runtimeOperationId)
        }
    }
}
