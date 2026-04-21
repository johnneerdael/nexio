package com.nexio.tv.ui.screens.search

import org.junit.Assert.assertEquals
import org.junit.Test

class SearchFocusNavigationDecisionTest {

    @Test
    fun `down from search moves to recent actions when recent searches are visible and results are not`() {
        assertEquals(
            SearchFieldDownTarget.RecentActions,
            resolveSearchFieldDownTarget(
                canMoveToResults = false,
                showRecentSearches = true
            )
        )
    }

    @Test
    fun `down from search still prioritizes results when search results are available`() {
        assertEquals(
            SearchFieldDownTarget.Results,
            resolveSearchFieldDownTarget(
                canMoveToResults = true,
                showRecentSearches = true
            )
        )
    }
}
