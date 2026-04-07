package com.nexio.tv.ui.screens.detail

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EpisodeManualStreamSelectionGateTest {

    @Test
    fun `episode manual stream selection follows deterministic autoplay gate`() {
        assertTrue(shouldShowEpisodeManualStreamSelection(deterministicAutoplayEnabled = true))
        assertFalse(shouldShowEpisodeManualStreamSelection(deterministicAutoplayEnabled = false))
    }
}
