package com.nexio.tv.ui.screens.home

import org.junit.Assert.assertEquals
import org.junit.Test

class ModernHomeHeroLayoutTest {
    @Test
    fun `hero description is capped at four lines for portrait and landscape poster modes`() {
        assertEquals(4, heroDescriptionMaxLines(portraitMode = true))
        assertEquals(4, heroDescriptionMaxLines(portraitMode = false))
    }
}
