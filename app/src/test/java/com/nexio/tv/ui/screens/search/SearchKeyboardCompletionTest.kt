package com.nexio.tv.ui.screens.search

import org.junit.Assert.assertEquals
import org.junit.Test

class SearchKeyboardCompletionTest {
    @Test
    fun `keyboard completions preserve suggestion order`() {
        val labels = searchKeyboardCompletionLabels(listOf("Alien", "Dark", "Severance"))

        assertEquals(listOf("Alien", "Dark", "Severance"), labels)
    }
}
