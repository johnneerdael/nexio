package com.nexio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioDialogTest {

    @Test
    fun `initial selection targets selected row and prepositions list above it`() {
        val tracks = List(5) { index ->
            TrackInfo(
                index = index,
                name = "Track $index",
                language = "en"
            )
        }

        val initialSelection = resolveAudioDialogInitialSelection(
            tracks = tracks,
            selectedIndex = 3
        )

        assertEquals(3, initialSelection.focusIndex)
        assertEquals(2, initialSelection.firstVisibleIndex)
    }

    @Test
    fun `initial selection falls back to first row when selected index is invalid`() {
        val tracks = List(3) { index ->
            TrackInfo(
                index = index,
                name = "Track $index",
                language = "en"
            )
        }

        val initialSelection = resolveAudioDialogInitialSelection(
            tracks = tracks,
            selectedIndex = -1
        )

        assertEquals(0, initialSelection.focusIndex)
        assertEquals(0, initialSelection.firstVisibleIndex)
    }

    @Test
    fun `initial selection stays safe for empty lists`() {
        val initialSelection = resolveAudioDialogInitialSelection(
            tracks = emptyList(),
            selectedIndex = 2
        )

        assertEquals(-1, initialSelection.focusIndex)
        assertEquals(0, initialSelection.firstVisibleIndex)
    }
}
