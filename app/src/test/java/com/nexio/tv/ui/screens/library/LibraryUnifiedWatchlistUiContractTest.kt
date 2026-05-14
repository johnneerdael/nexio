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

        val uiStateBlock = Regex("""data class LibraryUiState\((?s:.*?)\)\n\ninternal sealed interface""")
            .find(viewModelSource)
            ?.value
            .orEmpty()

        assertTrue(viewModelSource.contains("val selectedPrimaryTab: LibraryPrimaryTab = LibraryPrimaryTab.UNIFIED_WATCHLIST"))
        assertTrue(viewModelSource.contains("val unifiedWatchlistRows: StateFlow<List<UnifiedWatchlistRowItem>>"))
        assertFalse(uiStateBlock.contains("unifiedWatchlistRows"))

        assertTrue(screenSource.contains("viewModel.unifiedWatchlistRows.collectAsState()"))
        assertTrue(screenSource.contains("LibraryPrimaryTab.UNIFIED_WATCHLIST"))
        assertTrue(screenSource.contains("UnifiedWatchlistLibraryRailItem.fromRow(row)"))
        assertTrue(railItemSource.contains("posterProviderTag = null"))

        assertFalse(screenSource.contains("presentIn.joinToString"))
        assertFalse(screenSource.contains("Next episode"))
        assertFalse(screenSource.contains("Continue Watching"))
        assertFalse(screenSource.contains("progress"))
    }
}
