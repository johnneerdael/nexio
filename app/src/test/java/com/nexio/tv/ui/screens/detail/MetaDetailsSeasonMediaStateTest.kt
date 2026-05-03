package com.nexio.tv.ui.screens.detail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MetaDetailsSeasonMediaStateTest {

    @Test
    fun `failed season recap attempt clears playback chrome without reviving pending external launch`() {
        val updated = MetaDetailsUiState(
            selectedSeason = 2,
            trailerUrl = "https://example.com/series.m3u8",
            trailerAudioUrl = "https://example.com/series-audio.m4a",
            trailerExternalUrl = "https://youtube.com/watch?v=series",
            pendingExternalTrailerUrl = "https://youtube.com/watch?v=pending",
            isTrailerPlaying = true,
            showTrailerControls = true,
            hideLogoDuringTrailer = true
        ).withFailedSeasonMediaPlaybackAttempt(
            season = 2,
            availability = SeasonMediaActionAvailability(
                hasTrailerOrTeaser = false,
                hasRecap = false
            ),
            previousTrailerUrl = "https://example.com/series.m3u8",
            previousTrailerAudioUrl = "https://example.com/series-audio.m4a",
            previousTrailerUserAgent = "test-agent",
            previousTrailerExternalUrl = "https://youtube.com/watch?v=series"
        )

        assertEquals("https://example.com/series.m3u8", updated.trailerUrl)
        assertEquals("https://example.com/series-audio.m4a", updated.trailerAudioUrl)
        assertEquals("test-agent", updated.trailerUserAgent)
        assertEquals("https://youtube.com/watch?v=series", updated.trailerExternalUrl)
        assertEquals(null, updated.pendingExternalTrailerUrl)
        assertFalse(updated.isTrailerPlaying)
        assertFalse(updated.showTrailerControls)
        assertFalse(updated.hideLogoDuringTrailer)
        assertFalse(updated.selectedSeasonHasPlayableRecap)
    }

    @Test
    fun `failed season trailer attempt preserves prior cta while remaining a playback no-op`() {
        val updated = MetaDetailsUiState(
            selectedSeason = 3,
            trailerUrl = "https://example.com/series.m3u8",
            trailerAudioUrl = "https://example.com/series-audio.m4a",
            trailerExternalUrl = "https://youtube.com/watch?v=series",
            isTrailerPlaying = true,
            showTrailerControls = true,
            hideLogoDuringTrailer = true
        ).withFailedSeasonMediaPlaybackAttempt(
            season = 3,
            availability = SeasonMediaActionAvailability(
                hasTrailerOrTeaser = false,
                hasRecap = true
            ),
            previousTrailerUrl = "https://example.com/series.m3u8",
            previousTrailerAudioUrl = "https://example.com/series-audio.m4a",
            previousTrailerUserAgent = "test-agent",
            previousTrailerExternalUrl = "https://youtube.com/watch?v=series"
        )

        assertEquals("https://example.com/series.m3u8", updated.trailerUrl)
        assertEquals("https://example.com/series-audio.m4a", updated.trailerAudioUrl)
        assertEquals("test-agent", updated.trailerUserAgent)
        assertEquals("https://youtube.com/watch?v=series", updated.trailerExternalUrl)
        assertEquals(null, updated.pendingExternalTrailerUrl)
        assertFalse(updated.isTrailerPlaying)
        assertFalse(updated.showTrailerControls)
        assertFalse(updated.hideLogoDuringTrailer)
        assertFalse(updated.selectedSeasonHasPlayableTrailerMedia)
        assertTrue(updated.selectedSeasonHasPlayableRecap)
    }
}
