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
    private val profileSelectionScreen = File("app/src/main/java/com/nexio/tv/ui/screens/profile/ProfileSelectionScreen.kt")

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

    @Test
    fun `expanded profile switcher can focus alternate profiles`() {
        val source = mainActivity.readText()

        assertTrue(source.contains("val otherProfileFocusRequesters = remember(otherProfiles.map { it.id })"))
        assertTrue(source.contains("event.key == Key.DirectionDown"))
        assertTrue(source.contains("otherProfileFocusRequesters.first().requestFocus()"))
        assertTrue(source.contains("focusRequester = otherProfileFocusRequesters[index]"))
        assertTrue(source.contains("onMoveUp = { focusRequester.requestFocus() }"))
    }

    @Test
    fun `startup profile picker binds focus requester to clickable cards`() {
        val source = profileSelectionScreen.readText()

        assertTrue(source.contains("val profileIds = profiles.map { it.id }"))
        assertTrue(source.contains("LaunchedEffect(profileIds, activeProfileId)"))
        assertTrue(source.contains("repeat(6)"))
        assertTrue(source.contains("val rootFocusRequester = remember { FocusRequester() }"))
        assertTrue(source.contains(".focusRequester(rootFocusRequester)"))
        assertTrue(source.contains("Key.DirectionRight"))
        assertTrue(source.contains("profiles.getOrNull(focusedIndex)?.let(::selectProfile)"))
        assertTrue(source.contains(".focusRequester(focusRequester)"))
        assertTrue(source.contains("Key.NumPadEnter"))
        assertTrue(source.contains(".focusable()"))
        assertTrue(source.contains(".clickable(onClick = onClick)"))
    }

    @Test
    fun `startup profile picker has activity key fallback while focus is absent`() {
        val source = mainActivity.readText()

        assertTrue(source.contains("startupProfileSelectionKeyFallback"))
        assertTrue(source.contains("val selectStartupProfile: (Int) -> Unit"))
        assertTrue(source.contains("DisposableEffect(startupProfileIds, activeProfileId)"))
        assertTrue(source.contains("KeyEvent.KEYCODE_DPAD_RIGHT"))
        assertTrue(source.contains("val profileId = profiles.getOrNull(startupProfileFallbackIndex)?.id"))
        assertTrue(source.contains("profileId?.let(selectStartupProfile)"))
        assertTrue(source.contains("if (startupProfileSelectionKeyFallback?.invoke(event) == true) return true"))
        assertTrue(source.contains("Log.i(\"ProfileSelection\", \"Clearing startup profile key fallback\")"))
        assertTrue(source.contains("startupProfileSelectionKeyFallback = null"))
    }
}
