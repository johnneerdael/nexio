package com.nexio.tv.data.integration.trakt

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F-C-06 pin: Trakt cache keys for global content (trending/popular/recommended/calendar)
 * MUST NOT include `profile:` prefix. Two profiles must hit the same cache entry.
 *
 * Source-grep proxy: each global-content fetch function body must NOT contain
 * `accountCacheKey(...)` — which adds `profile:N:` to the key. They should use
 * `globalContentCacheKey(...)` instead.
 */
class TraktGlobalContentCacheKeyTest {

    @Test
    fun `global-content fetch functions do not call accountCacheKey`() {
        val provider = generateSequence(java.io.File(".")) { it.parentFile }
            .map { it.resolve("app/src/main/java/com/nexio/tv/data/integration/trakt/TraktIntegrationProvider.kt") }
            .firstOrNull { it.exists() }
            ?: error("Could not find TraktIntegrationProvider.kt")
        val text = provider.readText()

        // List of fetch functions whose data is identical across profiles
        val globalFetchFns = listOf(
            "fetchTrendingMovies",
            "fetchTrendingShows",
            "fetchPopularMovies",
            "fetchPopularShows",
            "fetchRecommendations",
            "fetchCalendarShows"
        )

        val offenders = mutableListOf<String>()
        for (fn in globalFetchFns) {
            // Find function declaration; grab next ~2500 chars (covers a typical fetch function body)
            val start = text.indexOf("fun $fn(")
            if (start < 0) continue  // function may not exist; skip silently
            val end = (start + 2500).coerceAtMost(text.length)
            val body = text.substring(start, end)
            if (body.contains("accountCacheKey(")) {
                offenders += fn
            }
        }

        assertTrue(
            "F-C-06: global-content fetch functions must NOT use accountCacheKey (which adds profile: prefix). " +
                "Use globalContentCacheKey(logicalKey) instead. Offenders: $offenders",
            offenders.isEmpty()
        )
    }
}
