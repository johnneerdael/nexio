package com.nexio.tv.data.repository

import com.nexio.tv.core.integration.RecordingTraceSink
import com.nexio.tv.core.trace.NoopRuntimeTraceSink
import com.nexio.tv.core.trace.TraceMetadataEvents
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContinueWatchingSnapshotTraceTest {
    @After
    fun tearDown() {
        ContinueWatchingSnapshotService.installTraceSink(NoopRuntimeTraceSink) { null }
    }

    @Test
    fun `installTraceSink accepts a non-noop sink without driving real writes`() {
        // Smoke test: the static slot is installable. We cannot easily drive a real
        // snapshot write without the full service graph. Concrete write-emission is
        // covered by integration-level QA (manual playbook flow E in spec section 15)
        // and the validator rule (Task 29) verifies event presence in captured traces.
        val sink = RecordingTraceSink()
        ContinueWatchingSnapshotService.installTraceSink(sink) { "s1" }
        assertEquals(0, sink.events.size)
    }

    @Test
    fun `home profile session trace helpers hash profile and session identifiers`() {
        val source = File("app/src/main/java/com/nexio/tv/core/trace/TraceMetadataEvents.kt").readText()

        listOf(
            "emitHomeProfileSessionStarted",
            "emitHomeProfileSessionCancelled",
            "emitHomeProfileEmissionIgnoredStale",
            "emitHomeInitialGateStateChanged"
        ).forEach { method ->
            assertTrue("Missing $method", source.contains("fun $method("))
        }

        listOf(
            "home.profile_session_started",
            "home.profile_session_cancelled",
            "home.profile_emission_ignored_stale",
            "home.initial_gate_state_changed"
        ).forEach { eventType ->
            assertTrue("Missing event type $eventType", source.contains("\"$eventType\""))
        }

        assertTrue(source.contains("TraceHash.of"))
        assertTrue(source.contains("\"profileHash\""))
        assertTrue(source.contains("\"sessionHash\""))
        assertFalse(source.contains("\"profileId\" to profileId"))
        assertFalse(source.contains("\"sessionId\" to sessionId"))
    }

    @Test
    fun `home profile session trace helpers emit hashed payload identifiers`() {
        val sink = RecordingTraceSink()
        val traceEvents = TraceMetadataEvents(sink) { "trace-session" }
        val profileId = 2
        val sessionId = "home-profile:2:runtime:7"

        traceEvents.emitHomeProfileSessionStarted(profileId, sessionId, generation = 1L)
        traceEvents.emitHomeProfileSessionCancelled(profileId, sessionId, reason = "profile_session_replaced")
        traceEvents.emitHomeProfileEmissionIgnoredStale("snapshot_apply", profileId, sessionId)
        traceEvents.emitHomeInitialGateStateChanged(
            profileId = profileId,
            sessionId = sessionId,
            gate = "CONTINUE_WATCHING",
            state = "resolved",
            reason = "first_snapshot"
        )

        assertEquals(
            listOf(
                "home.profile_session_started",
                "home.profile_session_cancelled",
                "home.profile_emission_ignored_stale",
                "home.initial_gate_state_changed"
            ),
            sink.events.map { it.eventType }
        )
        sink.events.forEach { event ->
            val payload = event.payload as Map<*, *>
            assertTrue(payload.containsKey("profileHash"))
            assertTrue(payload.containsKey("sessionHash"))
            assertFalse(payload.containsKey("profileId"))
            assertFalse(payload.containsKey("sessionId"))
            assertFalse(payload["profileHash"] == profileId.toString())
            assertFalse(payload["sessionHash"] == sessionId)
        }
    }

    @Test
    fun `logcat sink exposes curated home profile session fields from hashes`() {
        val source = File("app/src/main/java/com/nexio/tv/core/trace/LogcatRuntimeTraceSink.kt").readText()

        listOf(
            "home.profile_session_started",
            "home.profile_session_cancelled",
            "home.profile_emission_ignored_stale",
            "home.initial_gate_state_changed"
        ).forEach { eventType ->
            assertTrue("Missing logcat case $eventType", source.contains("\"$eventType\" -> linkedMapOf("))
        }

        assertTrue(source.contains("\"profile\" to payload[\"profileHash\"]"))
        assertTrue(source.contains("\"session\" to payload[\"sessionHash\"]"))
        assertTrue(source.contains("\"generation\" to payload[\"generation\"]"))
        assertTrue(source.contains("\"source\" to payload[\"source\"]"))
        assertTrue(source.contains("\"gate\" to payload[\"gate\"]"))
        assertTrue(source.contains("\"state\" to payload[\"state\"]"))
        assertTrue(source.contains("\"reason\" to payload[\"reason\"]"))
    }

    @Test
    fun `home view model traces session lifecycle gate transitions and stale continue watching emissions`() {
        val homeViewModelSource = File("app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModel.kt").readText()
        val continueWatchingSource =
            File("app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelContinueWatching.kt").readText()

        assertTrue(homeViewModelSource.contains("traceEvents.emitHomeProfileSessionCancelled("))
        assertTrue(homeViewModelSource.contains("activeHomeProfileSessionSnapshot = session"))
        assertTrue(homeViewModelSource.contains("traceEvents.emitHomeProfileSessionStarted("))
        assertTrue(homeViewModelSource.contains("emitInitialHomeProfileSessionStarted()"))
        assertFalse(homeViewModelSource.contains("previousSession.sessionId != session.sessionId"))
        assertTrue(continueWatchingSource.contains("traceEvents.emitHomeInitialGateStateChanged("))
        assertTrue(continueWatchingSource.contains("traceEvents.emitHomeProfileEmissionIgnoredStale("))
        assertFalse(continueWatchingSource.contains("var continueWatchingGateResolved"))
        assertFalse(continueWatchingSource.contains("var enrichedContinueWatchingGateResolved"))
        assertFalse(continueWatchingSource.contains("session=\${session.sessionId}"))
    }
}
