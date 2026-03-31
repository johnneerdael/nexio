package com.nexio.tv.ui.screens.detail

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MetaDetailsTrailerLifecycleTest {

    @Test
    fun `lifecycle pause stops auto-playing trailer`() {
        assertTrue(
            shouldStopAutoTrailerOnLifecyclePause(
                isTrailerPlaying = true,
                showTrailerControls = false
            )
        )
    }

    @Test
    fun `lifecycle pause keeps manual trailer state intact`() {
        assertFalse(
            shouldStopAutoTrailerOnLifecyclePause(
                isTrailerPlaying = true,
                showTrailerControls = true
            )
        )
    }

    @Test
    fun `lifecycle pause ignores idle state`() {
        assertFalse(
            shouldStopAutoTrailerOnLifecyclePause(
                isTrailerPlaying = false,
                showTrailerControls = false
            )
        )
    }
}
