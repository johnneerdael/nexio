package com.nexio.tv.ui.screens.detail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MetaDetailsScreenRuntimeTest {

    @Test
    fun `episode playback runtime uses episode runtime when present`() {
        assertEquals(
            64,
            resolveEpisodePlaybackRuntimeMinutes(
                episodeRuntimeMinutes = 64,
                seriesRuntime = "49"
            )
        )
    }

    @Test
    fun `episode playback runtime falls back to series average runtime`() {
        assertEquals(
            49,
            resolveEpisodePlaybackRuntimeMinutes(
                episodeRuntimeMinutes = null,
                seriesRuntime = "49"
            )
        )
    }

    @Test
    fun `episode playback runtime stays null when no runtime source exists`() {
        assertNull(
            resolveEpisodePlaybackRuntimeMinutes(
                episodeRuntimeMinutes = null,
                seriesRuntime = null
            )
        )
    }
}
