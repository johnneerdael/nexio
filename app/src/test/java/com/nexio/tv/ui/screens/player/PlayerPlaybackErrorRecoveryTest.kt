package com.nexio.tv.ui.screens.player

import androidx.media3.common.PlaybackException
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
}
