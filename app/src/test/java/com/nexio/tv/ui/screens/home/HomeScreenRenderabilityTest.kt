package com.nexio.tv.ui.screens.home

import com.nexio.tv.domain.model.CatalogRow
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.HomeLayout
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.PosterShape
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeScreenRenderabilityTest {

    @Test
    fun `modern home treats loading catalog rows as renderable`() {
        val state = HomeUiState(
            homeLayout = HomeLayout.MODERN,
            catalogRows = listOf(loadingRow())
        )

        assertTrue(hasRenderableHomeContent(state))
    }

    @Test
    fun `classic and grid homes do not treat loading only catalog rows as renderable`() {
        val classic = HomeUiState(
            homeLayout = HomeLayout.CLASSIC,
            catalogRows = listOf(loadingRow())
        )
        val grid = HomeUiState(
            homeLayout = HomeLayout.GRID,
            catalogRows = listOf(loadingRow())
        )

        assertFalse(hasRenderableHomeContent(classic))
        assertFalse(hasRenderableHomeContent(grid))
    }

    @Test
    fun `continue watching pending does not block renderable modern catalog rows`() {
        val sessionId = "profile:2:test-session"
        val state = HomeUiState(
            homeLayout = HomeLayout.MODERN,
            catalogRows = listOf(contentRow()),
            installedAddonsCount = 3,
            isLoading = false,
            homeReadiness = HomeInitialReadiness.started(
                sessionId = sessionId,
                profileId = 2
            ).markLoading(HomeInitialGate.CONTINUE_WATCHING)
        )

        assertTrue(hasRenderableHomeContent(state))
        assertFalse(shouldShowFullHomeLoadingGate(state, startupContentGateTimedOut = false))
    }

    @Test
    fun `continue watching pending still gates empty modern home before timeout`() {
        val sessionId = "profile:2:test-session"
        val state = HomeUiState(
            homeLayout = HomeLayout.MODERN,
            catalogRows = emptyList(),
            installedAddonsCount = 3,
            isLoading = false,
            homeReadiness = HomeInitialReadiness.started(
                sessionId = sessionId,
                profileId = 2
            ).markLoading(HomeInitialGate.CONTINUE_WATCHING)
        )

        assertTrue(shouldShowFullHomeLoadingGate(state, startupContentGateTimedOut = false))
    }

    @Test
    fun `empty modern home with catalog loading still shows spinner even when continue watching resolved`() {
        val sessionId = "profile:2:test-session"
        val state = HomeUiState(
            homeLayout = HomeLayout.MODERN,
            catalogRows = emptyList(),
            installedAddonsCount = 3,
            isLoading = true,
            homeReadiness = HomeInitialReadiness.started(
                sessionId = sessionId,
                profileId = 2
            ).markResolved(HomeInitialGate.CONTINUE_WATCHING, "first_snapshot_empty")
        )

        assertTrue(shouldShowFullHomeLoadingGate(state, startupContentGateTimedOut = false))
    }

    @Test
    fun `empty modern home with continue watching resolved and no loading shows empty state`() {
        val sessionId = "profile:2:test-session"
        val state = HomeUiState(
            homeLayout = HomeLayout.MODERN,
            catalogRows = emptyList(),
            installedAddonsCount = 3,
            isLoading = false,
            homeReadiness = HomeInitialReadiness.started(
                sessionId = sessionId,
                profileId = 2
            ).markResolved(HomeInitialGate.CONTINUE_WATCHING, "first_snapshot_empty")
        )

        assertFalse(shouldShowFullHomeLoadingGate(state, startupContentGateTimedOut = false))
    }

    private fun loadingRow(): CatalogRow {
        return CatalogRow(
            addonId = "simkl",
            addonName = "SIMKL",
            addonBaseUrl = "https://data.simkl.in",
            catalogId = "simkl_tv_trending_today",
            catalogName = "SIMKL Trending TV (Today)",
            type = ContentType.SERIES,
            items = emptyList(),
            isLoading = true
        )
    }

    private fun contentRow(): CatalogRow {
        return CatalogRow(
            addonId = "com.stremio.torrentio.addon",
            addonName = "Torrentio RD",
            addonBaseUrl = "https://torrentio.example",
            catalogId = "torrentio-realdebrid",
            catalogName = "RealDebrid",
            type = ContentType.MOVIE,
            items = listOf(
                MetaPreview(
                    id = "tt0111161",
                    type = ContentType.MOVIE,
                    name = "The Shawshank Redemption",
                    poster = null,
                    posterShape = PosterShape.POSTER,
                    background = null,
                    logo = null,
                    description = null,
                    releaseInfo = "1994",
                    imdbRating = null,
                    genres = emptyList()
                )
            ),
            isLoading = false
        )
    }
}
