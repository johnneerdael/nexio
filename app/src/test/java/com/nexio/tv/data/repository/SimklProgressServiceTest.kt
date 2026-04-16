package com.nexio.tv.data.repository

import com.nexio.tv.data.local.SimklAuthDataStore
import com.nexio.tv.data.local.SimklAuthState
import com.nexio.tv.data.local.SimklProgressSyncStateStore
import com.nexio.tv.data.remote.SimklRequestGate
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SimklProgressServiceTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when (request.path?.substringBefore('?')) {
                    "/sync/activities" -> json(
                        """
                        {
                          "all":"2026-04-08T10:00:00Z",
                          "tv_shows":{"playback":"2026-04-08T10:00:00Z","removed_from_list":"2026-04-08T10:00:00Z"},
                          "anime":{"playback":"2026-04-08T10:00:00Z","removed_from_list":"2026-04-08T10:00:00Z"},
                          "movies":{"playback":"2026-04-08T10:00:00Z","removed_from_list":"2026-04-08T10:00:00Z"}
                        }
                        """.trimIndent()
                    )
                    "/sync/playback/movies" -> json(
                        """
                        [
                          {
                            "id": 124,
                            "progress": 75,
                            "paused_at": "2024-01-15T11:15:00.000Z",
                            "type": "movie",
                            "movie": {
                              "title": "Inception",
                              "year": 2010,
                              "ids": {
                                "tmdb": 27205,
                                "imdb": "tt1375666"
                              }
                            }
                          }
                        ]
                        """.trimIndent()
                    )
                    "/sync/playback/episodes" -> json(
                        """
                        [
                          {
                            "id": 123,
                            "progress": 45.5,
                            "paused_at": "2024-01-15T10:30:00.000Z",
                            "type": "episode",
                            "episode": {
                              "season": 1,
                              "episode": 5,
                              "title": "Episode 5",
                              "tvdb_season": 1,
                              "tvdb_number": 5
                            },
                            "show": {
                              "title": "Breaking Bad",
                              "year": 2008,
                              "runtime": 47,
                              "ids": {
                                "tmdb": 1396,
                                "imdb": "tt0903747"
                              }
                            }
                          }
                        ]
                        """.trimIndent()
                    )
                    "/sync/all-items/movies/" -> json(
                        """
                        { "movies": [
                          {
                            "status":"completed",
                            "movie":{"title":"Inception","year":2010,"ids":{"imdb":"tt1375666","tmdb":"27205"}}
                          }
                        ] }
                        """.trimIndent()
                    )
                    "/sync/all-items/shows/" -> json(
                        """
                        { "shows": [
                          {
                            "status":"watching",
                            "last_watched_at":"2024-01-16T10:00:00Z",
                            "next_to_watch":"S01E06",
                            "show":{"title":"Breaking Bad","year":2008,"runtime":47,"ids":{"imdb":"tt0903747","tmdb":"1396"}},
                            "seasons":[{"number":1,"episodes":[{"number":1},{"number":2},{"number":3}]}]
                          }
                        ] }
                        """.trimIndent()
                    )
                    "/sync/all-items/anime/" -> json("{\"anime\":[]}")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `refreshNow maps playbacks next up watched movies and playback delete ids`() = runTest {
        val authStore = mockAuthStore()
        val syncStateStore = mockk<SimklProgressSyncStateStore>(relaxed = true) {
            every { read() } returns com.nexio.tv.data.local.SimklProgressSyncState()
        }
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val original = chain.request()
                val newUrl = server.url(original.url.encodedPath + if (original.url.encodedQuery != null) "?" + original.url.encodedQuery else "")
                chain.proceed(original.newBuilder().url(newUrl).build())
            }
            .build()

        val service = SimklProgressService(client, authStore, syncStateStore, SimklRequestGate())

        service.refreshNowImmediate()

        val progress = service.observeAllProgress().first()
        val nextUp = service.observeContinueWatchingNextUp().first()
        val watchedMovie = service.observeMovieWatched("tt1375666").first()
        val episodeMap = service.observeEpisodeProgress("tt0903747").first()
        val moviePlaybackIds = service.resolvePlaybackDeleteIdsForOutbox("tt1375666", null, null)
        val episodePlaybackIds = service.resolvePlaybackDeleteIdsForOutbox("tt0903747", 1, 5)

        assertEquals(2, progress.size)
        assertEquals("tt1375666", progress.first().contentId)
        assertEquals(1, nextUp.size)
        assertEquals("tt0903747", nextUp.first().contentId)
        assertEquals(1, nextUp.first().season)
        assertEquals(6, nextUp.first().episode)
        assertTrue(watchedMovie)
        assertTrue((1 to 1) in episodeMap.keys)
        assertEquals(listOf(124L), moviePlaybackIds)
        assertEquals(listOf(123L), episodePlaybackIds)
    }

    @Test
    fun `second refresh uses stored activity timestamps as date_from`() = runTest {
        val authStore = mockAuthStore()
        val syncStateStore = mockk<SimklProgressSyncStateStore>(relaxed = true) {
            every { read() } returns com.nexio.tv.data.local.SimklProgressSyncState(
                lastAllActivityAt = "2026-04-08T10:00:00Z",
                lastPlaybackActivityAt = "2026-04-08T10:00:00Z",
                lastRemovedFromListActivityAt = "2026-04-08T10:00:00Z"
            )
        }
        val recordedPaths = mutableListOf<String>()
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val original = chain.request()
                recordedPaths += original.url.toString()
                val newUrl = server.url(original.url.encodedPath + if (original.url.encodedQuery != null) "?" + original.url.encodedQuery else "")
                chain.proceed(original.newBuilder().url(newUrl).build())
            }
            .build()

        val service = SimklProgressService(client, authStore, syncStateStore, SimklRequestGate())

        service.refreshNowImmediate()

        assertTrue(recordedPaths.any { it.contains("/sync/playback/movies") && it.contains("date_from=") })
        assertTrue(recordedPaths.any { it.contains("/sync/playback/episodes") && it.contains("date_from=") })
        assertTrue(recordedPaths.any { it.contains("/sync/all-items/movies/") && it.contains("date_from=") })
        assertTrue(recordedPaths.any { it.contains("/sync/all-items/shows/") && it.contains("date_from=") })
        assertTrue(recordedPaths.any { it.contains("/sync/all-items/anime/") && it.contains("date_from=") })
    }

    @Test
    fun `removed from list activity change forces full all items sync without date_from`() = runTest {
        val authStore = mockAuthStore()
        val syncStateStore = mockk<SimklProgressSyncStateStore>(relaxed = true) {
            every { read() } returns com.nexio.tv.data.local.SimklProgressSyncState(
                lastAllActivityAt = "2026-04-08T10:00:00Z",
                lastPlaybackActivityAt = "2026-04-08T10:00:00Z",
                lastRemovedFromListActivityAt = "2026-04-01T10:00:00Z"
            )
        }
        val recordedPaths = mutableListOf<String>()
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val original = chain.request()
                recordedPaths += original.url.toString()
                val newUrl = server.url(original.url.encodedPath + if (original.url.encodedQuery != null) "?" + original.url.encodedQuery else "")
                chain.proceed(original.newBuilder().url(newUrl).build())
            }
            .build()

        val service = SimklProgressService(client, authStore, syncStateStore, SimklRequestGate())

        service.refreshNowImmediate()

        assertTrue(recordedPaths.any { it.contains("/sync/all-items/movies/?extended=full") && !it.contains("date_from=") })
        assertTrue(recordedPaths.any { it.contains("/sync/all-items/shows/?extended=full") && !it.contains("date_from=") })
        assertTrue(recordedPaths.any { it.contains("/sync/all-items/anime/?extended=full_anime_seasons") && !it.contains("date_from=") })
    }

    @Test
    fun `anime episode progress prefers tvdb numbering when available`() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when (request.path?.substringBefore('?')) {
                    "/sync/activities" -> json(
                        """
                        {
                          "all":"2026-04-08T10:00:00Z",
                          "tv_shows":{"playback":"2026-04-08T10:00:00Z","removed_from_list":"2026-04-08T10:00:00Z"},
                          "anime":{"playback":"2026-04-08T10:00:00Z","removed_from_list":"2026-04-08T10:00:00Z"},
                          "movies":{"playback":"2026-04-08T10:00:00Z","removed_from_list":"2026-04-08T10:00:00Z"}
                        }
                        """.trimIndent()
                    )
                    "/sync/playback/movies" -> json("[]")
                    "/sync/playback/episodes" -> json("[]")
                    "/sync/all-items/movies/" -> json("{\"movies\":[]}")
                    "/sync/all-items/shows/" -> json("{\"shows\":[]}")
                    "/sync/all-items/anime/" -> json(
                        """
                        {
                          "anime": [
                            {
                              "status":"watching",
                              "last_watched_at":"2024-01-16T10:00:00Z",
                              "show":{"title":"Anime","year":2024,"runtime":24,"ids":{"imdb":"tt9999999"}},
                              "seasons":[
                                {
                                  "number":1,
                                  "episodes":[
                                    {
                                      "number":4,
                                      "tvdb":{"season":2,"episode":3}
                                    }
                                  ]
                                }
                              ]
                            }
                          ]
                        }
                        """.trimIndent()
                    )
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        val authStore = mockAuthStore()
        val syncStateStore = mockk<SimklProgressSyncStateStore>(relaxed = true) {
            every { read() } returns com.nexio.tv.data.local.SimklProgressSyncState()
        }
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val original = chain.request()
                val newUrl = server.url(original.url.encodedPath + if (original.url.encodedQuery != null) "?" + original.url.encodedQuery else "")
                chain.proceed(original.newBuilder().url(newUrl).build())
            }
            .build()

        val service = SimklProgressService(client, authStore, syncStateStore, SimklRequestGate())

        service.refreshNowImmediate()

        val episodeMap = service.observeEpisodeProgress("tt9999999").first()
        assertTrue((2 to 3) in episodeMap.keys)
        assertTrue((1 to 4) !in episodeMap.keys)
    }

    private fun mockAuthStore(): SimklAuthDataStore {
        val state = MutableStateFlow(SimklAuthState(accessToken = "token"))
        return mockk {
            every { this@mockk.state } returns state
            every { this@mockk.isEffectivelyAuthenticated } returns state.map { it.isAuthenticated }
        }
    }

    private fun json(body: String): MockResponse =
        MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(body)
}
