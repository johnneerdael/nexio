package com.nexio.tv.core.search

import android.app.SearchManager
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.provider.BaseColumns

object AndroidTvSearchCursorFactory {
    val COLUMNS = arrayOf(
        BaseColumns._ID,
        SearchManager.SUGGEST_COLUMN_TEXT_1,
        SearchManager.SUGGEST_COLUMN_TEXT_2,
        SearchManager.SUGGEST_COLUMN_INTENT_ACTION,
        SearchManager.SUGGEST_COLUMN_INTENT_DATA,
        SearchManager.SUGGEST_COLUMN_PRODUCTION_YEAR,
        SearchManager.SUGGEST_COLUMN_DURATION,
        SearchManager.SUGGEST_COLUMN_SHORTCUT_ID
    )

    fun emptyCursor(): Cursor = MatrixCursor(COLUMNS)

    fun fromSuggestions(suggestions: List<AndroidTvSearchSuggestion>): Cursor {
        return MatrixCursor(COLUMNS).apply {
            suggestions.forEach { suggestion ->
                addRow(
                    arrayOf<Any?>(
                        suggestion.rowId,
                        suggestion.title,
                        suggestion.subtitle,
                        Intent.ACTION_VIEW,
                        AndroidTvNativeSearchIntent.buildDetailUri(suggestion).toString(),
                        suggestion.productionYear,
                        suggestion.durationMs,
                        SearchManager.SUGGEST_NEVER_MAKE_SHORTCUT
                    )
                )
            }
        }
    }
}
