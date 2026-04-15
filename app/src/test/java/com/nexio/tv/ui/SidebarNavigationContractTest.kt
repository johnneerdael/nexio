package com.nexio.tv.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SidebarNavigationContractTest {
    private val mainActivity = File("app/src/main/java/com/nexio/tv/MainActivity.kt")
    private val modernHomeRows = File("app/src/main/java/com/nexio/tv/ui/screens/home/ModernHomeRows.kt")

    @Test
    fun `collapsed sidebar left key opens drawer directly`() {
        val source = mainActivity.readText()

        assertTrue(source.contains("pendingSidebarFocusRequest = true"))
        assertTrue(
            "Collapsed sidebar key handling must not delegate left to focus search before opening; loading rows can trap focus there.",
            !source.contains("focusManager.moveFocus(FocusDirection.Left)")
        )
    }

    @Test
    fun `modern loading catalog placeholders are focusable`() {
        val source = modernHomeRows.readText()

        assertTrue(source.contains("ModernCatalogLoadingPlaceholder("))
        assertTrue(source.contains("focusRequester: FocusRequester"))
        assertTrue(source.contains(".focusRequester(focusRequester)"))
        assertTrue(source.contains(".onFocusChanged"))
        assertTrue(source.contains(".focusable()"))
    }
}
