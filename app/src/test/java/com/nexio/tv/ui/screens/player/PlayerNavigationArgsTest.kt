package com.nexio.tv.ui.screens.player

import androidx.lifecycle.SavedStateHandle
import com.nexio.tv.ui.navigation.Screen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PlayerNavigationArgsTest {

    @Test
    fun `decodes encoded stream url from navigation state`() {
        val args = PlayerNavigationArgs.from(
            SavedStateHandle(
                mapOf(
                    "streamUrl" to "asset%3A%2F%2F%2Ftruehd.mkv",
                    "title" to "TrueHD%20Validation"
                )
            )
        )

        assertEquals("asset:///truehd.mkv", args.streamUrl)
        assertEquals("TrueHD Validation", args.title)
    }

    @Test
    fun `reads resume progress from navigation state`() {
        val args = PlayerNavigationArgs.from(
            SavedStateHandle(
                mapOf(
                    "streamUrl" to "https%3A%2F%2Fexample.com%2Fvideo.mkv",
                    "title" to "Episode",
                    "resumePositionMs" to "123000",
                    "resumeDurationMs" to "900000",
                    "resumeProgressPercent" to "12.5",
                    "resumeLastWatchedMs" to "42",
                    "resumeSource" to "trakt_playback"
                )
            )
        )

        assertEquals(123_000L, args.resumePositionMs)
        assertEquals(900_000L, args.resumeDurationMs)
        assertEquals(12.5f, args.resumeProgressPercent)
        assertEquals(42L, args.resumeLastWatchedMs)
        assertEquals("trakt_playback", args.resumeSource)
    }

    @Test
    fun `player route carries direct addon urls without percent encoded path separators`() {
        val streamUrl = "https://45-4.download.real-debrid.com/d/RCFFKFRWG3THS/Avatar.Fire.and.Ash.2025.MULTi.VF2.2160p.HDR.DV.WEB-DL.Dolby.Atmos.7.1.H265-Slay3R.mkv?token=a%2Fb%3D"
        val route = Screen.Player.createRoute(
            streamUrl = streamUrl,
            title = "Direct Addon"
        )
        val streamUrlSegment = route.removePrefix("player/").substringBefore("/")

        assertFalse(streamUrlSegment.contains("%2F", ignoreCase = true))
        assertFalse(streamUrlSegment.contains("/"))

        val args = PlayerNavigationArgs.from(
            SavedStateHandle(
                mapOf(
                    "streamUrl" to streamUrlSegment,
                    "title" to "Direct%20Addon"
                )
            )
        )

        assertEquals(streamUrl, args.streamUrl)
        assertEquals("Direct Addon", args.title)
    }
}
