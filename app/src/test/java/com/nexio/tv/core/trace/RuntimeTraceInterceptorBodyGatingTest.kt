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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class RuntimeTraceInterceptorBodyGatingTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

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

    private fun runRequest(mode: TraceMode, isInternalBuild: Boolean): RecordingTraceSink {
        val sink = RecordingTraceSink()
        val interceptor = RuntimeTraceInterceptor(
            sink = sink,
            redactor = TraceRedactor(),
            modeProvider = fixedMode(mode),
            unscopedGuard = UnscopedNetworkPolicyGuard(sink, sessionId = { "s1" }, isInternalBuild = isInternalBuild),
            isInternalBuild = isInternalBuild
        )
        val client = OkHttpClient.Builder().addInterceptor(interceptor).build()
        server.enqueue(
            MockResponse()
                .setBody("""{"hello":"world"}""")
                .addHeader("Content-Type", "application/json")
        )
        val request = Request.Builder()
            .url(server.url("/test"))
            .tag(RuntimeTraceContext::class.java, ctx())
            .build()
        client.newCall(request).execute().close()
        return sink
    }

    @Test
    fun `INCLUDE_HTTP_SUMMARY with isInternalBuild=true does NOT emit body_sample`() {
        val sink = runRequest(TraceMode.INCLUDE_HTTP_SUMMARY, isInternalBuild = true)
        assertEquals(0, sink.events.count { it.eventType == "trace.body_sample" })
    }

    @Test
    fun `INCLUDE_HTTP_SUMMARY with isInternalBuild=false does NOT emit body_sample`() {
        val sink = runRequest(TraceMode.INCLUDE_HTTP_SUMMARY, isInternalBuild = false)
        assertEquals(0, sink.events.count { it.eventType == "trace.body_sample" })
    }

    @Test
    fun `INCLUDE_HTTP_BODIES_INTERNAL_ONLY with isInternalBuild=false does NOT emit body_sample`() {
        val sink = runRequest(TraceMode.INCLUDE_HTTP_BODIES_INTERNAL_ONLY, isInternalBuild = false)
        assertEquals(0, sink.events.count { it.eventType == "trace.body_sample" })
    }

    @Test
    fun `INCLUDE_HTTP_BODIES_INTERNAL_ONLY with isInternalBuild=true DOES emit body_sample`() {
        val sink = runRequest(TraceMode.INCLUDE_HTTP_BODIES_INTERNAL_ONLY, isInternalBuild = true)
        assertEquals(1, sink.events.count { it.eventType == "trace.body_sample" })
    }

    @Test
    fun `OFF mode never emits anything`() {
        val sink = runRequest(TraceMode.OFF, isInternalBuild = true)
        assertEquals(0, sink.events.size)
    }

    // F2-I-04: SAFE_METADATA_RUNTIME must never capture bodies regardless of build type.
    @Test
    fun `SAFE_METADATA_RUNTIME mode does not emit body_sample in internal build`() {
        val sink = runRequest(TraceMode.SAFE_METADATA_RUNTIME, isInternalBuild = true)
        assertEquals(
            "SAFE_METADATA_RUNTIME must not emit trace.body_sample even in internal builds",
            0,
            sink.events.count { it.eventType == "trace.body_sample" }
        )
    }

    @Test
    fun `SAFE_METADATA_RUNTIME mode does not emit body_sample in release build`() {
        val sink = runRequest(TraceMode.SAFE_METADATA_RUNTIME, isInternalBuild = false)
        assertEquals(
            "SAFE_METADATA_RUNTIME must not emit trace.body_sample in release builds",
            0,
            sink.events.count { it.eventType == "trace.body_sample" }
        )
    }
}
