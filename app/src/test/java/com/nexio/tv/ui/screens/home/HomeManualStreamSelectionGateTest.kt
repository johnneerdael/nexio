package com.nexio.tv.ui.screens.home

import com.nexio.tv.domain.model.HomeLayout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeManualStreamSelectionGateTest {

    @Test
    fun `home manual stream selection only shows for deterministic autoplay movies in modern layout`() {
        assertTrue(
            shouldShowHomeManualStreamSelection(
                deterministicAutoplayEnabled = true,
                homeLayout = HomeLayout.MODERN,
                apiType = "movie"
            )
        )
        assertFalse(
            shouldShowHomeManualStreamSelection(
                deterministicAutoplayEnabled = false,
                homeLayout = HomeLayout.MODERN,
                apiType = "movie"
            )
        )
        assertFalse(
            shouldShowHomeManualStreamSelection(
                deterministicAutoplayEnabled = true,
                homeLayout = HomeLayout.CLASSIC,
                apiType = "movie"
            )
        )
        assertFalse(
            shouldShowHomeManualStreamSelection(
                deterministicAutoplayEnabled = true,
                homeLayout = HomeLayout.MODERN,
                apiType = "series"
            )
        )
    }
}
