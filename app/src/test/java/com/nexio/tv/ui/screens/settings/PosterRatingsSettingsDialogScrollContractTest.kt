package com.nexio.tv.ui.screens.settings

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class PosterRatingsSettingsDialogScrollContractTest {
    @Test
    fun `provider selection dialog uses bounded lazy list initialized to selected focused choice`() {
        val source = File(
            "app/src/main/java/com/nexio/tv/ui/screens/settings/PosterRatingsSettingsScreen.kt"
        ).readText()
        val dialogSource = source.substringAfter("private fun ArtworkProviderSelectionDialog(")
            .substringBefore("private fun PosterApiKeyDialog(")

        assertTrue(
            "ArtworkProviderSelectionDialog must use LazyColumn so overflow choices remain focus-navigable.",
            dialogSource.contains("LazyColumn(")
        )
        assertTrue(
            "ArtworkProviderSelectionDialog must bound the list height so it scrolls inside NexioDialog.",
            dialogSource.contains(".heightIn(")
        )
        assertTrue(
            "ArtworkProviderSelectionDialog must remember LazyColumn state from the selected option.",
            dialogSource.contains("rememberLazyListState(") &&
                dialogSource.contains("initialFirstVisibleItemIndex = selectedIndex")
        )
        assertTrue(
            "ArtworkProviderSelectionDialog must create a focus requester for the selected option.",
            dialogSource.contains("FocusRequester()")
        )
        assertTrue(
            "ArtworkProviderSelectionDialog must attach the selected focus requester to the selected choice.",
            dialogSource.contains(".focusRequester(selectedFocusRequester)")
        )
        assertTrue(
            "ArtworkProviderSelectionDialog must compute a focus target from the selected index fallback.",
            dialogSource.contains("focusedChoice")
        )
        val focusRequesterBranch = dialogSource
            .substringBefore(".focusRequester(selectedFocusRequester)")
            .takeLast(240)
        assertTrue(
            "ArtworkProviderSelectionDialog must attach focus to the computed focus target, not only the selected value.",
            focusRequesterBranch.contains("choice == focusedChoice")
        )
        assertTrue(
            "ArtworkProviderSelectionDialog must request focus for the selected choice when opened.",
            dialogSource.contains("selectedFocusRequester.requestFocus()")
        )
    }
}
