package com.nexio.tv.ui.screens.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PauseOverlayVisibilityPolicyTest {

    @Test
    fun `delayed pause overlay can show only when playback is paused enabled and error free`() {
        assertTrue(
            shouldShowPauseOverlayAfterDelay(
                PlayerUiState(
                    isPlaying = false,
                    pauseOverlayEnabled = true,
                    error = null
                )
            )
        )

        assertFalse(
            shouldShowPauseOverlayAfterDelay(
                PlayerUiState(
                    isPlaying = true,
                    pauseOverlayEnabled = true,
                    error = null
                )
            )
        )

        assertFalse(
            shouldShowPauseOverlayAfterDelay(
                PlayerUiState(
                    isPlaying = false,
                    pauseOverlayEnabled = false,
                    error = null
                )
            )
        )

        assertFalse(
            shouldShowPauseOverlayAfterDelay(
                PlayerUiState(
                    isPlaying = false,
                    pauseOverlayEnabled = true,
                    error = "network error"
                )
            )
        )
    }

    @Test
    fun `delayed pause overlay is blocked while player panels are open`() {
        val base = PlayerUiState(
            isPlaying = false,
            pauseOverlayEnabled = true,
            error = null
        )

        assertFalse(shouldShowPauseOverlayAfterDelay(base.copy(showSubtitleDialog = true)))
        assertFalse(shouldShowPauseOverlayAfterDelay(base.copy(showSpeedDialog = true)))
        assertFalse(shouldShowPauseOverlayAfterDelay(base.copy(showMoreDialog = true)))
        assertFalse(shouldShowPauseOverlayAfterDelay(base.copy(showEpisodesPanel = true)))
        assertFalse(shouldShowPauseOverlayAfterDelay(base.copy(showSourcesPanel = true)))
        assertFalse(shouldShowPauseOverlayAfterDelay(base.copy(showAudioDialog = true)))
    }
}
