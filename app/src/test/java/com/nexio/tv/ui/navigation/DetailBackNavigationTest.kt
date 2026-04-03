package com.nexio.tv.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class DetailBackNavigationTest {

    @Test
    fun `detail back pops to existing home when home is already in stack`() {
        val action = resolveDetailBackNavigation(
            listOf(
                DETAIL_BACK_HOME_ROUTE,
                "detail/show/series",
                "stream/show/series/episode",
                "player/https://cdn.example.com/video.mkv"
            )
        )

        assertEquals(popDetailBackToExistingHome(), action)
    }

    @Test
    fun `detail back replaces the transient playback flow with home when home is missing`() {
        val action = resolveDetailBackNavigation(
            listOf(
                "search",
                "stream/show/series/episode",
                "player/https://cdn.example.com/video.mkv",
                "detail/show/series"
            )
        )

        assertEquals(
            replaceDetailBackStackWithHome(popUpToRoute = "stream/show/series/episode"),
            action
        )
    }

    @Test
    fun `detail back replaces the current detail entry with home when it is the only transient screen`() {
        val action = resolveDetailBackNavigation(
            listOf("detail/show/series")
        )

        assertEquals(
            replaceDetailBackStackWithHome(popUpToRoute = "detail/show/series"),
            action
        )
    }
}
