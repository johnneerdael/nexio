package com.nexio.tv.ui.screens.player

import androidx.media3.common.C
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerSafeAudioCapabilitiesTest {
    @Test
    fun safeAudioModeAdvertisesOnlyPcm16() {
        assertArrayEquals(
            intArrayOf(C.ENCODING_PCM_16BIT),
            safeAudioModeSupportedEncodingsForTesting()
        )
    }

    @Test
    fun postFirstFrameBufferingIsNotStagnantWhenBufferedPositionAdvances() {
        assertFalse(
            postFirstFrameBufferingAppearsStagnant(
                initialBufferedPositionMs = 1_793L,
                initialTotalBufferedDurationMs = 1_793L,
                observedBufferedPositionMs = 1_960L,
                observedTotalBufferedDurationMs = 1_960L
            )
        )
    }

    @Test
    fun postFirstFrameBufferingIsStagnantWhenBufferedStateDoesNotAdvance() {
        assertTrue(
            postFirstFrameBufferingAppearsStagnant(
                initialBufferedPositionMs = 584L,
                initialTotalBufferedDurationMs = 584L,
                observedBufferedPositionMs = 584L,
                observedTotalBufferedDurationMs = 584L
            )
        )
    }
}
