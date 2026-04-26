package com.nexio.tv.architecture

import org.junit.Assert.fail
import org.junit.Test

class IntegrationBoundaryTest {
    @Test
    fun `non integration packages do not reference provider retrofit apis directly across the whole tree`() {
        val forbiddenTypeNames = productionRemoteApiSurfaceSimpleNames("/com/nexio/tv/data/remote/api/")

        val offenders = productionRegexScan(
            forbiddenPatterns = forbiddenTypeNames.associateWith { Regex("""\b${Regex.escape(it)}\b""") },
            allowedPaths = productionAllowedPathSuffixes(
                "/com/nexio/tv/core/di/",
                "/com/nexio/tv/core/tvdb/TvdbAuthService.kt",
                "/com/nexio/tv/data/integration/",
                "/com/nexio/tv/data/remote/api/",
                "/com/nexio/tv/data/repository/KitsuAuthService.kt",
                "/com/nexio/tv/data/repository/RealDebridAuthService.kt",
                "/com/nexio/tv/data/repository/SimklAuthService.kt"
            )
        )

        if (offenders.isNotEmpty()) {
            fail("Direct provider API usage outside integration boundary: $offenders")
        }
    }
}
