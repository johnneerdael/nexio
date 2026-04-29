package com.nexio.tv.core.trace

import com.nexio.tv.BuildConfig
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F2-I-01 pin: TraceBuildInfo.gitSha MUST be non-null. Trace bundles need this for
 * bundle→commit correlation.
 */
class TraceBuildInfoGitShaTest {

    @Test
    fun `BuildConfig GIT_SHA is non-null and looks like a sha or local-dev`() {
        val sha = BuildConfig.GIT_SHA
        assertNotNull("BuildConfig.GIT_SHA must be non-null", sha)
        assertTrue("BuildConfig.GIT_SHA must be non-empty", sha.isNotEmpty())
        assertTrue(
            "BuildConfig.GIT_SHA looks like a git SHA (40 hex chars or 'local-dev')",
            sha == "local-dev" || sha.matches(Regex("[0-9a-f]{7,40}"))
        )
    }
}
