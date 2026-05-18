package com.nexio.tv.ui.screens.player.translation

import androidx.media3.common.MimeTypes
import com.nexio.tv.ui.screens.player.TrackInfo
import org.junit.Assert.assertEquals
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
    fun mkvUrlWithQuerySuffixIsMatroska() {
        assertTrue(
            EmbeddedSubtitleHarvestEligibility.isMatroska(
                streamUrl = "https://cdn.example.test/movie.mkv?token=abc123",
                filename = null
            )
        )
    }

    @Test
    fun mkvUrlWithFragmentSuffixIsMatroska() {
        assertTrue(
            EmbeddedSubtitleHarvestEligibility.isMatroska(
                streamUrl = "https://cdn.example.test/movie.mkv#fragment",
                filename = null
            )
        )
    }

    @Test
    fun srtCodecIsSubRip() {
        assertTrue(EmbeddedSubtitleHarvestEligibility.isSubRip(track(codec = "srt")))
    }

    @Test
    fun media3CueTrackWithSubRipCodecIsSubRip() {
        assertTrue(
            EmbeddedSubtitleHarvestEligibility.isSubRip(
                track(
                    mimeType = MimeTypes.APPLICATION_MEDIA3_CUES,
                    codec = MimeTypes.APPLICATION_SUBRIP
                )
            )
        )
    }

    @Test
    fun mp4Tx3gTrackIsEligible() {
        val result = EmbeddedSubtitleHarvestEligibility.evaluate(
            streamUrl = "https://example.test/video/movie.mp4?token=abc",
            filename = null,
            selectedTrack = track(mimeType = MimeTypes.APPLICATION_TX3G),
            selectedAddonSubtitlePresent = false,
            autoTranslateEnabled = true
        )

        assertTrue(result.eligible)
        assertEquals(EmbeddedSubtitleContainer.MP4, result.container)
        assertEquals("eligible", result.reason)
    }

    @Test
    fun mp4VttTrackIsEligible() {
        assertTrue(
            EmbeddedSubtitleHarvestEligibility.evaluate(
                streamUrl = "https://example.test/video/movie.m4v",
                filename = null,
                selectedTrack = track(mimeType = MimeTypes.APPLICATION_MP4VTT),
                selectedAddonSubtitlePresent = false,
                autoTranslateEnabled = true
            ).eligible
        )
    }

    @Test
    fun mp4TtmlTrackIsEligible() {
        assertTrue(
            EmbeddedSubtitleHarvestEligibility.evaluate(
                streamUrl = "https://example.test/video/movie.mov",
                filename = null,
                selectedTrack = track(mimeType = MimeTypes.APPLICATION_TTML),
                selectedAddonSubtitlePresent = false,
                autoTranslateEnabled = true
            ).eligible
        )
    }

    @Test
    fun mp4BitmapSubtitleIsRejected() {
        val result = EmbeddedSubtitleHarvestEligibility.evaluate(
            streamUrl = "https://example.test/video/movie.mp4",
            filename = null,
            selectedTrack = track(mimeType = "application/vobsub"),
            selectedAddonSubtitlePresent = false,
            autoTranslateEnabled = true
        )

        assertFalse(result.eligible)
        assertEquals(EmbeddedSubtitleContainer.MP4, result.container)
        assertEquals("unsupported_track", result.reason)
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
