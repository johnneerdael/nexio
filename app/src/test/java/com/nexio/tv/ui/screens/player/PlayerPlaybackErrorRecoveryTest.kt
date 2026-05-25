package com.nexio.tv.ui.screens.player

import androidx.media3.common.PlaybackException
import com.nexio.tv.core.player.FfmpegStreamMetadata
import com.nexio.tv.core.player.FfmpegStreamMetadataProbeResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerPlaybackErrorRecoveryTest {
    @Test
    fun `media codec audio decoder failures are recoverable via ffmpeg retry`() {
        val exception = PlaybackException(
            "MediaCodecAudioRenderer error, index=3, format_supported=YES",
            IllegalStateException("Decoder failed: OMX.google.mp3.decoder"),
            PlaybackException.ERROR_CODE_DECODING_FAILED
        )

        assertTrue(exception.isMediaCodecAudioDecoderFailure())
    }

    @Test
    fun `non audio decoder failures do not trigger ffmpeg audio retry`() {
        val exception = PlaybackException(
            "MediaCodecVideoRenderer error, index=0, format_supported=YES",
            IllegalStateException("Decoder failed: OMX.amlogic.mpeg4.decoder.awesome"),
            PlaybackException.ERROR_CODE_DECODING_FAILED
        )

        assertFalse(exception.isMediaCodecAudioDecoderFailure())
    }

    @Test
    fun `startup audio decoder fallback retries from beginning`() {
        assertEquals(0L, audioFfmpegFallbackRetryPositionMs(24_513L))
    }

    @Test
    fun `later audio decoder fallback backs up before retrying`() {
        assertEquals(55_000L, audioFfmpegFallbackRetryPositionMs(60_000L))
    }

    @Test
    fun `resume point audio decoder failure retries platform audio from beginning first`() {
        assertTrue(
            shouldRetryPlatformAudioFromBeginningBeforeFfmpeg(
                positionMs = 83_993L,
                alreadyRetried = false
            )
        )
    }

    @Test
    fun `platform audio restart is attempted once per stream`() {
        assertFalse(
            shouldRetryPlatformAudioFromBeginningBeforeFfmpeg(
                positionMs = 24_513L,
                alreadyRetried = true
            )
        )
    }

    @Test
    fun `ffmpeg audio fallback disables float sink output`() {
        assertFalse(
            shouldEnableAudioSinkFloatOutput(
                requestedEnableFloatOutput = true,
                preferFfmpegAudio = true
            )
        )
    }

    @Test
    fun `platform audio keeps requested float sink output setting`() {
        assertTrue(
            shouldEnableAudioSinkFloatOutput(
                requestedEnableFloatOutput = true,
                preferFfmpegAudio = false
            )
        )
    }

    @Test
    fun `ffmpeg audio fallback disables tunneling`() {
        assertFalse(
            shouldEnableTrackSelectorTunneling(
                requestedTunneling = true,
                safeAudioModeEnabled = false,
                audioFfmpegFallbackActive = true
            )
        )
    }

    @Test
    fun `normal playback keeps requested tunneling when safe audio is inactive`() {
        assertTrue(
            shouldEnableTrackSelectorTunneling(
                requestedTunneling = true,
                safeAudioModeEnabled = false,
                audioFfmpegFallbackActive = false
            )
        )
    }

    @Test
    fun `stuck playback retries ffmpeg audio before disabling audio`() {
        assertTrue(
            shouldRetryStuckPlaybackWithAudioFfmpeg(
                ffmpegAvailable = true,
                audioFfmpegFallbackActive = false,
                audioDisabledForStream = false
            )
        )
    }

    @Test
    fun `stuck playback does not repeat ffmpeg audio fallback`() {
        assertFalse(
            shouldRetryStuckPlaybackWithAudioFfmpeg(
                ffmpegAvailable = true,
                audioFfmpegFallbackActive = true,
                audioDisabledForStream = false
            )
        )
    }

    @Test
    fun `stuck playback skips ffmpeg audio when audio is already disabled`() {
        assertFalse(
            shouldRetryStuckPlaybackWithAudioFfmpeg(
                ffmpegAvailable = true,
                audioFfmpegFallbackActive = false,
                audioDisabledForStream = true
            )
        )
    }

    @Test
    fun `avi urls run deterministic ffmpeg audio probe when ffmpeg is available`() {
        assertTrue(
            shouldRunDeterministicAudioFfmpegProbe(
                url = "https://tekenfilms.nexioapp.org/nl/Atlantis%20de%20verzonken%20stad.avi",
                ffmpegAvailable = true
            )
        )
    }

    @Test
    fun `non avi urls skip deterministic ffmpeg audio probe`() {
        assertFalse(
            shouldRunDeterministicAudioFfmpegProbe(
                url = "https://cdn.example.test/movie.mkv",
                ffmpegAvailable = true
            )
        )
    }

    @Test
    fun `legacy avi mp3 probe prefers ffmpeg audio immediately`() {
        val metadata = FfmpegStreamMetadataProbeResult(
            streams = listOf(
                FfmpegStreamMetadata(
                    codecType = "audio",
                    codecName = "mp3",
                    codecTag = "0x0055"
                )
            ),
            formatName = "avi"
        )

        assertTrue(
            shouldPreferFfmpegAudioFromProbe(
                ffmpegAvailable = true,
                metadata = metadata
            )
        )
    }
}
