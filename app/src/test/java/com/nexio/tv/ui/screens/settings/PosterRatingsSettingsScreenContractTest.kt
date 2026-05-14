package com.nexio.tv.ui.screens.settings

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class PosterRatingsSettingsScreenContractTest {
    @Test
    fun `poster ratings rows scroll focused content inside settings card`() {
        val source = File("app/src/main/java/com/nexio/tv/ui/screens/settings/PosterRatingsSettingsScreen.kt").readText()

        assertTrue(source.contains("val settingsListState = rememberLazyListState()"))
        assertTrue(source.contains("val settingsScrollScope = rememberCoroutineScope()"))
        assertTrue(source.contains("LazyColumn("))
        assertTrue(source.contains("state = settingsListState"))
        assertTrue(source.contains("settingsScrollScope.launch"))
        assertTrue(source.contains("settingsListState.animateScrollToItem(index)"))
        assertTrue(source.contains("onFocused = { scrollSettingsRowIntoView("))
    }
}
