package com.nexio.tv.core.trace

import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.core.integration.IntegrationScope
import com.nexio.tv.core.integration.IntegrationWorkClass
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

class RuntimeTraceContextRequestTaggingInterceptorTest {
    private fun ctx() = RuntimeTraceContext(
        traceSessionId = "s",
        runtimeOperationId = "op_bridge",
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

    private fun observingClient(observed: AtomicReference<RuntimeTraceContext?>): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(RuntimeTraceContextRequestTaggingInterceptor())
            .addInterceptor(Interceptor { chain ->
                observed.set(chain.request().tag(RuntimeTraceContext::class.java))
                chain.proceed(chain.request())
            })
            .build()

    @Test
    fun `bridge tags request when active on thread via withContext`() = runTest {
        val server = MockWebServer().apply { enqueue(MockResponse().setResponseCode(200)) }
        server.start()
        val observed = AtomicReference<RuntimeTraceContext?>()
        val client = observingClient(observed)

        withContext(Dispatchers.IO + RuntimeTraceContextElement(ctx())) {
            client.newCall(Request.Builder().url(server.url("/x")).build()).execute().close()
        }

        assertEquals("op_bridge", observed.get()?.runtimeOperationId)
        server.shutdown()
    }

    @Test
    fun `bridge does not overwrite an explicit pre-existing tag`() = runTest {
        val server = MockWebServer().apply { enqueue(MockResponse().setResponseCode(200)) }
        server.start()
        val observed = AtomicReference<RuntimeTraceContext?>()
        val client = observingClient(observed)
        val explicit = ctx().copy(runtimeOperationId = "op_explicit")

        withContext(Dispatchers.IO + RuntimeTraceContextElement(ctx())) {
            client.newCall(
                Request.Builder()
                    .url(server.url("/x"))
                    .tag(RuntimeTraceContext::class.java, explicit)
                    .build()
            ).execute().close()
        }

        assertEquals("explicit tag wins", "op_explicit", observed.get()?.runtimeOperationId)
        server.shutdown()
    }

    @Test
    fun `untagged request without active context emits no tag`() = runTest {
        val server = MockWebServer().apply { enqueue(MockResponse().setResponseCode(200)) }
        server.start()
        val observed = AtomicReference<RuntimeTraceContext?>()
        val client = observingClient(observed)

        // No withContext wrapper — no thread-local context.
        client.newCall(Request.Builder().url(server.url("/x")).build()).execute().close()

        assertNull(observed.get())
        server.shutdown()
    }
}
