package com.nexio.tv.ui.screens.player

import kotlin.reflect.full.primaryConstructor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PlayerPlaybackProgressUiStateTest {

    @Test
    fun `player ui state no longer owns progress timing fields`() {
        val names = PlayerUiState::class.primaryConstructor!!.parameters.mapNotNull { it.name }.toSet()

        assertFalse(names.contains("currentPosition"))
        assertFalse(names.contains("duration"))
        assertFalse(names.contains("pendingPreviewSeekPosition"))
    }

    @Test
    fun `display position prefers preview seek when present`() {
        val state = PlayerPlaybackProgressUiState(
            currentPosition = 10_000L,
            duration = 100_000L,
            pendingPreviewSeekPosition = 42_000L
        )

        assertEquals(42_000L, state.displayPosition)
    }

    @Test
    fun `display position falls back to current position when no preview seek exists`() {
        val state = PlayerPlaybackProgressUiState(
            currentPosition = 10_000L,
            duration = 100_000L,
            pendingPreviewSeekPosition = null
        )

        assertEquals(10_000L, state.displayPosition)
    }
}
