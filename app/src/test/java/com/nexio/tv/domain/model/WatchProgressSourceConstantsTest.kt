package com.nexio.tv.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class WatchProgressSourceConstantsTest {

    @Test
    fun `SOURCE_SIMKL_PLAYBACK exists with the expected value`() {
        assertEquals("simkl_playback", WatchProgress.SOURCE_SIMKL_PLAYBACK)
    }

    @Test
    fun `SOURCE_SIMKL_HISTORY exists with the expected value`() {
        assertEquals("simkl_history", WatchProgress.SOURCE_SIMKL_HISTORY)
    }

    @Test
    fun `existing Trakt and local source constants are unchanged`() {
        assertEquals("local", WatchProgress.SOURCE_LOCAL)
        assertEquals("trakt_playback", WatchProgress.SOURCE_TRAKT_PLAYBACK)
        assertEquals("trakt_history", WatchProgress.SOURCE_TRAKT_HISTORY)
        assertEquals("trakt_show_progress", WatchProgress.SOURCE_TRAKT_SHOW_PROGRESS)
    }
}
