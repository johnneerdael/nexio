package com.nexio.tv.ui.screens.player

import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayerRuntimeControllerEmbeddedSubtitleHarvestTest {
    @Test
    fun selectedSubRipOrdinalCountsOnlyPrecedingSupportedSubRipTracks() {
        val tracks = listOf(
            track(index = 0, mimeType = MimeTypes.TEXT_VTT),
            track(index = 1, mimeType = MimeTypes.APPLICATION_SUBRIP),
            track(index = 2, mimeType = MimeTypes.TEXT_SSA),
            track(index = 3, codec = "srt")
        )

        assertEquals(
            1,
            selectedSubRipOrdinalForHarvest(
                subtitleTracks = tracks,
                selectedTrack = tracks[3]
            )
        )
    }

    @Test
    fun selectedSubRipOrdinalReturnsNullForUnsupportedOrMissingSelection() {
        val tracks = listOf(
            track(index = 0, mimeType = MimeTypes.TEXT_VTT),
            track(index = 1, mimeType = MimeTypes.APPLICATION_SUBRIP)
        )

        assertNull(
            selectedSubRipOrdinalForHarvest(
                subtitleTracks = tracks,
                selectedTrack = tracks[0]
            )
        )
        assertNull(
            selectedSubRipOrdinalForHarvest(
                subtitleTracks = tracks,
                selectedTrack = null
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
