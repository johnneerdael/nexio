package com.nexio.tv.ui.screens.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerRuntimeControllerAssSsaPipelineTest {
    @Test
    fun selectedAssTrackRequestsAssSsaPipeline() {
        val tracks = tracksFor(
            Format.Builder()
                .setSampleMimeType(MimeTypes.TEXT_SSA)
                .build(),
            selected = true
        )

        assertTrue(tracks.hasSelectedAssSsaTextTrackForTesting())
    }

    @Test
    fun unselectedAssTrackDoesNotRequestAssSsaPipeline() {
        val tracks = tracksFor(
            Format.Builder()
                .setSampleMimeType(MimeTypes.TEXT_SSA)
                .build(),
            selected = false
        )

        assertFalse(tracks.hasSelectedAssSsaTextTrackForTesting())
    }

    @Test
    fun selectedAssTrackDetectedByCodecRequestsAssSsaPipeline() {
        val tracks = tracksFor(
            Format.Builder()
                .setSampleMimeType(MimeTypes.APPLICATION_SUBRIP)
                .setCodecs("avc1.640028, s_text/ass")
                .build(),
            selected = true
        )

        assertTrue(tracks.hasSelectedAssSsaTextTrackForTesting())
    }

    @Test
    fun selectedAssTrackDetectedByHeaderRequestsAssSsaPipeline() {
        val tracks = tracksFor(
            Format.Builder()
                .setSampleMimeType(MimeTypes.APPLICATION_SUBRIP)
                .setInitializationData(listOf("[Script Info]\nScriptType: v4.00+".toByteArray()))
                .build(),
            selected = true
        )

        assertTrue(tracks.hasSelectedAssSsaTextTrackForTesting())
    }

    private fun tracksFor(format: Format, selected: Boolean): Tracks {
        return Tracks(
            listOf(
                Tracks.Group(
                    TrackGroup(format),
                    false,
                    intArrayOf(C.FORMAT_HANDLED),
                    booleanArrayOf(selected)
                )
            )
        )
    }
}
