package com.nexio.tv.core.search

import android.app.SearchManager
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import dagger.hilt.EntryPoint
import dagger.hilt.EntryPoints
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

private const val DEFAULT_PROVIDER_LIMIT = 20

class AndroidTvSearchProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val path = uri.pathSegments.firstOrNull()
        if (path != SearchManager.SUGGEST_URI_PATH_QUERY &&
            path != SearchManager.SUGGEST_URI_PATH_SHORTCUT
        ) {
            return AndroidTvSearchCursorFactory.emptyCursor()
        }
        if (path == SearchManager.SUGGEST_URI_PATH_SHORTCUT) {
            return AndroidTvSearchCursorFactory.emptyCursor()
        }

        val query = selectionArgs?.firstOrNull()?.trim().orEmpty()
        if (query.isEmpty()) return AndroidTvSearchCursorFactory.emptyCursor()

        val limit = uri.getQueryParameter(SearchManager.SUGGEST_PARAMETER_LIMIT)
            ?.toIntOrNull()
            ?.coerceAtLeast(1)
            ?: DEFAULT_PROVIDER_LIMIT

        val entryPoint = EntryPoints.get(
            requireNotNull(context).applicationContext,
            ProviderEntryPoint::class.java
        )

        val suggestions = runBlocking(Dispatchers.IO) {
            entryPoint.suggestionCache().getOrPut(query = query, limit = limit) {
                AndroidTvSearchSuggestionMapper.toSuggestions(
                    entryPoint.nativeSearchService().search(query = query, limit = limit)
                )
            }
        }

        return AndroidTvSearchCursorFactory.fromSuggestions(suggestions)
    }

    override fun getType(uri: Uri): String? {
        val path = uri.pathSegments.firstOrNull()
        return if (path == SearchManager.SUGGEST_URI_PATH_QUERY ||
            path == SearchManager.SUGGEST_URI_PATH_SHORTCUT
        ) {
            SearchManager.SUGGEST_MIME_TYPE
        } else {
            null
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface ProviderEntryPoint {
        fun nativeSearchService(): AndroidTvNativeSearchService
        fun suggestionCache(): AndroidTvSearchSuggestionCache
    }
}
