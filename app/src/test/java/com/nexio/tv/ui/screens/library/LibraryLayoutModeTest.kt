package com.nexio.tv.ui.screens.library

import com.nexio.tv.domain.model.LibraryProviderSelection
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryLayoutModeTest {

    @Test
    fun `readable debrid layout supports all debrid providers`() {
        assertTrue(usesReadableDebridProviderLayout(LibraryProviderSelection.REAL_DEBRID))
        assertTrue(usesReadableDebridProviderLayout(LibraryProviderSelection.PREMIUMIZE))
        assertTrue(usesReadableDebridProviderLayout(LibraryProviderSelection.TORBOX))
        assertTrue(usesReadableDebridProviderLayout(LibraryProviderSelection.EASY_DEBRID))
        assertFalse(usesReadableDebridProviderLayout(LibraryProviderSelection.UNIFIED))
        assertFalse(usesReadableDebridProviderLayout(LibraryProviderSelection.TRAKT))
    }
}
