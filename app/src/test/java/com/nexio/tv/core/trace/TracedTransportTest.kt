package com.nexio.tv.core.trace

import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.core.integration.IntegrationScope
import com.nexio.tv.core.integration.IntegrationWorkClass
import com.nexio.tv.core.integration.RecordingTraceSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TracedTransportTest {
    private val ctx = RuntimeTraceContext(
        traceSessionId = "s1",
        runtimeOperationId = "op_t",
        provider = IntegrationProvider.TMDB,
        apiShapeId = "raw.probe",
        operationKey = "k",
        cacheKey = null,
        workClass = IntegrationWorkClass.USER_VISIBLE,
        scope = IntegrationScope.GlobalContent,
        profileHash = null,
        accountProvider = null,
        credentialHash = null
    )

    @Test
    fun `emits request, response with status, and returns lambda result`() {
        val sink = RecordingTraceSink()
        val transport = TracedTransport(sink, TraceRedactor())

        val result = transport.execute(
            ctx = ctx,
            method = "GET",
            url = "https://example.com/probe?api_key=ABC123",
            block = { TracedResponse(statusCode = 206, byteCount = 12345L, payload = "ok") }
        )

        assertEquals("ok", result)

        val types = sink.events.map { it.eventType }
        assertTrue("expected http.request in $types", types.contains("http.request"))
        assertTrue("expected http.response in $types", types.contains("http.response"))

        val req = sink.events.first { it.eventType == "http.request" }.payload as Map<*, *>
        val url = req["url"] as String
        assertTrue("URL must redact api_key: $url", url.contains("api_key=<redacted>"))

        val resp = sink.events.first { it.eventType == "http.response" }.payload as Map<*, *>
        assertEquals(206, resp["statusCode"])
        assertEquals(12345L, resp["byteCount"])
    }

    @Test
    fun `emits http_error and rethrows on exception`() {
        val sink = RecordingTraceSink()
        val transport = TracedTransport(sink, TraceRedactor())

        val ex = assertThrows(IllegalStateException::class.java) {
            transport.execute<String>(
                ctx = ctx,
                method = "GET",
                url = "https://example.com/x",
                block = { throw IllegalStateException("boom") }
            )
        }
        assertEquals("boom", ex.message)

        val types = sink.events.map { it.eventType }
        assertTrue("expected http.error in $types", types.contains("http.error"))
        // No http.response when the call throws
        assertEquals(0, sink.events.count { it.eventType == "http.response" })
    }
}
