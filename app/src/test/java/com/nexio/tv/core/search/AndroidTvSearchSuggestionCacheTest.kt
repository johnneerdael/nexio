package com.nexio.tv.core.search

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidTvSearchSuggestionCacheTest {

    @Test
    fun `get or put reuses cached suggestions within ttl`() = runTest {
        var now = 1_000L
        var calls = 0
        val cache = AndroidTvSearchSuggestionCache(maxEntries = 5, ttlMs = 1_000L, nowMs = { now })

        val first = cache.getOrPut(" Matrix ", limit = 5) {
            calls += 1
            listOf(suggestion("The Matrix"))
        }
        val second = cache.getOrPut("matrix", limit = 5) {
            calls += 1
            listOf(suggestion("Reloaded"))
        }

        assertEquals(listOf("The Matrix"), first.map { it.title })
        assertEquals(listOf("The Matrix"), second.map { it.title })
        assertEquals(1, calls)
    }

    @Test
    fun `get or put refreshes after ttl expires`() = runTest {
        var now = 1_000L
        var calls = 0
        val cache = AndroidTvSearchSuggestionCache(maxEntries = 5, ttlMs = 100L, nowMs = { now })

        cache.getOrPut("matrix", limit = 5) {
            calls += 1
            listOf(suggestion("Old"))
        }
        now = 1_101L
        val refreshed = cache.getOrPut("matrix", limit = 5) {
            calls += 1
            listOf(suggestion("New"))
        }

        assertEquals(listOf("New"), refreshed.map { it.title })
        assertEquals(2, calls)
    }

    @Test
    fun `cache evicts eldest entry when max size is exceeded`() = runTest {
        var calls = 0
        val cache = AndroidTvSearchSuggestionCache(maxEntries = 2, ttlMs = 1_000L, nowMs = { 1_000L })

        cache.getOrPut("one", limit = 5) {
            calls += 1
            listOf(suggestion("One"))
        }
        cache.getOrPut("two", limit = 5) {
            calls += 1
            listOf(suggestion("Two"))
        }
        cache.getOrPut("three", limit = 5) {
            calls += 1
            listOf(suggestion("Three"))
        }
        val one = cache.getOrPut("one", limit = 5) {
            calls += 1
            listOf(suggestion("One Again"))
        }

        assertEquals(listOf("One Again"), one.map { it.title })
        assertEquals(4, calls)
    }

    private fun suggestion(title: String): AndroidTvSearchSuggestion {
        return AndroidTvSearchSuggestion(
            rowId = title.hashCode().toLong(),
            title = title,
            subtitle = "Movie",
            itemId = title,
            contentType = "movie",
            addonBaseUrl = "https://v3-cinemeta.strem.io",
            productionYear = null,
            durationMs = null
        )
    }
}
