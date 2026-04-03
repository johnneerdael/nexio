package com.nexio.tv.ui.navigation

import com.nexio.tv.domain.model.HomeDisplayMetadata
import com.nexio.tv.domain.model.WatchProgress
import com.nexio.tv.ui.screens.home.ContinueWatchingItem
import com.nexio.tv.ui.screens.home.NextUpInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StreamRuntimeRoutingTest {

    @Test
    fun `in progress continue watching runtime is derived from watch duration`() {
        val item = ContinueWatchingItem.InProgress(
            progress = watchProgress(durationMs = 9_091_000L)
        )

        assertEquals(152, continueWatchingRuntimeMinutes(item))
    }

    @Test
    fun `next up runtime is parsed from display metadata`() {
        val item = ContinueWatchingItem.NextUp(
            info = nextUpInfo(runtime = "47m")
        )

        assertEquals(47, continueWatchingRuntimeMinutes(item))
    }

    @Test
    fun `missing continue watching runtime stays null`() {
        val inProgress = ContinueWatchingItem.InProgress(
            progress = watchProgress(durationMs = 0L)
        )
        val nextUp = ContinueWatchingItem.NextUp(
            info = nextUpInfo(runtime = null)
        )

        assertNull(continueWatchingRuntimeMinutes(inProgress))
        assertNull(continueWatchingRuntimeMinutes(nextUp))
    }

    @Test
    fun `runtime parser accepts plain minutes and decorated strings`() {
        assertEquals(152, parseRuntimeMinutes("152"))
        assertEquals(47, parseRuntimeMinutes("47m"))
        assertEquals(125, parseRuntimeMinutes("125 min"))
        assertNull(parseRuntimeMinutes("unknown"))
    }

    private fun watchProgress(durationMs: Long): WatchProgress {
        return WatchProgress(
            contentId = "tt123",
            contentType = "movie",
            name = "Example",
            poster = null,
            backdrop = null,
            logo = null,
            videoId = "tt123",
            season = null,
            episode = null,
            episodeTitle = null,
            position = 1_000L,
            duration = durationMs,
            lastWatched = 42L
        )
    }

    private fun nextUpInfo(runtime: String?): NextUpInfo {
        return NextUpInfo(
            contentId = "tt123",
            contentType = "series",
            name = "Example",
            poster = null,
            backdrop = null,
            logo = null,
            displayMetadata = HomeDisplayMetadata(runtime = runtime),
            videoId = "tt123:1:2",
            season = 1,
            episode = 2,
            episodeTitle = "Episode",
            thumbnail = null,
            lastWatched = 42L
        )
    }
}
