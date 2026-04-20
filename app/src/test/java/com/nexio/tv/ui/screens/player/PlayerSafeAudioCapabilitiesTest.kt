package com.nexio.tv.ui.screens.player

import androidx.media3.common.C
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class PlayerSafeAudioCapabilitiesTest {
    @Test
    fun safeAudioModeAdvertisesOnlyPcm16() {
        assertArrayEquals(
            intArrayOf(C.ENCODING_PCM_16BIT),
            safeAudioModeSupportedEncodingsForTesting()
        )
    }
}
