package com.nexio.tv.ui.screens.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerRuntimeControllerAssSsaPipelineTest {
    @Test
    fun selectedAssTrackRequestsAssSsaPipeline() {
        val tracks = tracksFor(
            Format.Builder()
                .setSampleMimeType(MimeTypes.TEXT_SSA)
                .setContainerMimeType(MimeTypes.VIDEO_MATROSKA)
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
                .setContainerMimeType(MimeTypes.VIDEO_MATROSKA)
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
                .setContainerMimeType(MimeTypes.VIDEO_MATROSKA)
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
                .setContainerMimeType(MimeTypes.VIDEO_MATROSKA)
                .build(),
            selected = true
        )

        assertTrue(tracks.hasSelectedAssSsaTextTrackForTesting())
    }

    @Test
    fun selectedSidecarAssTrackDoesNotRequestAssSsaPipeline() {
        val tracks = tracksFor(
            Format.Builder()
                .setSampleMimeType(MimeTypes.TEXT_SSA)
                .build(),
            selected = true
        )

        assertFalse(tracks.hasSelectedAssSsaTextTrackForTesting())
    }

    @Test
    fun pipelineDecisionResetForNewStreamClearsStaleOverride() {
        val reset = resetAssSsaPipelineDecisionStateForStream("https://example.test/new.mkv")

        assertTrue(reset.decisionStreamUrl == "https://example.test/new.mkv")
        assertFalse(reset.switchInFlight)
        assertFalse(reset.fallbackHandled)
        assertTrue(reset.overrideForCurrentStream == null)
    }

    @Test
    fun startupProbeRunsOnlyBeforeFirstDecisionForProgressiveStreams() {
        assertTrue(
            shouldRunEmbeddedAssSsaStartupProbe(
                nativeAssSsaAvailable = true,
                pipelineOverrideForCurrentStream = null,
                url = "https://example.test/video.mkv"
            )
        )
        assertFalse(
            shouldRunEmbeddedAssSsaStartupProbe(
                nativeAssSsaAvailable = false,
                pipelineOverrideForCurrentStream = null,
                url = "https://example.test/video.mkv"
            )
        )
        assertFalse(
            shouldRunEmbeddedAssSsaStartupProbe(
                nativeAssSsaAvailable = true,
                pipelineOverrideForCurrentStream = true,
                url = "https://example.test/video.mkv"
            )
        )
        assertFalse(
            shouldRunEmbeddedAssSsaStartupProbe(
                nativeAssSsaAvailable = true,
                pipelineOverrideForCurrentStream = null,
                url = "https://example.test/playlist.m3u8"
            )
        )
    }

    @Test
    fun assSsaPipelineEnablesLegacyTextDecodingForTrackSelectionCompatibility() {
        assertTrue(shouldEnableLegacyTextDecodingForAssSsaPipeline(assSsaRenderActive = true))
        assertFalse(shouldEnableLegacyTextDecodingForAssSsaPipeline(assSsaRenderActive = false))
    }

    @Test
    fun negativeProbeNeverEnablesAssReadyPipelineForProgressiveFallback() {
        assertFalse(
            shouldEnableAssSsaPipelineForProgressiveFallback(
                url = "https://example.test/proxy",
                filename = "episode.mkv"
            )
        )
        assertFalse(
            shouldEnableAssSsaPipelineForProgressiveFallback(
                url = "https://example.test/proxy",
                filename = "episode.webm"
            )
        )
        assertFalse(
            shouldEnableAssSsaPipelineForProgressiveFallback(
                url = "https://example.test/video.mp4",
                filename = "episode.mp4"
            )
        )
    }

    @Test
    fun overlayProviderNullStillStartsAssSsaPipelineWithoutReinitialization() {
        val decision = resolveAssSsaPipelineOverlayDecision(
            requestedUseAssSsaPipeline = true,
            overlayAttached = false
        )

        assertTrue(decision.useAssSsaPipeline)
        assertFalse(decision.disableOverrideForCurrentStream)
    }

    @Test
    fun assSsaTranslationSinkIsOnlyEnabledWhenAiTranslationIsConfigured() {
        assertEquals(
            false,
            shouldEnableAssSsaSampleTranslation(
                aiSubtitlesEnabled = false,
                selectedAddonSubtitlePresent = false,
                selectedSubtitleTrackIndex = 0,
                translationSettingsEnabled = true,
                translationApiKeyPresent = true
            )
        )
        assertEquals(
            true,
            shouldEnableAssSsaSampleTranslation(
                aiSubtitlesEnabled = true,
                selectedAddonSubtitlePresent = false,
                selectedSubtitleTrackIndex = 0,
                translationSettingsEnabled = true,
                translationApiKeyPresent = true
            )
        )
    }

    @Test
    fun assSsaTrackSelectionAfterPlayerInitDoesNotReinitializePlayback() {
        val adjustment = resolveAssSsaPipelineTrackAdjustment(
            desiredUseAssSsaPipeline = true,
            activePlayerUsesAssSsaRenderer = false,
            fallbackHandled = false
        )

        assertEquals(true, adjustment.overrideForCurrentStream)
        assertFalse(adjustment.shouldReinitializePlayer)
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
