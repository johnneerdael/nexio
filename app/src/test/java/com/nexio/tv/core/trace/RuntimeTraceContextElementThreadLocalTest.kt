package com.nexio.tv.core.trace

import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.core.integration.IntegrationScope
import com.nexio.tv.core.integration.IntegrationWorkClass
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RuntimeTraceContextElementThreadLocalTest {
    private fun ctx(opId: String = "op_t") = RuntimeTraceContext(
        traceSessionId = "s",
        runtimeOperationId = opId,
        provider = IntegrationProvider.TMDB,
        apiShapeId = "shape",
        operationKey = "k",
        cacheKey = null,
        workClass = IntegrationWorkClass.USER_VISIBLE,
        scope = IntegrationScope.GlobalContent,
        profileHash = null,
        accountProvider = null,
        credentialHash = null
    )

    @Test
    fun `activeOnThread is null without element`() {
        assertNull(RuntimeTraceContextElement.activeOnThread())
    }

    @Test
    fun `activeOnThread returns context inside coroutine and clears on exit`() = runTest {
        withContext(RuntimeTraceContextElement(ctx("op_inner"))) {
            assertEquals("op_inner", RuntimeTraceContextElement.activeOnThread()?.runtimeOperationId)
        }
        assertNull("thread-local must be cleared after coroutine exits", RuntimeTraceContextElement.activeOnThread())
    }

    @Test
    fun `nested elements stack and restore`() = runTest {
        withContext(RuntimeTraceContextElement(ctx("op_outer"))) {
            assertEquals("op_outer", RuntimeTraceContextElement.activeOnThread()?.runtimeOperationId)
            withContext(RuntimeTraceContextElement(ctx("op_inner"))) {
                assertEquals("op_inner", RuntimeTraceContextElement.activeOnThread()?.runtimeOperationId)
            }
            assertEquals("op_outer", RuntimeTraceContextElement.activeOnThread()?.runtimeOperationId)
        }
    }

    @Test
    fun `dispatch onto a different dispatcher carries thread-local`() = runTest {
        withContext(RuntimeTraceContextElement(ctx("op_disp"))) {
            withContext(Dispatchers.Default) {
                assertEquals("op_disp", RuntimeTraceContextElement.activeOnThread()?.runtimeOperationId)
            }
        }
    }
}
