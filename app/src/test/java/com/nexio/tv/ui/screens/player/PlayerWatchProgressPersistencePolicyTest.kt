package com.nexio.tv.ui.screens.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerWatchProgressPersistencePolicyTest {

    @Test
    fun `watch progress is not persisted on a playback interval`() {
        assertFalse(shouldPersistWatchProgressOnPlaybackInterval())
    }

    @Test
    fun `watch progress is persisted on playback stop events`() {
        assertTrue(shouldPersistWatchProgressOnPlaybackStopEvent())
    }
}
