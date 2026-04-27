package com.nexio.tv.core.integration

import com.nexio.tv.core.trace.NoopRuntimeTraceSink
import com.nexio.tv.core.trace.RuntimeTraceSink
import com.nexio.tv.core.trace.TraceEventEnvelope
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileBoundaryCheckTraceTest {
    private val sink = object : RuntimeTraceSink {
        val events = mutableListOf<TraceEventEnvelope<*>>()
        override fun emit(event: TraceEventEnvelope<*>) { events += event }
        override fun eventsWritten(): Long = events.size.toLong()
        override fun eventsDropped(): Long = 0L
    }

    @After
    fun tearDown() {
        ProfileBoundaryEnforcer.installTraceSink(NoopRuntimeTraceSink) { null }
    }

    @Test
    fun `successful global validation emits PASS verdict`() {
        ProfileBoundaryEnforcer.installTraceSink(sink) { "s1" }

        ProfileBoundaryEnforcer.validateRequest(
            provider = IntegrationProvider.TMDB,
            scope = IntegrationScope.GlobalContent,
            cacheKey = "metadata:tmdb:550",
            profileContext = null
        )

        val checks = sink.events.filter { it.eventType == "profile.boundary_check" }
        assertEquals(1, checks.size)
        val payload = checks.first().payload as Map<*, *>
        assertEquals("TMDB", payload["provider"])
        assertEquals("PASS", payload["verdict"])
    }

    @Test
    fun `global key with profile token emits FAIL verdict`() {
        ProfileBoundaryEnforcer.installTraceSink(sink) { "s1" }

        assertThrows(ProfileBoundaryException::class.java) {
            ProfileBoundaryEnforcer.validateRequest(
                provider = IntegrationProvider.TMDB,
                scope = IntegrationScope.GlobalContent,
                cacheKey = "metadata:profile:1:resolved",
                profileContext = null
            )
        }

        val failures = sink.events.filter {
            it.eventType == "profile.boundary_check" &&
                (it.payload as Map<*, *>)["verdict"] == "FAIL"
        }
        assertTrue(
            "expected at least one FAIL boundary check, got: ${sink.events}",
            failures.isNotEmpty()
        )
    }
}
