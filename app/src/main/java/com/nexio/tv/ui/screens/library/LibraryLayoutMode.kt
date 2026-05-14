package com.nexio.tv.ui.screens.library

import com.nexio.tv.data.repository.DebridLibraryService
import com.nexio.tv.domain.model.LibraryProviderSelection

internal fun usesReadableDebridProviderLayout(selectedProvider: LibraryProviderSelection): Boolean {
    return selectedProvider == LibraryProviderSelection.REAL_DEBRID ||
        selectedProvider == LibraryProviderSelection.PREMIUMIZE ||
        selectedProvider == LibraryProviderSelection.TORBOX ||
        selectedProvider == LibraryProviderSelection.EASY_DEBRID
}

internal fun usesReadableDebridListLayout(selectedListKey: String?): Boolean {
    return selectedListKey == DebridLibraryService.REAL_DEBRID_LIST_KEY ||
        selectedListKey == DebridLibraryService.PREMIUMIZE_LIST_KEY ||
        selectedListKey == DebridLibraryService.TORBOX_LIST_KEY ||
        selectedListKey == DebridLibraryService.EASY_DEBRID_LIST_KEY
}
