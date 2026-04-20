package com.nexio.tv.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.nexio.tv.domain.model.WatchedItem
import com.nexio.tv.testutil.testPreferencesDataStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class WatchedItemsPreferencesTest {
    @Test
    fun `watched items drops entries without content id type title or watched timestamp`() = runTest {
        val prefs = watchedItemsPreferences()
        prefs.writeRawItems(
            setOf(
                """{"contentType":"movie","title":"Missing id","watchedAt":1}""",
                """{"contentId":"tt123","title":"Missing type","watchedAt":1}""",
                """{"contentId":"tt456","contentType":"movie","watchedAt":1}""",
                """{"contentId":"tt789","contentType":"movie","title":"Missing watchedAt"}"""
            )
        )

        assertEquals(emptyList<WatchedItem>(), prefs.getAllItems())
    }

    @Test
    fun `watched items normalizes valid legacy entries`() = runTest {
        val prefs = watchedItemsPreferences()
        prefs.writeRawItems(
            setOf(
                """
                {
                  "contentId":"tt123",
                  "contentType":"movie",
                  "title":"Movie",
                  "season":null,
                  "episode":null,
                  "watchedAt":42
                }
                """.trimIndent()
            )
        )

        val item = prefs.getAllItems().single()

        item.hashCode()
        assertEquals("tt123", item.contentId)
        assertEquals("movie", item.contentType)
        assertEquals("Movie", item.title)
        assertEquals(42L, item.watchedAt)
    }

    private fun kotlinx.coroutines.test.TestScope.watchedItemsPreferences(): WatchedItemsPreferences {
        return WatchedItemsPreferences(testPreferencesDataStore("watched-items-preferences"))
    }

    private suspend fun WatchedItemsPreferences.writeRawItems(items: Set<String>) {
        val field = WatchedItemsPreferences::class.java.getDeclaredField("dataStore").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val dataStore = field.get(this) as DataStore<Preferences>
        val key = stringSetPreferencesKey("watched_items")
        dataStore.edit { prefs -> prefs[key] = items }
    }
}
