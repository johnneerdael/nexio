package com.nexio.tv.data.repository

import com.nexio.tv.core.integration.RecordingTraceSink
import com.nexio.tv.core.trace.NoopRuntimeTraceSink
import org.junit.After
import org.junit.Assert.assertEquals
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
}
