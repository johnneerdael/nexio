package com.nexio.tv.core.trace

import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.core.integration.IntegrationScope
import com.nexio.tv.core.integration.IntegrationWorkClass
import com.nexio.tv.core.integration.RecordingTraceSink
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression: when a downstream client adds an auth interceptor via newBuilder(),
 * the trace interceptor (registered as a network interceptor on the root client)
 * must still see the post-auth request, not the bare pre-auth request.
 */
class TraceInterceptorOrderingTest {
    private fun ctx() = RuntimeTraceContext(
        traceSessionId = "s1",
        runtimeOperationId = "op_x",
        provider = IntegrationProvider.TRAKT,
        apiShapeId = "trakt.user.lists",
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
    fun `trace interceptor as network interceptor sees post-auth request added by derived client`() {
        val server = MockWebServer().apply { enqueue(MockResponse().setResponseCode(200).setBody("ok")) }
        server.start()
        val sink = RecordingTraceSink()

        // Build the root client with the trace as a NETWORK interceptor (the fix).
        val root = OkHttpClient.Builder()
            .addNetworkInterceptor(RuntimeTraceInterceptor(
                sink = sink,
                redactor = TraceRedactor(),
                modeProvider = fixedMode(TraceMode.INCLUDE_HTTP_SUMMARY),
                unscopedGuard = UnscopedNetworkPolicyGuard(sink, sessionId = { "s1" }, isInternalBuild = false)
            ))
            .build()

        // Derive a Trakt-like client that adds an auth interceptor via newBuilder().
        val derived = root.newBuilder()
            .addInterceptor(Interceptor { chain ->
                val tagged = chain.request().newBuilder()
                    .header("Authorization", "Bearer SHOULD_BE_REDACTED")
                    .build()
                chain.proceed(tagged)
            })
            .build()

        val request = Request.Builder()
            .url(server.url("/users/me"))
            .tag(RuntimeTraceContext::class.java, ctx())
            .build()
        derived.newCall(request).execute().close()

        val req = sink.events.first { it.eventType == "http.request" }
        @Suppress("UNCHECKED_CAST")
        val headers = (req.payload as Map<*, *>)["headers"] as Map<String, String>
        // Must observe the auth header (proves trace ran AFTER the derived auth interceptor)
        // AND must redact it.
        assertEquals("trace must see the auth header added by derived client and redact it",
            "<redacted>", headers["Authorization"])

        server.shutdown()
    }
}
