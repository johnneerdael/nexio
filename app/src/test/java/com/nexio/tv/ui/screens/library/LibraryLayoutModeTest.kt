package com.nexio.tv.ui.screens.library

import com.nexio.tv.data.repository.DebridLibraryService
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryLayoutModeTest {

    @Test
    fun `readable list layout is enabled for real-debrid and premiumize tabs`() {
        assertTrue(usesReadableDebridListLayout(DebridLibraryService.REAL_DEBRID_LIST_KEY))
        assertTrue(usesReadableDebridListLayout(DebridLibraryService.PREMIUMIZE_LIST_KEY))
    }

    @Test
    fun `readable list layout stays disabled for other library tabs`() {
        assertFalse(usesReadableDebridListLayout(null))
        assertFalse(usesReadableDebridListLayout("watchlist"))
        assertFalse(usesReadableDebridListLayout("service:other"))
    }
}
