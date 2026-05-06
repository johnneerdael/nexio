package com.nexio.tv.ui.screens.settings

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class PosterRatingsSettingsDialogScrollContractTest {
    @Test
    fun `provider selection dialog uses bounded lazy list for overflow choices`() {
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
    }
}
