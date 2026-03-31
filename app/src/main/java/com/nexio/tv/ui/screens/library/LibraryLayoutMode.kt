package com.nexio.tv.ui.screens.library

import com.nexio.tv.data.repository.DebridLibraryService

internal fun usesReadableDebridListLayout(selectedListKey: String?): Boolean {
    return selectedListKey == DebridLibraryService.REAL_DEBRID_LIST_KEY ||
        selectedListKey == DebridLibraryService.PREMIUMIZE_LIST_KEY ||
        selectedListKey == DebridLibraryService.TORBOX_LIST_KEY
}
