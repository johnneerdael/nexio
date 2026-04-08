package com.nexio.tv.ui.screens.search

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchManualStreamSelectionGateTest {

    @Test
    fun `manual stream selection shows for deterministic autoplay movie search results`() {
        assertTrue(
            shouldShowSearchManualStreamSelection(
                deterministicAutoplayEnabled = true,
                apiType = "movie"
            )
        )
    }

    @Test
    fun `manual stream selection hides when deterministic autoplay is disabled`() {
        assertFalse(
            shouldShowSearchManualStreamSelection(
                deterministicAutoplayEnabled = false,
                apiType = "movie"
            )
        )
    }

    @Test
    fun `manual stream selection shows for deterministic autoplay series search results`() {
        assertTrue(
            shouldShowSearchManualStreamSelection(
                deterministicAutoplayEnabled = true,
                apiType = "series"
            )
        )
    }
}
