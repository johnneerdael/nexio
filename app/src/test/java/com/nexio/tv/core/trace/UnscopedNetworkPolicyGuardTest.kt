package com.nexio.tv.core.trace

import com.nexio.tv.core.integration.RecordingTraceSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class UnscopedNetworkPolicyGuardTest {
    @Test
    fun `release build emits policy event without throwing`() {
        val sink = RecordingTraceSink()
        val guard = UnscopedNetworkPolicyGuard(sink, sessionId = { "s1" }, isInternalBuild = false)
        guard.reportUnscoped("GET", "https://example.com/x")
        assertEquals(1, sink.events.size)
        val event = sink.events.first()
        assertEquals("policy.unscoped_network_call", event.eventType)
        val payload = event.payload as Map<*, *>
        assertEquals("GET", payload["method"])
        assertEquals("https://example.com/x", payload["url"])
        assertEquals(false, payload["isInternalBuild"])
    }

    @Test
    fun `internal build emits and then throws`() {
        val sink = RecordingTraceSink()
        val guard = UnscopedNetworkPolicyGuard(sink, sessionId = { "s1" }, isInternalBuild = true)
        val ex = assertThrows(IllegalStateException::class.java) {
            guard.reportUnscoped("POST", "https://example.com/y")
        }
        assertTrue(ex.message!!.contains("UNSCOPED_NETWORK_CALL"))
        assertEquals(1, sink.events.size)
    }
}
