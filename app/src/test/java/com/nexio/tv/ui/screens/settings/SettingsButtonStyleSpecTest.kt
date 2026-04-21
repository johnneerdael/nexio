package com.nexio.tv.ui.screens.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsButtonStyleSpecTest {

    @Test
    fun `neutral settings button style keeps focus on the same surface with no scaling`() {
        val spec = neutralSettingsButtonStyleSpec()

        assertEquals(SettingsButtonSurface.BackgroundCard, spec.surface)
        assertEquals(SettingsButtonSurface.BackgroundCard, spec.focusedSurface)
        assertEquals(1f, spec.focusedScale)
        assertEquals(2, spec.focusedBorderWidthDp)
    }

    @Test
    fun `neutral settings button style preserves elevated surface variants`() {
        val spec = neutralSettingsButtonStyleSpec(surface = SettingsButtonSurface.BackgroundElevated)

        assertEquals(SettingsButtonSurface.BackgroundElevated, spec.surface)
        assertEquals(SettingsButtonSurface.BackgroundElevated, spec.focusedSurface)
        assertEquals(1f, spec.focusedScale)
        assertEquals(2, spec.focusedBorderWidthDp)
    }
}
