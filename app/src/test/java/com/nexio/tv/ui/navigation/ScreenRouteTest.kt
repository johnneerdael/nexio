package com.nexio.tv.ui.navigation

import com.nexio.tv.ui.screens.player.PlayerLaunchSource
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenRouteTest {

    @Test
    fun `stream route preserves original language flag`() {
        val route = Screen.Stream.createRoute(
            videoId = "tt0076759",
            contentType = "movie",
            title = "Star Wars",
            originalLanguage = "en",
            deterministicAutoplay = true
        )

        assertTrue(route.contains("originalLanguage=en"))
    }

    @Test
    fun `player route preserves deterministic autoplay flag`() {
        val route = Screen.Player.createRoute(
            streamUrl = "https://example.com/video.mkv",
            title = "Example",
            returnToDetailOnBack = true,
            deterministicAutoplay = true,
            launchSource = PlayerLaunchSource.STREAM
        )

        assertTrue(route.contains("deterministicAutoplay=true"))
    }
}
