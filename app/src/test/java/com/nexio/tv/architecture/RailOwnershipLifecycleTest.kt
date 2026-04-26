package com.nexio.tv.architecture

import org.junit.Assert.assertTrue
import org.junit.Test

class RailOwnershipLifecycleTest {
    @Test
    fun `legacy snapshot ownership paths are retired in favor of rail store`() {
        val offenders = sourceTextScan(
            forbiddenPatterns = listOf(
                "replaceHomeFeedReferences(",
                "removeHomeUnreferencedMetaEntries(",
                "home_ref::"
            ),
            allowedPaths = listOf(
                "app/src/test/",
                "docs/architecture/"
            )
        )

        assertTrue("Legacy snapshot ownership paths still exist: $offenders", offenders.isEmpty())
    }
}
