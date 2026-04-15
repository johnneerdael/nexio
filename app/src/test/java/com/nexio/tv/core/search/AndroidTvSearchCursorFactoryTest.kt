package com.nexio.tv.core.search

import android.app.SearchManager
import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AndroidTvSearchCursorFactoryTest {

    @Test
    fun `cursor contains Android search suggestion columns and detail intent data`() {
        val cursor = AndroidTvSearchCursorFactory.fromSuggestions(
            listOf(
                AndroidTvSearchSuggestion(
                    rowId = 1,
                    title = "The Matrix",
                    subtitle = "Movie",
                    itemId = "tt0133093",
                    contentType = "movie",
                    addonBaseUrl = "https://v3-cinemeta.strem.io",
                    productionYear = 1999,
                    durationMs = 136L * 60L * 1000L
                )
            )
        )

        assertTrue(cursor.moveToFirst())
        assertEquals("The Matrix", cursor.string(SearchManager.SUGGEST_COLUMN_TEXT_1))
        assertEquals("Movie", cursor.string(SearchManager.SUGGEST_COLUMN_TEXT_2))
        assertEquals(Intent.ACTION_VIEW, cursor.string(SearchManager.SUGGEST_COLUMN_INTENT_ACTION))
        assertEquals(1999, cursor.int(SearchManager.SUGGEST_COLUMN_PRODUCTION_YEAR))
        assertEquals(136L * 60L * 1000L, cursor.long(SearchManager.SUGGEST_COLUMN_DURATION))
        assertEquals(SearchManager.SUGGEST_NEVER_MAKE_SHORTCUT, cursor.string(SearchManager.SUGGEST_COLUMN_SHORTCUT_ID))

        val target = AndroidTvNativeSearchIntent.parseDetailUri(
            android.net.Uri.parse(cursor.string(SearchManager.SUGGEST_COLUMN_INTENT_DATA))
        )
        assertEquals("tt0133093", target?.itemId)
        assertEquals("movie", target?.itemType)
        assertEquals("https://v3-cinemeta.strem.io", target?.addonBaseUrl)
    }

    @Test
    fun `empty cursor has no rows`() {
        val cursor = AndroidTvSearchCursorFactory.emptyCursor()

        assertEquals(0, cursor.count)
    }

    private fun android.database.Cursor.string(column: String): String {
        return getString(getColumnIndexOrThrow(column))
    }

    private fun android.database.Cursor.int(column: String): Int {
        return getInt(getColumnIndexOrThrow(column))
    }

    private fun android.database.Cursor.long(column: String): Long {
        return getLong(getColumnIndexOrThrow(column))
    }
}
