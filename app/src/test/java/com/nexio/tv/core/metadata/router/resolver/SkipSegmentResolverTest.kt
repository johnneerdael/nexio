package com.nexio.tv.core.metadata.router.resolver

import com.nexio.tv.data.repository.SkipInterval
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SkipSegmentResolverTest {
    private val animeInterval = SkipInterval(10.0, 20.0, "op", "aniskip")
    private val introDbInterval = SkipInterval(30.0, 40.0, "intro", "theintrodb")

    @Test
    fun `direct MAL video id uses anime primary skip port`() = runTest {
        val port = FakeSkipIntroRepositoryPort(
            malIntervals = listOf(animeInterval)
        )
        val resolver = SkipSegmentResolver(port)

        val result = resolver.resolveSkipSegments(
            SkipSegmentRequest(
                contentId = "tt1234567",
                currentVideoId = "mal:57658:3",
                season = 1,
                episode = 1,
                contentType = "series"
            )
        )

        assertEquals(listOf(animeInterval), result)
        assertEquals(listOf("mal:57658:3"), port.calls)
    }

    @Test
    fun `direct Kitsu video id uses anime primary skip port`() = runTest {
        val port = FakeSkipIntroRepositoryPort(
            kitsuIntervals = listOf(animeInterval)
        )
        val resolver = SkipSegmentResolver(port)

        val result = resolver.resolveSkipSegments(
            SkipSegmentRequest(
                contentId = "tt1234567",
                currentVideoId = "kitsu:12345:7",
                season = 1,
                episode = 1,
                contentType = "series"
            )
        )

        assertEquals(listOf(animeInterval), result)
        assertEquals(listOf("kitsu:12345:7"), port.calls)
    }

    @Test
    fun `anime IMDb request uses anime primary IMDb route`() = runTest {
        val port = FakeSkipIntroRepositoryPort(
            animePrimaryIntervals = listOf(animeInterval)
        )
        val resolver = SkipSegmentResolver(port)

        val result = resolver.resolveSkipSegments(
            SkipSegmentRequest(
                contentId = "tt7654321",
                currentVideoId = null,
                season = 2,
                episode = 4,
                contentType = "anime"
            )
        )

        assertEquals(listOf(animeInterval), result)
        assertEquals(listOf("anime:tt7654321:2:4"), port.calls)
    }

    @Test
    fun `non anime IMDb request uses TheIntroDB route`() = runTest {
        val port = FakeSkipIntroRepositoryPort(
            introDbIntervals = listOf(introDbInterval)
        )
        val resolver = SkipSegmentResolver(port)

        val result = resolver.resolveSkipSegments(
            SkipSegmentRequest(
                contentId = "tt7654321",
                currentVideoId = null,
                season = 1,
                episode = 8,
                contentType = "series"
            )
        )

        assertEquals(listOf(introDbInterval), result)
        assertEquals(listOf("introdb:tt7654321:1:8"), port.calls)
    }

    @Test
    fun `unsupported request returns empty without touching port`() = runTest {
        val port = FakeSkipIntroRepositoryPort()
        val resolver = SkipSegmentResolver(port)

        val result = resolver.resolveSkipSegments(
            SkipSegmentRequest(
                contentId = "catalog:series:abc",
                currentVideoId = null,
                season = 1,
                episode = 1,
                contentType = "series"
            )
        )

        assertTrue(result.isEmpty())
        assertTrue(port.calls.isEmpty())
    }

    @Test
    fun `TheIntroDB managed cache clearing delegates to port cache`() {
        val port = FakeSkipIntroRepositoryPort()
        val resolver = SkipSegmentResolver(port)

        resolver.clearCachedIntervals()

        assertEquals(1, port.clearCount)
    }

    private class FakeSkipIntroRepositoryPort(
        private val introDbIntervals: List<SkipInterval> = emptyList(),
        private val animePrimaryIntervals: List<SkipInterval> = emptyList(),
        private val malIntervals: List<SkipInterval> = emptyList(),
        private val kitsuIntervals: List<SkipInterval> = emptyList()
    ) : SkipIntroRepositoryPort {
        val calls = mutableListOf<String>()
        var clearCount = 0

        override fun clearCachedIntervals() {
            clearCount += 1
        }

        override suspend fun getSkipIntervals(
            contentId: String?,
            season: Int?,
            episode: Int?
        ): List<SkipInterval> {
            calls += "introdb:$contentId:$season:$episode"
            return introDbIntervals
        }

        override suspend fun getAnimePrimarySkipIntervals(
            imdbId: String?,
            season: Int,
            episode: Int
        ): List<SkipInterval> {
            calls += "anime:$imdbId:$season:$episode"
            return animePrimaryIntervals
        }

        override suspend fun getSkipIntervalsForMal(malId: String, episode: Int): List<SkipInterval> {
            calls += "mal:$malId:$episode"
            return malIntervals
        }

        override suspend fun getSkipIntervalsForKitsu(kitsuId: String, episode: Int): List<SkipInterval> {
            calls += "kitsu:$kitsuId:$episode"
            return kitsuIntervals
        }
    }
}
