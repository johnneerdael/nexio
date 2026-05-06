package com.nexio.tv.data.repository

import com.nexio.tv.core.metadata.router.resolver.SkipIntroRepositoryPort
import com.nexio.tv.core.metadata.router.resolver.SkipProviderRoute
import com.nexio.tv.core.metadata.router.resolver.SkipSegmentRequest
import com.nexio.tv.core.metadata.router.resolver.SkipSegmentResolver
import com.nexio.tv.data.remote.api.TheIntroDbMediaResponse
import com.nexio.tv.data.remote.api.TheIntroDbSegmentTimestamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SkipIntroRepositoryTidbTest {

    @Test
    fun `mapper includes all enabled TIDB segment types and sorts by start`() {
        val response = TheIntroDbMediaResponse(
            intro = listOf(TheIntroDbSegmentTimestamp(startMs = null, endMs = 61_000)),
            recap = listOf(TheIntroDbSegmentTimestamp(startMs = 5_000, endMs = 20_000)),
            credits = listOf(TheIntroDbSegmentTimestamp(startMs = 1_200_000, endMs = null)),
            preview = listOf(TheIntroDbSegmentTimestamp(startMs = 1_260_000, endMs = null))
        )

        val mapped = TheIntroDbSegmentMapper.map(
            response,
            TheIntroDbSegmentPreferences()
        )

        assertEquals(listOf("intro", "recap", "credits", "preview"), mapped.map { it.type })
        assertEquals(0.0, mapped.first().startTime, 0.0001)
        assertEquals(Double.MAX_VALUE, mapped[2].endTime, 0.0)
        assertEquals(Double.MAX_VALUE, mapped[3].endTime, 0.0)
    }

    @Test
    fun `mapper respects per-segment preferences`() {
        val response = TheIntroDbMediaResponse(
            intro = listOf(TheIntroDbSegmentTimestamp(startMs = 0, endMs = 10_000)),
            recap = listOf(TheIntroDbSegmentTimestamp(startMs = 10_000, endMs = 20_000)),
            credits = listOf(TheIntroDbSegmentTimestamp(startMs = 30_000, endMs = null)),
            preview = listOf(TheIntroDbSegmentTimestamp(startMs = 40_000, endMs = null))
        )

        val mapped = TheIntroDbSegmentMapper.map(
            response,
            TheIntroDbSegmentPreferences(
                showIntroButton = false,
                showRecapButton = true,
                showCreditsButton = false,
                showPreviewButton = true
            )
        )

        assertEquals(listOf("recap", "preview"), mapped.map { it.type })
    }

    @Test
    fun `resolver policy routes explicit anime paths to anime primary`() {
        val resolver = SkipSegmentResolver(NoOpSkipIntroRepositoryPort)

        assertEquals(
            SkipProviderRoute.ANIME_PRIMARY,
            resolver.resolveRoute(
                SkipSegmentRequest(
                    contentType = "anime",
                    currentVideoId = "tt1234567",
                    contentId = "tt1234567",
                    season = null,
                    episode = null
                )
            )
        )
        assertEquals(
            SkipProviderRoute.ANIME_PRIMARY,
            resolver.resolveRoute(
                SkipSegmentRequest(
                    contentType = "series",
                    currentVideoId = "mal:57658:1",
                    contentId = "tt1234567",
                    season = null,
                    episode = null
                )
            )
        )
        assertEquals(
            SkipProviderRoute.ANIME_PRIMARY,
            resolver.resolveRoute(
                SkipSegmentRequest(
                    contentType = "series",
                    currentVideoId = "tt1234567",
                    contentId = "addon:anime:tt1234567",
                    season = null,
                    episode = null
                )
            )
        )
    }

    @Test
    fun `resolver policy routes normal movie and tv content to TheIntroDB`() {
        val resolver = SkipSegmentResolver(NoOpSkipIntroRepositoryPort)

        assertEquals(
            SkipProviderRoute.THEINTRODB,
            resolver.resolveRoute(
                SkipSegmentRequest(
                    contentType = "movie",
                    currentVideoId = "tt0111161",
                    contentId = "tt0111161",
                    season = null,
                    episode = null
                )
            )
        )
        assertEquals(
            SkipProviderRoute.THEINTRODB,
            resolver.resolveRoute(
                SkipSegmentRequest(
                    contentType = "series",
                    currentVideoId = "1399:1:1",
                    contentId = "1399",
                    season = null,
                    episode = null
                )
            )
        )
    }

    @Test
    fun `mapper supports multiple same-type segments`() {
        val response = TheIntroDbMediaResponse(
            intro = listOf(
                TheIntroDbSegmentTimestamp(startMs = 0, endMs = 10_000),
                TheIntroDbSegmentTimestamp(startMs = 20_000, endMs = 25_000)
            )
        )

        val mapped = TheIntroDbSegmentMapper.map(response, TheIntroDbSegmentPreferences())

        assertEquals(2, mapped.size)
        assertTrue(mapped.all { it.type == "intro" })
        assertEquals(20.0, mapped[1].startTime, 0.0001)
    }

    private object NoOpSkipIntroRepositoryPort : SkipIntroRepositoryPort {
        override fun clearCachedIntervals() = Unit

        override suspend fun getSkipIntervals(contentId: String?, season: Int?, episode: Int?): List<SkipInterval> =
            emptyList()

        override suspend fun getAnimePrimarySkipIntervals(imdbId: String?, season: Int, episode: Int): List<SkipInterval> =
            emptyList()

        override suspend fun getSkipIntervalsForMal(malId: String, episode: Int): List<SkipInterval> =
            emptyList()

        override suspend fun getSkipIntervalsForKitsu(kitsuId: String, episode: Int): List<SkipInterval> =
            emptyList()
    }
}
