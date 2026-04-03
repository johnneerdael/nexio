package com.nexio.tv.ui.navigation

import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenStreamRouteTest {

    @Test
    fun `stream route preserves runtime metadata when provided`() {
        val route = Screen.Stream.createRoute(
            videoId = "tt123",
            contentType = "movie",
            title = "Example",
            contentId = "tt123",
            contentName = "Example",
            runtime = 152
        )

        assertTrue(route.contains("runtime=152"))
    }
}
