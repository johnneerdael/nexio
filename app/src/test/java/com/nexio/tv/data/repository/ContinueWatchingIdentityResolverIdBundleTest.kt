package com.nexio.tv.data.repository

import com.nexio.tv.domain.model.TrackingProvider
import com.nexio.tv.domain.model.WatchProgress
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContinueWatchingIdentityResolverIdBundleTest {

    // Use the internal no-arg constructor so the resolver runs the legacyFallback path
    // (metadataRouterFacade = null). This exercises the parsed-contentId branch of
    // idBundle population without needing the full router facade.
    private val resolver = ContinueWatchingIdentityResolver()

    @Test
    fun `legacy fallback populates idBundle from IMDB contentId`() = runTest {
        val record = resolver.resolveOrFallback(
            RawContinueWatchingInput(
                profileId = 1,
                progress = movieProgress(contentId = "tt0903747"),
                languageTag = "en",
            )
        )

        assertEquals("tt0903747", record.idBundle.imdb)
        assertNull(record.idBundle.tmdb)
        assertNull(record.idBundle.season)
        assertNull(record.idBundle.episode)
    }

    @Test
    fun `legacy fallback populates idBundle from TMDB contentId`() = runTest {
        val record = resolver.resolveOrFallback(
            RawContinueWatchingInput(
                profileId = 1,
                progress = movieProgress(contentId = "tmdb:1396"),
                languageTag = "en",
            )
        )

        assertEquals("1396", record.idBundle.tmdb)
        assertNull(record.idBundle.imdb)
    }

    @Test
    fun `Simkl-sourced WatchProgress tags record provider as SIMKL`() = runTest {
        val record = resolver.resolveOrFallback(
            RawContinueWatchingInput(
                profileId = 1,
                progress = movieProgress(contentId = "tt1").copy(
                    source = WatchProgress.SOURCE_SIMKL_PLAYBACK,
                ),
                languageTag = "en",
            )
        )
        assertEquals(TrackingProvider.SIMKL, record.provider)
    }

    @Test
    fun `Trakt-sourced WatchProgress tags record provider as TRAKT`() = runTest {
        val record = resolver.resolveOrFallback(
            RawContinueWatchingInput(
                profileId = 1,
                progress = movieProgress(contentId = "tt1").copy(
                    source = WatchProgress.SOURCE_TRAKT_PLAYBACK,
                ),
                languageTag = "en",
            )
        )
        assertEquals(TrackingProvider.TRAKT, record.provider)
    }

    @Test
    fun `local WatchProgress defaults provider tag to TRAKT for legacy display`() = runTest {
        val record = resolver.resolveOrFallback(
            RawContinueWatchingInput(
                profileId = 1,
                progress = movieProgress(contentId = "tt1"),
                languageTag = "en",
            )
        )
        assertEquals(TrackingProvider.TRAKT, record.provider)
    }

    @Test
    fun `episode record carries season and episode in idBundle`() = runTest {
        val record = resolver.resolveOrFallback(
            RawContinueWatchingInput(
                profileId = 1,
                progress = episodeProgress(
                    contentId = "tt0903747",
                    season = 5,
                    episode = 14,
                ),
                languageTag = "en",
            )
        )

        assertEquals("tt0903747", record.idBundle.imdb)
        assertEquals(5, record.idBundle.season)
        assertEquals(14, record.idBundle.episode)
    }

    private fun movieProgress(contentId: String) = WatchProgress(
        contentId = contentId,
        contentType = "movie",
        name = "Test Movie",
        poster = null,
        backdrop = null,
        logo = null,
        videoId = contentId,
        season = null,
        episode = null,
        episodeTitle = null,
        position = 5_000L,
        duration = 60_000L,
        lastWatched = 1000L,
    )

    private fun episodeProgress(contentId: String, season: Int, episode: Int) = WatchProgress(
        contentId = contentId,
        contentType = "series",
        name = "Test Series",
        poster = null,
        backdrop = null,
        logo = null,
        videoId = "$contentId:$season:$episode",
        season = season,
        episode = episode,
        episodeTitle = "Test Episode",
        position = 5_000L,
        duration = 60_000L,
        lastWatched = 1000L,
    )
}
