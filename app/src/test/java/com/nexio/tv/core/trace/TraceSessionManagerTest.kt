package com.nexio.tv.core.trace

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TraceSessionManagerTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun manager() = TraceSessionManager(
        tracesRoot = tmp.newFolder("traces"),
        gson = Gson(),
        clock = { 1_700_000_000_000L },
        buildInfo = TraceBuildInfo(
            appVersion = "1.0",
            buildType = "debug",
            gitSha = null,
            deviceModel = "Pixel",
            androidVersion = "14"
        )
    )

    @Test
    fun `before start the active sink is Noop`() {
        val mgr = manager()
        assertSame(NoopRuntimeTraceSink, mgr.activeSink())
        assertNull(mgr.activeSession())
    }

    @Test
    fun `start with OFF stays Noop`() {
        val mgr = manager()
        mgr.start(TraceMode.OFF, activeProfileHash = null)
        assertSame(NoopRuntimeTraceSink, mgr.activeSink())
    }

    @Test
    fun `start with non-OFF mode creates File sink and session`() {
        val mgr = manager()
        mgr.start(TraceMode.SAFE_METADATA_RUNTIME, activeProfileHash = "ph_abc")
        val s = mgr.activeSession()
        assertTrue(s!!.traceSessionId.isNotBlank())
        assertEquals(TraceMode.SAFE_METADATA_RUNTIME, s.mode)
        assertEquals("ph_abc", s.activeProfileHash)
        assertTrue(mgr.activeSink() is FileRuntimeTraceSink)
    }

    @Test
    fun `stop reverts to Noop`() {
        val mgr = manager()
        mgr.start(TraceMode.SAFE_METADATA_RUNTIME, activeProfileHash = null)
        mgr.stop()
        assertSame(NoopRuntimeTraceSink, mgr.activeSink())
        assertNull(mgr.activeSession())
    }
}
