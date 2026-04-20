package com.nexio.tv.ui.screens.detail

import org.junit.Assert.assertEquals
import org.junit.Test

class HeroDescriptionTruncationTest {
    @Test
    fun `overflow truncation replaces trailing period with ellipsis`() {
        assertEquals(
            "The Pirate King was famous...",
            collapseHeroDescriptionForOverflow(
                text = "The Pirate King was famous. The rest is hidden.",
                visibleEnd = "The Pirate King was famous.".length
            )
        )
    }

    @Test
    fun `overflow truncation appends ellipsis to final word`() {
        assertEquals(
            "The Pirate King was famous...",
            collapseHeroDescriptionForOverflow(
                text = "The Pirate King was famous and hidden.",
                visibleEnd = "The Pirate King was famous".length
            )
        )
    }
}
