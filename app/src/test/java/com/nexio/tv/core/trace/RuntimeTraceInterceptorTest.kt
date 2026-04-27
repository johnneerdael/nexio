package com.nexio.tv.core.trace

import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.core.integration.IntegrationScope
import com.nexio.tv.core.integration.IntegrationWorkClass
import com.nexio.tv.core.integration.RecordingTraceSink
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeTraceInterceptorTest {
    private fun ctx(opId: String = "op_x") = RuntimeTraceContext(
        traceSessionId = "s1",
        runtimeOperationId = opId,
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

    private fun fixedMode(mode: TraceMode) = object : TraceModeProvider {
        override val current: TraceMode = mode
        override fun observe(): Flow<TraceMode> = flowOf(mode)
    }

    @Test
    fun `tagged request emits sanitized http_request and http_response`() {
        val server = MockWebServer().apply { enqueue(MockResponse().setResponseCode(200).setBody("ok")) }
        server.start()
        val sink = RecordingTraceSink()
        val client = OkHttpClient.Builder()
            .addInterceptor(RuntimeTraceInterceptor(
                sink = sink,
                redactor = TraceRedactor(),
                modeProvider = fixedMode(TraceMode.INCLUDE_HTTP_SUMMARY),
                unscopedGuard = UnscopedNetworkPolicyGuard(sink, sessionId = { "s1" }, isInternalBuild = false)
            ))
            .build()

        val request = Request.Builder()
            .url(server.url("/movie/550?api_key=ABC123"))
            .header("Authorization", "Bearer SECRET")
            .tag(RuntimeTraceContext::class.java, ctx())
            .build()
        client.newCall(request).execute().close()

        val types = sink.events.map { it.eventType }
        assertTrue("expected http.request in $types", types.contains("http.request"))
        assertTrue("expected http.response in $types", types.contains("http.response"))

        val req = sink.events.first { it.eventType == "http.request" }
        val payload = req.payload as Map<*, *>
        assertEquals("op_x", payload["runtimeOperationId"])
        val url = payload["url"] as String
        assertTrue("URL must redact api_key: $url", url.contains("api_key=<redacted>"))
        assertFalse("URL must not contain ABC123: $url", url.contains("ABC123"))
        @Suppress("UNCHECKED_CAST")
        val headers = payload["headers"] as Map<String, String>
        assertEquals("<redacted>", headers["Authorization"])

        server.shutdown()
    }

    @Test
    fun `untagged request emits unscoped policy violation`() {
        val server = MockWebServer().apply { enqueue(MockResponse().setResponseCode(200)) }
        server.start()
        val sink = RecordingTraceSink()
        val client = OkHttpClient.Builder()
            .addInterceptor(RuntimeTraceInterceptor(
                sink = sink,
                redactor = TraceRedactor(),
                modeProvider = fixedMode(TraceMode.INCLUDE_HTTP_SUMMARY),
                unscopedGuard = UnscopedNetworkPolicyGuard(sink, sessionId = { "s1" }, isInternalBuild = false)
            ))
            .build()

        val request = Request.Builder().url(server.url("/x")).build() // no tag
        client.newCall(request).execute().close()

        val types = sink.events.map { it.eventType }
        assertTrue("expected policy.unscoped_network_call in $types", types.contains("policy.unscoped_network_call"))
        // No http.request / http.response when unscoped — only the policy event
        assertFalse("must not emit http.request when unscoped", types.contains("http.request"))

        server.shutdown()
    }

    @Test
    fun `OFF mode emits neither http nor policy events for tagged request`() {
        val server = MockWebServer().apply { enqueue(MockResponse().setResponseCode(200)) }
        server.start()
        val sink = RecordingTraceSink()
        val client = OkHttpClient.Builder()
            .addInterceptor(RuntimeTraceInterceptor(
                sink = sink,
                redactor = TraceRedactor(),
                modeProvider = fixedMode(TraceMode.OFF),
                unscopedGuard = UnscopedNetworkPolicyGuard(sink, sessionId = { "s1" }, isInternalBuild = false)
            ))
            .build()

        val request = Request.Builder()
            .url(server.url("/x"))
            .tag(RuntimeTraceContext::class.java, ctx())
            .build()
        client.newCall(request).execute().close()

        assertTrue("OFF mode must not emit any trace events", sink.events.isEmpty())
        server.shutdown()
    }
}
