package com.nexio.tv.ui.screens.search

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class SearchLayoutInsetTest {

    @Test
    fun `dropdown content starts at text field edge when voice button is visible`() {
        assertEquals(184.dp, searchDropdownStartPadding(showVoiceSearch = true))
    }

    @Test
    fun `dropdown content starts at text field edge when voice button is hidden`() {
        assertEquals(116.dp, searchDropdownStartPadding(showVoiceSearch = false))
    }
}
