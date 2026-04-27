package com.nexio.tv.core.trace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertNotNull
import org.junit.Test

class TraceSessionTest {
    @Test
    fun `session requires non-blank id`() {
        assertThrows(IllegalArgumentException::class.java) {
            TraceSession(
                traceSessionId = "",
                startedAtEpochMs = 1L,
                appVersion = "1.0",
                buildType = "debug",
                gitSha = null,
                deviceModel = "Pixel",
                androidVersion = "14",
                activeProfileHash = null,
                mode = TraceMode.SAFE_METADATA_RUNTIME,
                salt = "salt"
            )
        }
    }

    @Test
    fun `envelope wraps payload with sequence and metadata`() {
        val payload = mapOf("k" to "v")
        val envelope = TraceEventEnvelope(
            traceSessionId = "s1",
            sequence = 7L,
            wallClockMs = 1_700_000_000_000L,
            elapsedRealtimeMs = 5_000L,
            threadName = "test-thread",
            eventType = "metadata.route_decision",
            payload = payload
        )
        assertEquals(1, envelope.schemaVersion)
        assertEquals("s1", envelope.traceSessionId)
        assertEquals(7L, envelope.sequence)
        assertEquals("metadata.route_decision", envelope.eventType)
        assertNotNull(envelope.payload)
    }
}
