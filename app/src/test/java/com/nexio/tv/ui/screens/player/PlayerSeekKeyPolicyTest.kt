package com.nexio.tv.ui.screens.player

import android.view.KeyEvent
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlayerSeekKeyPolicyTest {
    @Test
    fun mediaFastForwardUsesPreviewSeekAndCommitsOnKeyUp() {
        assertThat(
            PlayerSeekKeyPolicy.previewSeekDeltaMs(
                keyCode = KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
                repeatCount = 0
            )
        ).isEqualTo(10_000L)

        assertThat(
            PlayerSeekKeyPolicy.shouldCommitPreviewSeekOnKeyUp(
                keyCode = KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
                controlsVisible = true
            )
        ).isTrue()
    }

    @Test
    fun repeatedMediaFastForwardAcceleratesPreviewWithoutImmediateCommit() {
        assertThat(
            PlayerSeekKeyPolicy.previewSeekDeltaMs(
                keyCode = KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
                repeatCount = 8
            )
        ).isEqualTo(30_000L)
    }

    @Test
    fun mediaRewindUsesNegativePreviewSeekAndCommitsOnKeyUp() {
        assertThat(
            PlayerSeekKeyPolicy.previewSeekDeltaMs(
                keyCode = KeyEvent.KEYCODE_MEDIA_REWIND,
                repeatCount = 15
            )
        ).isEqualTo(-60_000L)

        assertThat(
            PlayerSeekKeyPolicy.shouldCommitPreviewSeekOnKeyUp(
                keyCode = KeyEvent.KEYCODE_MEDIA_REWIND,
                controlsVisible = true
            )
        ).isTrue()
    }

    @Test
    fun dpadSeekOnlyPreviewsWhenControlsAreHidden() {
        assertThat(
            PlayerSeekKeyPolicy.previewSeekDeltaMs(
                keyCode = KeyEvent.KEYCODE_DPAD_RIGHT,
                repeatCount = 0
            )
        ).isEqualTo(10_000L)

        assertThat(
            PlayerSeekKeyPolicy.shouldCommitPreviewSeekOnKeyUp(
                keyCode = KeyEvent.KEYCODE_DPAD_RIGHT,
                controlsVisible = false
            )
        ).isTrue()

        assertThat(
            PlayerSeekKeyPolicy.shouldCommitPreviewSeekOnKeyUp(
                keyCode = KeyEvent.KEYCODE_DPAD_RIGHT,
                controlsVisible = true
            )
        ).isFalse()
    }
}
