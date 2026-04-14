package com.nexio.tv.core.player

import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidFrameRateSettingsTest {
    @Test
    fun `device afr disabled label`() {
        assertEquals(
            "Android: disabled",
            AndroidFrameRateSettings.statusLabelForTests(AndroidFrameRateSettings.Status.Disabled)
        )
    }

    @Test
    fun `device afr seamless only label`() {
        assertEquals(
            "Android: seamless only",
            AndroidFrameRateSettings.statusLabelForTests(AndroidFrameRateSettings.Status.SeamlessOnly)
        )
    }

    @Test
    fun `device afr always label`() {
        assertEquals(
            "Android: enabled",
            AndroidFrameRateSettings.statusLabelForTests(AndroidFrameRateSettings.Status.Always)
        )
    }
}
