package com.nexio.tv.ui.navigation

import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

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

    @Test
    fun `stream route keeps primary video id separate from imdb stream fetch id`() {
        val route = Screen.Stream.createRoute(
            videoId = "tvdb:422712:1:2",
            streamVideoId = "tt14016574:1:2",
            contentType = "series",
            title = "Alien: Earth",
            contentId = "tvdb:422712",
            contentName = "Alien: Earth",
            season = 1,
            episode = 2
        )

        val args = decodedStreamRouteArgs(route)
        assertEquals("tvdb:422712:1:2", args["videoId"])
        assertEquals("tt14016574:1:2", args["streamVideoId"])
        assertEquals("tvdb:422712", args["contentId"])
    }

    private fun decodedStreamRouteArgs(route: String): Map<String, String> {
        val path = route.substringBefore("?")
        val pathParts = path.split("/")
        val queryArgs = route.substringAfter("?", "")
            .split("&")
            .filter { it.isNotBlank() }
            .associate { pair ->
                val key = pair.substringBefore("=")
                val value = pair.substringAfter("=", "")
                key to decode(value)
            }
        return queryArgs + mapOf(
            "videoId" to decode(pathParts[1]),
            "contentType" to decode(pathParts[2]),
            "title" to decode(pathParts[3])
        )
    }

    private fun decode(value: String): String {
        return URLDecoder.decode(value, StandardCharsets.UTF_8.name())
    }
}
