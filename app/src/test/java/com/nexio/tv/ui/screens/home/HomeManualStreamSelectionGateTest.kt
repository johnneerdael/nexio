package com.nexio.tv.ui.screens.home

import com.nexio.tv.domain.model.HomeDisplayMetadata
import com.nexio.tv.domain.model.HomeLayout
import com.nexio.tv.domain.model.WatchProgress
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

    @Test
    fun `continue watching manual stream selection shows for deterministic autoplay movies and episodes`() {
        assertTrue(
            shouldShowContinueWatchingManualStreamSelection(
                deterministicAutoplayEnabled = true,
                item = ContinueWatchingItem.InProgress(
                    progress = watchProgress(contentType = "movie")
                )
            )
        )
        assertTrue(
            shouldShowContinueWatchingManualStreamSelection(
                deterministicAutoplayEnabled = true,
                item = ContinueWatchingItem.NextUp(
                    info = nextUpInfo(contentType = "series")
                )
            )
        )
    }

    @Test
    fun `continue watching manual stream selection hides when disabled or unsupported type`() {
        assertFalse(
            shouldShowContinueWatchingManualStreamSelection(
                deterministicAutoplayEnabled = false,
                item = ContinueWatchingItem.InProgress(
                    progress = watchProgress(contentType = "movie")
                )
            )
        )
        assertFalse(
            shouldShowContinueWatchingManualStreamSelection(
                deterministicAutoplayEnabled = true,
                item = ContinueWatchingItem.InProgress(
                    progress = watchProgress(contentType = "channel")
                )
            )
        )
    }

    private fun watchProgress(contentType: String): WatchProgress {
        return WatchProgress(
            contentId = "tt123",
            contentType = contentType,
            name = "Example",
            poster = null,
            backdrop = null,
            logo = null,
            videoId = "tt123",
            season = null,
            episode = null,
            episodeTitle = null,
            position = 1_000L,
            duration = 9_091_000L,
            lastWatched = 42L
        )
    }

    private fun nextUpInfo(contentType: String): NextUpInfo {
        return NextUpInfo(
            contentId = "tt123",
            contentType = contentType,
            name = "Example",
            poster = null,
            backdrop = null,
            logo = null,
            displayMetadata = HomeDisplayMetadata(runtime = "47m"),
            videoId = "tt123:1:2",
            season = 1,
            episode = 2,
            episodeTitle = "Episode",
            thumbnail = null,
            lastWatched = 42L
        )
    }

}
