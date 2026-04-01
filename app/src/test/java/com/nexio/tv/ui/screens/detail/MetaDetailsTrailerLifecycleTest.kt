package com.nexio.tv.ui.screens.detail

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MetaDetailsTrailerLifecycleTest {

    @Test
    fun `detail screen takes over for internal trailer playback`() {
        assertTrue(
            shouldShowDetailTrailerTakeover(
                isTrailerPlaying = true,
                isTrailerLoading = false
            )
        )
    }

    @Test
    fun `detail screen takes over while trailer is still resolving`() {
        assertTrue(
            shouldShowDetailTrailerTakeover(
                isTrailerPlaying = false,
                isTrailerLoading = true
            )
        )
    }

    @Test
    fun `detail content hides during internal trailer takeover`() {
        assertFalse(
            shouldShowDetailScrollableContent(
                showTrailerTakeover = true
            )
        )
    }

    @Test
    fun `detail trailer player only renders when trailer url is ready`() {
        assertFalse(
            shouldRenderDetailTrailerPlayer(
                isTrailerPlaying = true,
                trailerUrl = null
            )
        )
    }

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
