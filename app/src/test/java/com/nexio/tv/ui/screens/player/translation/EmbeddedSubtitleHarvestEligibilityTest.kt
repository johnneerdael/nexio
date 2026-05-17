package com.nexio.tv.ui.screens.player.translation

import androidx.media3.common.MimeTypes
import com.nexio.tv.ui.screens.player.TrackInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddedSubtitleHarvestEligibilityTest {

    @Test
    fun mkvInternalSubripTrackIsEligible() {
        assertTrue(
            EmbeddedSubtitleHarvestEligibility.isEligible(
                streamUrl = "https://example.test/video/movie.mkv",
                filename = null,
                selectedTrack = track(mimeType = MimeTypes.APPLICATION_SUBRIP),
                selectedAddonSubtitlePresent = false,
                autoTranslateEnabled = true
            )
        )
    }

    @Test
    fun addonSubtitleSelectionIsNotEligible() {
        assertFalse(
            EmbeddedSubtitleHarvestEligibility.isEligible(
                streamUrl = "https://example.test/video/movie.mkv",
                filename = null,
                selectedTrack = track(mimeType = MimeTypes.APPLICATION_SUBRIP),
                selectedAddonSubtitlePresent = true,
                autoTranslateEnabled = true
            )
        )
    }

    @Test
    fun pgsSubtitleIsNotEligible() {
        assertFalse(
            EmbeddedSubtitleHarvestEligibility.isEligible(
                streamUrl = "https://example.test/video/movie.mkv",
                filename = null,
                selectedTrack = track(mimeType = "application/pgs"),
                selectedAddonSubtitlePresent = false,
                autoTranslateEnabled = true
            )
        )
    }

    @Test
    fun disabledAutoTranslateIsNotEligible() {
        assertFalse(
            EmbeddedSubtitleHarvestEligibility.isEligible(
                streamUrl = "https://example.test/video/movie.mkv",
                filename = null,
                selectedTrack = track(mimeType = MimeTypes.APPLICATION_SUBRIP),
                selectedAddonSubtitlePresent = false,
                autoTranslateEnabled = false
            )
        )
    }

    @Test
    fun urlEncodedMkvPathIsMatroska() {
        assertTrue(
            EmbeddedSubtitleHarvestEligibility.isMatroska(
                streamUrl = "https://example.test/files/movie.mkv%2Fstream",
                filename = null
            )
        )
    }

    @Test
    fun srtCodecIsSubRip() {
        assertTrue(EmbeddedSubtitleHarvestEligibility.isSubRip(track(codec = "srt")))
    }

    private fun track(
        mimeType: String? = null,
        codec: String? = null
    ): TrackInfo {
        return TrackInfo(
            index = 0,
            name = "English",
            language = "en",
            codec = codec,
            mimeType = mimeType
        )
    }
}
