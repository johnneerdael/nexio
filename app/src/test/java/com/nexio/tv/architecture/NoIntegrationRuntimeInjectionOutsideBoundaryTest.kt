package com.nexio.tv.architecture

import org.junit.Assert.fail
import org.junit.Test

class NoIntegrationRuntimeInjectionOutsideBoundaryTest {
    @Test
    fun `feature and presentation packages do not inject integration runtime directly across the whole tree`() {
        val rawOffenders = architectureScan(
            allowedPackages = setOf(
                "com.nexio.tv.core.anime",
                "com.nexio.tv.data.integration",
                "com.nexio.tv.core.integration",
                "com.nexio.tv.core.di"
                // NOTE: core.tmdb and core.tvdb removed (F2-A-03 / F2-J-04) — they have zero
                // actual IntegrationRuntime/IntegrationSpec uses and were stale allowlist entries.
            ),
            forbiddenSimpleNames = setOf("IntegrationRuntime")
        )

        // Auth-service carve-outs (F2-J-05 / cluster H Task 10, commit b804610c6):
        // KitsuAuthService, RealDebridAuthService, and SimklAuthService reference [IntegrationRuntime]
        // only in KDoc comments explaining WHY they predate the integration boundary.
        // No actual injection or runtime usage occurs in these files.
        //
        // CatalogRailSource (F2-A-02 / cluster 2 integration-runtime-phase-a): the interface KDoc
        // mandates that implementers route fetches through `IntegrationRuntime`. The interface itself
        // does NOT inject or use IntegrationRuntime — it only names it in documentation.
        val kdocOnlyCarveOutSuffixes = setOf(
            "data/repository/KitsuAuthService.kt",
            "data/repository/RealDebridAuthService.kt",
            "data/repository/SimklAuthService.kt",
            "core/catalog/rails/CatalogRailSource.kt"
        )

        val offenders = rawOffenders.filterNot { path ->
            kdocOnlyCarveOutSuffixes.any { path.contains(it) }
        }

        if (offenders.isNotEmpty()) {
            fail("IntegrationRuntime escaped approved layers: $offenders")
        }
    }
}
