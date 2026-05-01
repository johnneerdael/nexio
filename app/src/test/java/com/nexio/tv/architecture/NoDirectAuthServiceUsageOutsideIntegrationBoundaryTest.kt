package com.nexio.tv.architecture

import org.junit.Assert.fail
import org.junit.Test

class NoDirectAuthServiceUsageOutsideIntegrationBoundaryTest {
    @Test
    fun `non adapter code does not depend on auth services directly`() {
        val forbiddenSimpleNames = setOf(
                "TraktAuthService",
                "SimklAuthService",
                "KitsuAuthService",
                "TvdbAuthService",
                "RealDebridAuthService"
            )
        val offenders = productionRegexScan(
            forbiddenPatterns = forbiddenSimpleNames.associateWith { Regex("""\b${Regex.escape(it)}\b""") },
            allowedPaths = productionAllowedPathSuffixes(
                "/com/nexio/tv/core/di/",
                "/com/nexio/tv/core/tvdb/TvdbAuthService.kt",
                "/com/nexio/tv/data/integration/",
                "/com/nexio/tv/data/repository/KitsuAuthService.kt",
                "/com/nexio/tv/data/repository/KitsuSettingsAuthGateway.kt",
                "/com/nexio/tv/data/repository/RealDebridAuthService.kt",
                "/com/nexio/tv/data/repository/RealDebridSettingsAuthGateway.kt",
                "/com/nexio/tv/data/repository/SimklAuthService.kt",
                "/com/nexio/tv/data/repository/SimklSettingsAuthGateway.kt",
                "/com/nexio/tv/data/repository/TraktAuthService.kt",
                "/com/nexio/tv/data/repository/TraktRepositoryAuthGateway.kt",
                "/com/nexio/tv/data/repository/TraktSettingsAuthGateway.kt",
                "/com/nexio/tv/core/tvdb/TvdbSettingsAuthGateway.kt",
                "/com/nexio/tv/data/remote/SimklRequestGate.kt",
                "/com/nexio/tv/data/remote/TraktRequestGate.kt",
                // TraktCatalogRailSource references TraktAuthService only in a KDoc comment
                // explaining the session-derivation caveat for global rails. No real injection.
                "/com/nexio/tv/data/catalog/rails/TraktCatalogRailSource.kt"
            )
        )

        if (offenders.isNotEmpty()) {
            fail("Direct auth service usage escaped the integration boundary: $offenders")
        }
    }
}
