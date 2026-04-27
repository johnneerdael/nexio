package com.nexio.tv.core.trace

import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.core.integration.IntegrationScope
import com.nexio.tv.core.integration.IntegrationWorkClass
import com.nexio.tv.core.integration.RecordingTraceSink
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeTraceEventListenerTest {
    private fun ctx() = RuntimeTraceContext(
        traceSessionId = "s1",
        runtimeOperationId = "op_t",
        provider = IntegrationProvider.TMDB,
        apiShapeId = "tmdb.movie.core",
        operationKey = "k",
        cacheKey = null,
        workClass = IntegrationWorkClass.USER_VISIBLE,
        scope = IntegrationScope.GlobalContent,
        profileHash = null,
        accountProvider = null,
        credentialHash = null
    )

    private fun fixedMode(mode: TraceMode) = object : TraceModeProvider {
        override val current: TraceMode = mode
        override fun observe(): Flow<TraceMode> = flowOf(mode)
    }

    @Test
    fun `emits at least one http_timing event under summary mode`() {
        val server = MockWebServer().apply { enqueue(MockResponse().setResponseCode(200).setBody("ok")) }
        server.start()
        val sink = RecordingTraceSink()
        val factory = EventListener.Factory { call ->
            RuntimeTraceEventListener(
                sink = sink,
                modeProvider = fixedMode(TraceMode.INCLUDE_HTTP_SUMMARY),
                contextResolver = { call.request().tag(RuntimeTraceContext::class.java) }
            )
        }
        val client = OkHttpClient.Builder().eventListenerFactory(factory).build()

        val request = Request.Builder()
            .url(server.url("/x"))
            .tag(RuntimeTraceContext::class.java, ctx())
            .build()
        client.newCall(request).execute().close()

        val timings = sink.events.filter { it.eventType == "http.timing" }
        assertTrue("expected at least one http.timing event, got: ${sink.events.map { it.eventType }}", timings.isNotEmpty())
        val payload = timings.first().payload as Map<*, *>
        assertTrue("payload must include phase", payload.containsKey("phase"))
        assertTrue("payload must include runtimeOperationId", payload.containsKey("runtimeOperationId"))

        server.shutdown()
    }

    @Test
    fun `OFF mode emits nothing`() {
        val server = MockWebServer().apply { enqueue(MockResponse().setResponseCode(200)) }
        server.start()
        val sink = RecordingTraceSink()
        val factory = EventListener.Factory { call ->
            RuntimeTraceEventListener(
                sink = sink,
                modeProvider = fixedMode(TraceMode.OFF),
                contextResolver = { call.request().tag(RuntimeTraceContext::class.java) }
            )
        }
        val client = OkHttpClient.Builder().eventListenerFactory(factory).build()
        val request = Request.Builder().url(server.url("/x")).tag(RuntimeTraceContext::class.java, ctx()).build()
        client.newCall(request).execute().close()
        assertTrue("OFF mode emits no timing events", sink.events.isEmpty())
        server.shutdown()
    }
}
