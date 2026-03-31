package com.nexio.tv.ui.screens.settings

import org.junit.Assert.assertFalse
import org.junit.Test

class YouTubeTrailerLoginViewModelTest {

    @Test
    fun `login state is signed out by default`() {
        val state = YouTubeTrailerAuthUiState()

        assertFalse(state.isSignedIn)
    }
}
