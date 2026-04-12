package com.nexio.tv.data.local

import org.junit.Assert.assertEquals
import org.junit.Test

class StreamingCacheDebugModeTest {

    @Test
    fun fromStorageValue_defaultsToPhase4CoverageWithFill() {
        assertEquals(
            StreamingCacheDebugMode.PHASE4_COVERAGE_WITH_FILL,
            StreamingCacheDebugMode.fromStorageValue(null)
        )
        assertEquals(
            StreamingCacheDebugMode.PHASE4_COVERAGE_WITH_FILL,
            StreamingCacheDebugMode.fromStorageValue("unknown")
        )
    }

    @Test
    fun next_cyclesThroughDiagnosticModes() {
        assertEquals(
            StreamingCacheDebugMode.PHASE3_CACHE_WITH_FILL,
            StreamingCacheDebugMode.PHASE4_COVERAGE_WITH_FILL.next()
        )
        assertEquals(
            StreamingCacheDebugMode.COVERAGE_ONLY,
            StreamingCacheDebugMode.PHASE3_CACHE_WITH_FILL.next()
        )
        assertEquals(
            StreamingCacheDebugMode.PHASE4_COVERAGE_WITH_FILL,
            StreamingCacheDebugMode.COVERAGE_ONLY.next()
        )
    }
}
