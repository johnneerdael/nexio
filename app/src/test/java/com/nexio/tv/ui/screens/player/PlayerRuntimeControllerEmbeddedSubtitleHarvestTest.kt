package com.nexio.tv.ui.screens.player

import androidx.media3.common.MimeTypes
import com.nexio.tv.ui.screens.player.translation.EmbeddedSubtitleContainer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayerRuntimeControllerEmbeddedSubtitleHarvestTest {
    @Test
    fun textOrdinalCountsAllSupportedTextFormats() {
        val tracks = listOf(
            track(index = 0, mimeType = MimeTypes.APPLICATION_PGS),
            track(index = 1, mimeType = MimeTypes.APPLICATION_SUBRIP),
            track(index = 2, mimeType = MimeTypes.APPLICATION_TX3G),
            track(index = 3, mimeType = MimeTypes.APPLICATION_TTML)
        )

        assertEquals(
            0,
            selectedTextOrdinalForHarvest(
                subtitleTracks = tracks,
                selectedTrack = tracks[1],
                container = EmbeddedSubtitleContainer.MP4
            )
        )
        assertEquals(
            1,
            selectedTextOrdinalForHarvest(
                subtitleTracks = tracks,
                selectedTrack = tracks[2],
                container = EmbeddedSubtitleContainer.MP4
            )
        )
        assertEquals(
            2,
            selectedTextOrdinalForHarvest(
                subtitleTracks = tracks,
                selectedTrack = tracks[3],
                container = EmbeddedSubtitleContainer.MP4
            )
        )
    }

    @Test
    fun matroskaOrdinalCountsOnlySubRipTracks() {
        val tracks = listOf(
            track(index = 0, mimeType = MimeTypes.TEXT_VTT),
            track(index = 1, mimeType = MimeTypes.APPLICATION_SUBRIP),
            track(index = 2, mimeType = MimeTypes.APPLICATION_TTML),
            track(index = 3, mimeType = MimeTypes.APPLICATION_SUBRIP)
        )

        assertNull(
            selectedTextOrdinalForHarvest(
                subtitleTracks = tracks,
                selectedTrack = tracks[0],
                container = EmbeddedSubtitleContainer.MATROSKA
            )
        )
        assertEquals(
            0,
            selectedTextOrdinalForHarvest(
                subtitleTracks = tracks,
                selectedTrack = tracks[1],
                container = EmbeddedSubtitleContainer.MATROSKA
            )
        )
        assertEquals(
            1,
            selectedTextOrdinalForHarvest(
                subtitleTracks = tracks,
                selectedTrack = tracks[3],
                container = EmbeddedSubtitleContainer.MATROSKA
            )
        )
    }

    @Test
    fun bitmapTrackHasNoTextOrdinal() {
        val tracks = listOf(
            track(index = 0, mimeType = MimeTypes.APPLICATION_PGS),
            track(index = 1, mimeType = MimeTypes.APPLICATION_SUBRIP)
        )

        assertNull(
            selectedTextOrdinalForHarvest(
                subtitleTracks = tracks,
                selectedTrack = tracks[0],
                container = EmbeddedSubtitleContainer.MP4
            )
        )
    }

    @Test
    fun selectedTextOrdinalReturnsNullForMissingSelection() {
        val tracks = listOf(
            track(index = 0, mimeType = MimeTypes.APPLICATION_SUBRIP)
        )

        assertNull(
            selectedTextOrdinalForHarvest(
                subtitleTracks = tracks,
                selectedTrack = null,
                container = EmbeddedSubtitleContainer.MP4
            )
        )
    }

    private fun track(
        index: Int,
        mimeType: String? = null,
        codec: String? = null
    ): TrackInfo {
        return TrackInfo(
            index = index,
            name = "Track $index",
            language = "en",
            trackId = "track-$index",
            codec = codec,
            mimeType = mimeType
        )
    }
}
