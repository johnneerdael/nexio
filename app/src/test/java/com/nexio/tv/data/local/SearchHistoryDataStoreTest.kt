package com.nexio.tv.data.local

import com.nexio.tv.testutil.searchHistoryDataStoreForTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SearchHistoryDataStoreTest {
    @Test
    fun `next search history trims blanks deduplicates case-insensitively and keeps newest first`() {
        val updated = nextSearchHistory(
            current = listOf("Alien", "Dark", "  ", "dark", "Severance"),
            query = " dark ",
            maxItems = 4
        )

        assertEquals(listOf("dark", "Alien", "Severance"), updated)
    }

    @Test
    fun `next search history ignores blank query`() {
        val current = listOf("Alien", "Dark")

        val updated = nextSearchHistory(
            current = current,
            query = "   ",
            maxItems = 4
        )

        assertEquals(current, updated)
    }

    @Test
    fun `next search history coerces max item count to at least one`() {
        val updated = nextSearchHistory(
            current = listOf("Alien", "Dark"),
            query = "Severance",
            maxItems = 0
        )

        assertEquals(listOf("Severance"), updated)
    }

    @Test
    fun `store persists recent searches`() = runTest {
        val dataStore = searchHistoryDataStoreForTest()

        dataStore.clearRecentSearches()
        dataStore.saveRecentSearch("Severance")

        assertEquals(listOf("Severance"), dataStore.recentSearches.first())
    }
}
