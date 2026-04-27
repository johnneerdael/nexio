package com.nexio.tv.core.trace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TraceModeTest {
    @Test
    fun `OFF produces no http or runtime emission`() {
        assertFalse(TraceMode.OFF.includesRuntime)
        assertFalse(TraceMode.OFF.includesHttpSummary)
        assertFalse(TraceMode.OFF.includesHttpBodies)
    }

    @Test
    fun `SAFE_METADATA_RUNTIME enables runtime but not http`() {
        val m = TraceMode.SAFE_METADATA_RUNTIME
        assertTrue(m.includesRuntime)
        assertFalse(m.includesHttpSummary)
        assertFalse(m.includesHttpBodies)
    }

    @Test
    fun `INCLUDE_HTTP_SUMMARY enables runtime + http summary`() {
        val m = TraceMode.INCLUDE_HTTP_SUMMARY
        assertTrue(m.includesRuntime)
        assertTrue(m.includesHttpSummary)
        assertFalse(m.includesHttpBodies)
    }

    @Test
    fun `INCLUDE_HTTP_BODIES_INTERNAL_ONLY enables everything`() {
        val m = TraceMode.INCLUDE_HTTP_BODIES_INTERNAL_ONLY
        assertTrue(m.includesRuntime)
        assertTrue(m.includesHttpSummary)
        assertTrue(m.includesHttpBodies)
    }

    @Test
    fun `parse returns OFF for unknown string`() {
        assertEquals(TraceMode.OFF, TraceMode.parse("garbage"))
        assertEquals(TraceMode.SAFE_METADATA_RUNTIME, TraceMode.parse("SAFE_METADATA_RUNTIME"))
    }
}
