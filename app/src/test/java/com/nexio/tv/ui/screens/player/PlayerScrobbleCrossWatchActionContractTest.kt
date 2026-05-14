package com.nexio.tv.ui.screens.player

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerScrobbleCrossWatchActionContractTest {
    private val playbackEvents =
        File("app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerPlaybackEvents.kt").readText()
    private val initialization =
        File("app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerInitialization.kt").readText()

    @Test
    fun `pause helper sends tracking pause not stop`() {
        val body = bodyOf(playbackEvents, "fun PlayerRuntimeController.emitPauseScrobble")

        assertTrue(body.contains("trackingScrobbleService.scrobblePause("))
        assertFalse(body.contains("emitScrobbleStop("))
        assertFalse(body.contains("trackingScrobbleService.scrobbleStop("))
    }

    @Test
    fun `non playing transition uses pause path outside ended state`() {
        val body = bodyOf(initialization, "override fun onIsPlayingChanged")

        assertTrue(body.contains("emitPauseScrobble("))
        assertFalse(body.contains("emitStopScrobbleForCurrentProgress()"))
    }

    private fun bodyOf(source: String, marker: String): String {
        val start = source.indexOf(marker)
        require(start >= 0) { "$marker not found" }
        var depth = 0
        var seenBody = false
        for (i in start until source.length) {
            when (source[i]) {
                '{' -> {
                    depth++
                    seenBody = true
                }
                '}' -> {
                    depth--
                    if (seenBody && depth == 0) return source.substring(start, i + 1)
                }
            }
        }
        error("body for $marker not found")
    }
}
