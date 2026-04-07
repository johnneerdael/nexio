package com.nexio.tv.ui.navigation

import com.nexio.tv.domain.model.HomeDisplayMetadata
import com.nexio.tv.domain.model.WatchProgress
import com.nexio.tv.ui.screens.home.ContinueWatchingItem
import com.nexio.tv.ui.screens.home.NextUpInfo
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test
    fun `continue watching route forwards deterministic autoplay when enabled`() {
        val route = buildContinueWatchingStreamRoute(
            item = ContinueWatchingItem.NextUp(
                info = nextUpInfo(runtime = "47m")
            ),
            deterministicAutoplayEnabled = true
        )

        assertTrue(route.contains("deterministicAutoplay=true"))
        assertTrue(route.contains("returnToDetailOnBack=true"))
    }

    @Test
    fun `continue watching start from beginning route preserves flags`() {
        val route = buildContinueWatchingStreamRoute(
            item = ContinueWatchingItem.InProgress(
                progress = watchProgress(durationMs = 9_091_000L)
            ),
            deterministicAutoplayEnabled = true,
            startFromBeginning = true
        )

        assertTrue(route.contains("startFromBeginning=true"))
        assertTrue(route.contains("deterministicAutoplay=true"))
    }

    @Test
    fun `missing runtime is hydrated before continue watching route build`() = runTest {
        val route = buildContinueWatchingStreamRouteWithHydration(
            item = ContinueWatchingItem.NextUp(
                info = nextUpInfo(runtime = null)
            ),
            deterministicAutoplayEnabled = true,
            resolveRuntimeMinutes = { 62 }
        )

        assertTrue(route.contains("runtime=62"))
    }

    @Test
    fun `continue watching route still builds when runtime hydration fails`() = runTest {
        val route = buildContinueWatchingStreamRouteWithHydration(
            item = ContinueWatchingItem.NextUp(
                info = nextUpInfo(runtime = null)
            ),
            deterministicAutoplayEnabled = true,
            resolveRuntimeMinutes = { null }
        )

        assertTrue(route.contains("deterministicAutoplay=true"))
        assertTrue(route.contains("runtime="))
    }

    @Test
    fun `manual selection route forces manual mode and disables deterministic autoplay`() {
        val route = buildManualSelectionStreamRoute(
            videoId = "tt123",
            contentType = "movie",
            title = "Example",
            contentId = "tt123",
            contentName = "Example",
            returnToDetailOnBack = false
        )

        assertTrue(route.contains("manualSelection=true"))
        assertTrue(route.contains("deterministicAutoplay=false"))
        assertTrue(route.contains("returnToDetailOnBack=false"))
    }

    @Test
    fun `manual selection route preserves detail return behavior for episodes`() {
        val route = buildManualSelectionStreamRoute(
            videoId = "tt123:1:2",
            contentType = "series",
            title = "Example",
            season = 1,
            episode = 2,
            episodeName = "Episode",
            contentId = "tt123",
            contentName = "Example",
            returnToDetailOnBack = true
        )

        assertTrue(route.contains("manualSelection=true"))
        assertTrue(route.contains("deterministicAutoplay=false"))
        assertTrue(route.contains("returnToDetailOnBack=true"))
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
