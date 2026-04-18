package com.nexio.tv.core.recommendations

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.FileNotFoundException

class AndroidTvChannelArtworkProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String? {
        val appContext = context ?: return null
        val file = AndroidTvChannelArtwork.resolvePosterFile(appContext, uri) ?: return null
        return if (file.isFile) "image/jpeg" else null
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        require(mode == "r") {
            "Android TV channel artwork is read-only"
        }
        val appContext = context ?: throw FileNotFoundException("Provider context unavailable")
        val file = AndroidTvChannelArtwork.resolvePosterFile(appContext, uri)
            ?: throw FileNotFoundException("Unsupported artwork URI: $uri")
        if (!file.isFile || file.length() <= 0L) {
            throw FileNotFoundException("Artwork file not found: $uri")
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? {
        val appContext = context ?: return null
        val file = AndroidTvChannelArtwork.resolvePosterFile(appContext, uri) ?: return null
        if (!file.isFile) return null
        val columns = projection?.takeIf { it.isNotEmpty() }
            ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns).apply {
            addRow(columns.map { column ->
                when (column) {
                    OpenableColumns.DISPLAY_NAME -> file.name
                    OpenableColumns.SIZE -> file.length()
                    else -> null
                }
            })
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri {
        throw UnsupportedOperationException("Android TV channel artwork provider is read-only")
    }

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int {
        throw UnsupportedOperationException("Android TV channel artwork provider is read-only")
    }

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int {
        throw UnsupportedOperationException("Android TV channel artwork provider is read-only")
    }
}
