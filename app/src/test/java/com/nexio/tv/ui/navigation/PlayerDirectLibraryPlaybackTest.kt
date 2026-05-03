package com.nexio.tv.ui.navigation

import androidx.lifecycle.SavedStateHandle
import com.nexio.tv.ui.screens.player.PlayerLaunchSource
import com.nexio.tv.ui.screens.player.PlayerNavigationArgs
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.invariantSeparatorsPathString
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
    fun `player route preserves and decodes video size`() {
        val route = Screen.Player.createRoute(
            streamUrl = "https://cdn.test/video.mkv",
            title = "Direct Play",
            videoSize = 123_456_789L,
            launchSource = PlayerLaunchSource.LIBRARY_DIRECT
        )

        assertTrue(route.contains("videoSize=123456789"))

        val args = PlayerNavigationArgs.from(
            SavedStateHandle(
                mapOf(
                    "streamUrl" to "https%3A%2F%2Fcdn.test%2Fvideo.mkv",
                    "title" to "Direct%20Play",
                    "videoSize" to "123456789",
                    "launchSource" to "library_direct"
                )
            )
        )

        assertEquals(123_456_789L, args.videoSize)
    }

    @Test
    fun `direct library navigation passes playback size to player route`() {
        val source = sourceFile("com/nexio/tv/ui/navigation/NexioNavHost.kt").toFile().readText()
        val directPlaybackBlock = source.substringAfter("val directPlaybackUrl = entry.directPlaybackUrl")
            .substringBefore("} else {")

        assertTrue(
            "Library direct playback must pass entry.playbackSizeBytes into Screen.Player.createRoute",
            directPlaybackBlock.contains("videoSize = entry.playbackSizeBytes")
        )
    }

    @Test
    fun `direct library navigation passes addon context to player route`() {
        val source = sourceFile("com/nexio/tv/ui/navigation/NexioNavHost.kt").toFile().readText()
        val directPlaybackBlock = source.substringAfter("val directPlaybackUrl = entry.directPlaybackUrl")
            .substringBefore("} else {")

        assertTrue(
            "Library direct playback must pass entry.addonBaseUrl into Screen.Player.createRoute",
            directPlaybackBlock.contains("addonBaseUrl = entry.addonBaseUrl")
        )
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

    private fun sourceFile(relativePath: String): Path {
        val cwd = Paths.get("").toAbsolutePath().normalize()
        val relative = Paths.get("app", "src", "main", "java")
        val directCandidate = cwd.resolve(relative)
        val parentCandidate = cwd.resolve("..").resolve(relative).normalize()
        val sourceRoot = when {
            Files.exists(directCandidate) -> directCandidate
            Files.exists(parentCandidate) -> parentCandidate
            else -> error("Unable to locate app/src/main/java from working directory $cwd")
        }
        return sourceRoot.resolve(relativePath).also { path ->
            require(path.invariantSeparatorsPathString.endsWith(relativePath))
        }
    }
}
