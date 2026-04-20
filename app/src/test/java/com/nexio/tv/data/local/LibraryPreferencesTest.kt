package com.nexio.tv.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.nexio.tv.domain.model.PosterShape
import com.nexio.tv.domain.model.SavedLibraryItem
import com.nexio.tv.testutil.testPreferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryPreferencesTest {
    @Test
    fun `libraryItems drops entries without id type or name`() = runTest {
        val prefs = libraryPreferences()
        prefs.writeRawItems(
            setOf(
                """{"type":"movie","name":"Missing id","posterShape":"POSTER","genres":[]}""",
                """{"id":"tt123","name":"Missing type","posterShape":"POSTER","genres":[]}""",
                """{"id":"tt456","type":"movie","posterShape":"POSTER","genres":[]}"""
            )
        )

        assertEquals(emptyList<SavedLibraryItem>(), prefs.libraryItems.first())
    }

    @Test
    fun `libraryItems normalizes missing poster shape and genres`() = runTest {
        val prefs = libraryPreferences()
        prefs.writeRawItems(
            setOf(
                """
                {
                  "id":"tt123",
                  "type":"movie",
                  "name":"Movie",
                  "poster":null,
                  "background":null,
                  "description":null,
                  "releaseInfo":"2025",
                  "imdbRating":8.3,
                  "addonBaseUrl":null,
                  "addedAt":42
                }
                """.trimIndent()
            )
        )

        val item = prefs.libraryItems.first().single()

        item.hashCode()
        assertEquals(PosterShape.POSTER, item.posterShape)
        assertEquals(emptyList<String>(), item.genres)
        assertEquals("tt123", item.id)
    }

    private fun kotlinx.coroutines.test.TestScope.libraryPreferences(): LibraryPreferences {
        return LibraryPreferences(testPreferencesDataStore("library-preferences"))
    }

    private suspend fun LibraryPreferences.writeRawItems(items: Set<String>) {
        val field = LibraryPreferences::class.java.getDeclaredField("dataStore").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val dataStore = field.get(this) as DataStore<Preferences>
        val key = stringSetPreferencesKey("library_items")
        dataStore.edit { prefs -> prefs[key] = items }
    }
}
