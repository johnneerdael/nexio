package com.nexio.tv.core.player

import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidFrameRateSettingsTest {
    @Test
    fun `unknown platform preference maps to unknown status`() {
        assertEquals(
            AndroidFrameRateSettings.Status.Unknown,
            AndroidFrameRateSettings.statusFromPlatformPreferenceForTests(-1)
        )
    }

    @Test
    fun `missing platform preference maps to unknown status`() {
        assertEquals(
            AndroidFrameRateSettings.Status.Unknown,
            AndroidFrameRateSettings.statusFromPlatformPreferenceForTests(null)
        )
    }

    @Test
    fun `only enabled android afr statuses allow app frame rate requests`() {
        assertEquals(
            false,
            AndroidFrameRateSettings.canRequestFrameRateForTests(AndroidFrameRateSettings.Status.Disabled)
        )
        assertEquals(
            false,
            AndroidFrameRateSettings.canRequestFrameRateForTests(AndroidFrameRateSettings.Status.Unknown)
        )
        assertEquals(
            true,
            AndroidFrameRateSettings.canRequestFrameRateForTests(AndroidFrameRateSettings.Status.SeamlessOnly)
        )
        assertEquals(
            true,
            AndroidFrameRateSettings.canRequestFrameRateForTests(AndroidFrameRateSettings.Status.Always)
        )
    }

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
