package com.nexio.tv.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import java.io.File
import kotlinx.coroutines.CoroutineScope
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
        val dataStore = SearchHistoryDataStore(createDataStore(backgroundScope))

        dataStore.clearRecentSearches()
        dataStore.saveRecentSearch("Severance")

        assertEquals(listOf("Severance"), dataStore.recentSearches.first())
    }

    private fun createDataStore(scope: CoroutineScope): DataStore<Preferences> {
        val tempFile = File.createTempFile("search_history_store", ".preferences_pb")
        tempFile.deleteOnExit()
        return PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { tempFile }
        )
    }
}
