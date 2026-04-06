package com.nexio.tv.ui.navigation

import androidx.lifecycle.SavedStateHandle
import com.nexio.tv.ui.screens.player.PlayerLaunchSource
import com.nexio.tv.ui.screens.player.PlayerNavigationArgs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerDirectLibraryPlaybackTest {

    @Test
    fun `player route encodes direct library launch source`() {
        val route = Screen.Player.createRoute(
            streamUrl = "https://cdn.test/video.mkv",
            title = "Direct Play",
            launchSource = PlayerLaunchSource.LIBRARY_DIRECT
        )

        assertTrue(route.contains("launchSource=library_direct"))
    }

    @Test
    fun `player args decode direct library launch source`() {
        val args = PlayerNavigationArgs.from(
            SavedStateHandle(
                mapOf(
                    "streamUrl" to "https%3A%2F%2Fcdn.test%2Fvideo.mkv",
                    "title" to "Direct%20Play",
                    "launchSource" to "library_direct"
                )
            )
        )

        assertEquals(PlayerLaunchSource.LIBRARY_DIRECT, args.launchSource)
        assertTrue(shouldReturnDirectLibraryPlaybackToLibrary(args.launchSource))
    }

    @Test
    fun `player route preserves and decodes service key`() {
        val route = Screen.Player.createRoute(
            streamUrl = "https://cdn.test/video.mkv",
            title = "Direct Play",
            serviceKey = "RD",
            launchSource = PlayerLaunchSource.STREAM
        )

        assertTrue(route.contains("serviceKey=RD"))

        val args = PlayerNavigationArgs.from(
            SavedStateHandle(
                mapOf(
                    "streamUrl" to "https%3A%2F%2Fcdn.test%2Fvideo.mkv",
                    "title" to "Direct%20Play",
                    "serviceKey" to "RD",
                    "launchSource" to "stream"
                )
            )
        )

        assertEquals("RD", args.serviceKey)
    }

    @Test
    fun `debrid library content id still returns to library when launch source is missing`() {
        assertTrue(
            shouldReturnDirectLibraryPlaybackToLibrary(
                launchSource = PlayerLaunchSource.STREAM,
                contentId = "rd:torrent:abc123"
            )
        )
        assertTrue(
            shouldReturnDirectLibraryPlaybackToLibrary(
                launchSource = PlayerLaunchSource.OTHER,
                videoId = "pm:item:xyz987"
            )
        )
    }
}
