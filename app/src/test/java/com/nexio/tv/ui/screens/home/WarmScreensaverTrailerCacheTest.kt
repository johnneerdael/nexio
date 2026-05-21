package com.nexio.tv.ui.screens.home

import com.nexio.tv.core.metadata.router.MetadataRouterFacade
import com.nexio.tv.data.trailer.TrailerPlaybackSource
import com.nexio.tv.data.trailer.TrailerResolutionResult
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Plan: Bug A — Task A3.
 *
 * Pure-logic tests for `warmScreensaverTrailerCache`. Validates per-item
 * sequential dispatch, per-item exception resilience, and the empty-list
 * no-op path.
 *
 * The job-launching extension `refreshScreensaverTrailerCachePipeline`
 * requires a `HomeViewModel` instance and is exercised by on-device
 * verification in Task V1.
 */
class WarmScreensaverTrailerCacheTest {

    @Test
    fun `pipeline calls fetchTrailer for every candidate sequentially`() = runTest {
        val facade = mockk<MetadataRouterFacade>()
        coEvery {
            facade.fetchTrailer(
                metadataRequest = any(),
                title = any(),
                year = any(),
                tmdbId = any(),
                type = any(),
                seasonNumber = any(),
                contentId = any(),
                fallbackYtIds = any(),
                originalLanguage = any()
            )
        } returns TrailerResolutionResult.Playback(
            source = TrailerPlaybackSource(videoUrl = "https://example/test.mp4")
        )

        val candidates = (0 until 5).map { i ->
            ScreensaverWarmCandidate(
                itemId = "tmdb:$i",
                title = "Title $i",
                releaseInfo = "2024",
                apiType = "movie",
                tmdbId = "$i",
                fallbackYtId = null
            )
        }

        val invocations = mutableListOf<String>()
        warmScreensaverTrailerCache(
            candidates = candidates,
            facade = facade,
            languageTag = "en",
            throttleMs = 0L,
            onItemFetched = { id -> invocations += id }
        )

        assertEquals(listOf("tmdb:0", "tmdb:1", "tmdb:2", "tmdb:3", "tmdb:4"), invocations)
        coVerify(exactly = 5) {
            facade.fetchTrailer(
                metadataRequest = any(),
                title = any(),
                year = any(),
                tmdbId = any(),
                type = any(),
                seasonNumber = any(),
                contentId = any(),
                fallbackYtIds = any(),
                originalLanguage = any()
            )
        }
    }

    @Test
    fun `pipeline continues past a per-item exception`() = runTest {
        val facade = mockk<MetadataRouterFacade>()
        coEvery {
            facade.fetchTrailer(
                metadataRequest = any(),
                title = "Fail",
                year = any(),
                tmdbId = any(),
                type = any(),
                seasonNumber = any(),
                contentId = any(),
                fallbackYtIds = any(),
                originalLanguage = any()
            )
        } throws RuntimeException("first item TMDB 500")
        coEvery {
            facade.fetchTrailer(
                metadataRequest = any(),
                title = "OK",
                year = any(),
                tmdbId = any(),
                type = any(),
                seasonNumber = any(),
                contentId = any(),
                fallbackYtIds = any(),
                originalLanguage = any()
            )
        } returns TrailerResolutionResult.Playback(
            source = TrailerPlaybackSource(videoUrl = "https://example/second.mp4")
        )

        val candidates = listOf(
            ScreensaverWarmCandidate(
                itemId = "tmdb:fail",
                title = "Fail",
                releaseInfo = "2024",
                apiType = "movie",
                tmdbId = "1",
                fallbackYtId = null
            ),
            ScreensaverWarmCandidate(
                itemId = "tmdb:ok",
                title = "OK",
                releaseInfo = "2024",
                apiType = "movie",
                tmdbId = "2",
                fallbackYtId = null
            )
        )
        val invocations = mutableListOf<String>()
        warmScreensaverTrailerCache(
            candidates = candidates,
            facade = facade,
            languageTag = "en",
            throttleMs = 0L,
            onItemFetched = { id -> invocations += id }
        )

        // Both candidates must complete despite the first throwing.
        assertEquals(listOf("tmdb:fail", "tmdb:ok"), invocations)
    }

    @Test
    fun `empty candidates list is a no-op`() = runTest {
        val facade = mockk<MetadataRouterFacade>()
        var fetched = 0
        warmScreensaverTrailerCache(
            candidates = emptyList(),
            facade = facade,
            languageTag = "en",
            throttleMs = 0L,
            onItemFetched = { fetched += 1 }
        )
        assertEquals(0, fetched)
    }
}
