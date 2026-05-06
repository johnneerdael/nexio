package com.nexio.tv.architecture

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F-12-02 architecture pin: skip intervals MUST be fetched only via SkipIntroRepository
 * or its registered sub-providers. Player-skip latency requirements (sub-50ms from
 * playback start) are incompatible with the metadata router pipeline's identity-resolution
 * + provider-plan overhead. The resolver detour was struck in F-12-01 (ResolverType.SKIP_SEGMENTS
 * removed in commit 95e99e5b4).
 *
 * If you're tempted to bypass this pin: the answer is "add a method to SkipIntroRepository",
 * not "call the API directly from a VM or service". File a feature request if SkipIntroRepository
 * doesn't expose what you need.
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
