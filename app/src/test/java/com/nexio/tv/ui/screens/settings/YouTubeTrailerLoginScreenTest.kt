package com.nexio.tv.ui.screens.settings

import com.nexio.tv.data.trailer.helper.YouTubeTrailerAuthMode
import com.nexio.tv.data.trailer.helper.YouTubeTrailerAuthUiState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeTrailerLoginScreenTest {

    @Test
    fun `activation overlay is shown when auth is awaiting approval and code exists`() {
        val uiState = YouTubeTrailerAuthUiState(
            mode = YouTubeTrailerAuthMode.AWAITING_APPROVAL,
            userCode = "ABCD-EFGH"
        )

        assertTrue(shouldPresentYouTubeTrailerActivationOverlay(uiState))
    }

    @Test
    fun `activation overlay is hidden when auth is not pending`() {
        val uiState = YouTubeTrailerAuthUiState(
            mode = YouTubeTrailerAuthMode.CONNECTED,
            userCode = "ABCD-EFGH"
        )

        assertFalse(shouldPresentYouTubeTrailerActivationOverlay(uiState))
    }

    @Test
    fun `activation overlay is hidden when code is missing`() {
        val uiState = YouTubeTrailerAuthUiState(
            mode = YouTubeTrailerAuthMode.AWAITING_APPROVAL,
            userCode = null
        )

        assertFalse(shouldPresentYouTubeTrailerActivationOverlay(uiState))
    }
}
