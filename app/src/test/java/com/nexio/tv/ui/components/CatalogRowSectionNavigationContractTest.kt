package com.nexio.tv.ui.components

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogRowSectionNavigationContractTest {
    private val source = File("app/src/main/java/com/nexio/tv/ui/components/CatalogRowSection.kt")

    @Test
    fun `left key is handled inside row before sidebar can consume it`() {
        val text = source.readText()

        assertTrue(text.contains(".onKeyEvent"))
        assertTrue(text.contains("Key.DirectionLeft"))
        assertTrue(text.contains("currentIndex <= 0"))
        assertTrue(text.contains("return@onKeyEvent true"))
        assertTrue(text.contains("listState.animateScrollToItem(targetIndex)"))
        assertTrue(text.contains("requester.requestFocus()"))
    }
}
