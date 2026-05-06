package com.nexio.tv.architecture

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F-12-02 architecture pin: skip provider APIs MUST be fetched only through
 * IntegrationRuntime-backed providers or the SkipIntroRepository adapter boundary.
 * SkipSegmentResolver owns skip-provider decisions for player code; SkipIntroRepository
 * keeps provider calls and cache details behind that resolver.
 */
class SkipIntroRepositoryCanonicalSurfaceTest {

    private val allowedRelativePaths = setOf(
        "/com/nexio/tv/data/repository/SkipIntroRepository.kt",
        "/com/nexio/tv/data/integration/skip/AniSkipIntegrationProvider.kt",
        "/com/nexio/tv/data/integration/skip/AnimeSkipIntegrationProvider.kt",
        "/com/nexio/tv/data/integration/skip/IntroDbIntegrationProvider.kt",
        "/com/nexio/tv/data/integration/skip/ArmIntegrationProvider.kt"
    )

    @Test
    fun `skip provider APIs are only called from SkipIntroRepository or registered sub-providers`() {
        val forbiddenPatterns = mapOf(
            "introDbApi." to Regex("""\bintroDbApi\."""),
            "aniSkipApi." to Regex("""\baniSkipApi\."""),
            "animeSkipApi." to Regex("""\banimeSkipApi\."""),
            "armApi." to Regex("""\barmApi\.""")
        )

        val offenders = productionRegexScan(
            forbiddenPatterns = forbiddenPatterns,
            allowedPaths = allowedRelativePaths.toList()
        )

        assertTrue(
            "Skip-segment APIs must be invoked only by IntegrationRuntime-backed skip providers " +
                "under SkipSegmentResolver. Player code must use " +
                "SkipSegmentResolver.resolveSkipSegments(request). " +
                "Offenders: $offenders",
            offenders.isEmpty()
        )
    }
}
