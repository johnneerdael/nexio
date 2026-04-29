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

    /**
     * Extracts the body of a named function using brace-balanced scanning so the
     * check is not sensitive to physical text layout or file size.
     */
    private fun extractFunctionBody(text: String, fnName: String): String? {
        val start = text.indexOf("fun $fnName(")
        if (start < 0) return null
        val openBrace = text.indexOf('{', start)
        if (openBrace < 0) return null
        var depth = 1
        var i = openBrace + 1
        while (i < text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return text.substring(openBrace + 1, i)
                }
            }
            i++
        }
        return null
    }

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
            "fetchCalendarShows",
            "fetchPopularLists"
        )

        val offenders = mutableListOf<String>()
        for (fn in globalFetchFns) {
            val body = extractFunctionBody(text, fn) ?: continue
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
