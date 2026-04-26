package com.nexio.tv.architecture

import org.junit.Assert.fail
import org.junit.Test

class NoDirectProviderApiOutsideIntegrationPackagesFullTreeTest {
    @Test
    fun `provider apis are only referenced from integration packages across the full tree`() {
        val forbiddenTypeNames = productionRemoteApiSurfaceSimpleNames("/com/nexio/tv/data/remote/api/")

        val offenders = productionRegexScan(
            forbiddenPatterns = forbiddenTypeNames.associateWith { Regex("""\b${Regex.escape(it)}\b""") },
            allowedPaths = productionAllowedPathSuffixes(
                "/com/nexio/tv/core/di/",
                "/com/nexio/tv/data/integration/",
                "/com/nexio/tv/data/remote/api/",
                "/com/nexio/tv/data/repository/KitsuAuthService.kt"
            )
        )

        if (offenders.isNotEmpty()) {
            fail("Direct provider API usage escaped the integration boundary: $offenders")
        }
    }
}
