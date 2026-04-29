package com.nexio.tv.core.poster

import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * F-C-05 pin: cache keys for premium poster providers must use a stable hash (e.g.
 * SHA-256 prefix), not String.hashCode(), so cache hits survive process restarts and
 * cross-JVM-implementation differences.
 *
 * The proxy assertion: production source code does NOT contain `apiKey.hashCode()`
 * within PosterRatingsUrlResolver.kt.
 */
class PosterCacheKeyStableHashTest {

    @Test
    fun `PosterRatingsUrlResolver does not use apiKey hashCode in cache keys`() {
        val module = generateSequence(java.io.File(".")) { it.parentFile }
            .map { it.resolve("app/src/main/java/com/nexio/tv/core/poster/PosterRatingsUrlResolver.kt") }
            .firstOrNull { it.exists() }
            ?: error("Could not find PosterRatingsUrlResolver.kt")
        val text = module.readText()

        assertFalse(
            "F-C-05: PosterRatingsUrlResolver MUST NOT use apiKey.hashCode() in cache keys " +
                "(JVM hashCode is not stable across processes/implementations). " +
                "Use stableHashHex8(apiKey) (SHA-256 truncated to 8 hex chars) instead.",
            text.contains("apiKey.hashCode()")
        )
    }
}
