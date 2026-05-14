package com.nexio.tv.ui.screens.library

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LibraryUnifiedWatchlistUiContractTest {
    @Test
    fun `unified watchlist rows stay outside LibraryUiState and render provider-neutral cards`() {
        val viewModelSource = File("app/src/main/java/com/nexio/tv/ui/screens/library/LibraryViewModel.kt").readText()
        val screenSource = File("app/src/main/java/com/nexio/tv/ui/screens/library/LibraryScreen.kt").readText()
        val railItemSource = File("app/src/main/java/com/nexio/tv/ui/screens/library/LibraryRailItem.kt").readText()
        val modelsSource = File("app/src/main/java/com/nexio/tv/domain/model/LibraryModels.kt").readText()

        val uiStateBlock = Regex("""data class LibraryUiState\((?s:.*?)\)\n\ninternal sealed interface""")
            .find(viewModelSource)
            ?.value
            .orEmpty()

        assertTrue(viewModelSource.contains("val unifiedWatchlistRows: StateFlow<List<UnifiedWatchlistRowItem>>"))
        assertFalse(uiStateBlock.contains("unifiedWatchlistRows"))

        assertTrue(screenSource.contains("viewModel.unifiedWatchlistRows.collectAsState()"))
        assertFalse(screenSource.contains("LibraryPrimaryTabsRow("))
        assertFalse(screenSource.contains("LibraryPrimaryTab.UNIFIED_WATCHLIST"))
        assertTrue(screenSource.contains("title = \"Provider\""))
        assertTrue(screenSource.contains("expandedPicker == \"provider\""))
        assertTrue(screenSource.contains("title = stringResource(R.string.library_filter_list)"))
        assertTrue(screenSource.contains("listSelectorLabel = uiState.listSelectorLabel"))
        assertTrue(screenSource.contains("onSelectProvider"))
        assertFalse(screenSource.contains("Provider Library"))
        assertFalse(screenSource.contains("Unified Watchlist\""))
        assertTrue(screenSource.contains("UnifiedWatchlistLibraryRailItem.entryFromRow(row)"))
        assertTrue(screenSource.contains("LibraryRailItem.fromEntry(item)"))
        assertFalse(screenSource.contains("items(unifiedWatchlistRows"))
        assertTrue(railItemSource.contains("posterProviderTag = null"))

        assertFalse(screenSource.contains("presentIn.joinToString"))
        assertFalse(screenSource.contains("Next episode"))
        assertFalse(screenSource.contains("Continue Watching"))
        assertFalse(screenSource.contains("progress"))

        assertTrue(modelsSource.contains("enum class LibraryProviderSelection"))
        assertTrue(modelsSource.contains("UNIFIED(\"Unified\")"))
        assertTrue(modelsSource.contains("EASY_DEBRID(\"EasyDebrid\")"))
        assertTrue(modelsSource.contains("data class LibraryProviderSnapshot"))
        assertTrue(modelsSource.contains("val provider: LibraryProviderSelection"))
        assertTrue(modelsSource.contains("val listSelectorLabel: String = \"N/A\""))
        assertTrue(modelsSource.contains("val isMutableStaticList: Boolean = false"))
    }
}
