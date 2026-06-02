package com.nexio.tv.ui.navigation

import com.nexio.tv.core.network.NetworkResult
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.PosterShape
import com.nexio.tv.domain.model.Stream
import com.nexio.tv.domain.repository.StreamRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TekenfilmsDirectPlaybackViewModelTest {
    @Test
    fun `buildPlayerRoute fetches streams from tekenfilms addon and creates player route`() = runTest {
        val streamRepository = mockk<StreamRepository>()
        coEvery {
            streamRepository.getStreamsFromAddon(
                baseUrl = "https://tekenfilms.nexioapp.org",
                type = "movie",
                videoId = "tekenfilms:movie-1"
            )
        } returns NetworkResult.Success(
            listOf(
                stream(url = null),
                stream(url = "https://tekenfilms.nexioapp.org/video/movie-1.mp4")
            )
        )

        val route = TekenfilmsDirectPlaybackViewModel(streamRepository).buildPlayerRoute(
            item(id = "tekenfilms:movie-1")
        )

        assertNotNull(route)
        assertTrue(route!!.startsWith("player/"))
        assertTrue(route.contains("contentId=tekenfilms%3Amovie-1"))
        assertTrue(route.contains("contentType=movie"))
        assertTrue(route.contains("addonBaseUrl=https%3A%2F%2Ftekenfilms.nexioapp.org"))
        coVerify(exactly = 1) {
            streamRepository.getStreamsFromAddon(
                baseUrl = "https://tekenfilms.nexioapp.org",
                type = "movie",
                videoId = "tekenfilms:movie-1"
            )
        }
    }

    @Test
    fun `buildPlayerRoute fetches streams from cartoons addon and creates player route`() = runTest {
        val streamRepository = mockk<StreamRepository>()
        coEvery {
            streamRepository.getStreamsFromAddon(
                baseUrl = "https://cartoons.nexioapp.org",
                type = "movie",
                videoId = "tt0103639"
            )
        } returns NetworkResult.Success(
            listOf(
                stream(
                    url = "https://cartoons.nexioapp.org/nl-gesproken/aladdin.mkv",
                    addonBaseUrl = "https://cartoons.nexioapp.org"
                )
            )
        )

        val route = TekenfilmsDirectPlaybackViewModel(streamRepository).buildPlayerRoute(
            item = item(id = "tt0103639"),
            addonBaseUrl = "https://cartoons.nexioapp.org/manifest.json"
        )

        assertNotNull(route)
        assertTrue(route!!.startsWith("player/"))
        assertTrue(route.contains("contentId=tt0103639"))
        assertTrue(route.contains("addonBaseUrl=https%3A%2F%2Fcartoons.nexioapp.org"))
        coVerify(exactly = 1) {
            streamRepository.getStreamsFromAddon(
                baseUrl = "https://cartoons.nexioapp.org",
                type = "movie",
                videoId = "tt0103639"
            )
        }
    }

    @Test
    fun `buildPlayerRoute fetches series episode streams without all addon fanout`() = runTest {
        val streamRepository = mockk<StreamRepository>()
        coEvery {
            streamRepository.getStreamsFromAddon(
                baseUrl = "https://tekenfilms.nexioapp.org",
                type = "series",
                videoId = "tt1234567:1:2"
            )
        } returns NetworkResult.Success(
            listOf(stream(url = "https://tekenfilms.nexioapp.org/series/show-s01e02.mkv"))
        )

        val route = TekenfilmsDirectPlaybackViewModel(streamRepository).buildPlayerRoute(
            item(id = "tt1234567:1:2", rawType = "series")
        )

        assertNotNull(route)
        assertTrue(route!!.startsWith("player/"))
        assertTrue(route.contains("contentType=series"))
        assertTrue(route.contains("videoId=tt1234567%3A1%3A2"))
        coVerify(exactly = 1) {
            streamRepository.getStreamsFromAddon(
                baseUrl = "https://tekenfilms.nexioapp.org",
                type = "series",
                videoId = "tt1234567:1:2"
            )
        }
        coVerify(exactly = 0) {
            streamRepository.getStreamsFromAllAddons(any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `buildPlayerRoute returns null without fetching for non tekenfilms item`() = runTest {
        val streamRepository = mockk<StreamRepository>()

        val route = TekenfilmsDirectPlaybackViewModel(streamRepository).buildPlayerRoute(
            item(id = "tmdb:1234567")
        )

        assertNull(route)
        coVerify(exactly = 0) {
            streamRepository.getStreamsFromAddon(any(), any(), any())
        }
    }

    @Test
    fun `buildPlayerRoute returns null without all addon fallback when no stream url is available`() = runTest {
        val streamRepository = mockk<StreamRepository>()
        coEvery {
            streamRepository.getStreamsFromAddon(any(), any(), any())
        } returns NetworkResult.Success(listOf(stream(url = null)))

        val route = TekenfilmsDirectPlaybackViewModel(streamRepository).buildPlayerRoute(
            item(id = "tt0103639")
        )

        assertNull(route)
        coVerify(exactly = 0) {
            streamRepository.getStreamsFromAllAddons(any(), any(), any(), any(), any(), any(), any())
        }
    }

    private fun item(
        id: String,
        rawType: String = "movie"
    ): MetaPreview {
        return MetaPreview(
            id = id,
            type = ContentType.fromString(rawType),
            rawType = rawType,
            name = "Tekenfilm",
            poster = "https://tekenfilms.nexioapp.org/poster.jpg",
            posterShape = PosterShape.POSTER,
            background = null,
            logo = null,
            description = null,
            releaseInfo = null,
            imdbRating = null,
            genres = emptyList(),
            originalLanguage = "nl"
        )
    }

    private fun stream(
        url: String?,
        addonBaseUrl: String? = "https://tekenfilms.nexioapp.org"
    ): Stream {
        return Stream(
            name = "Dutch",
            title = null,
            description = null,
            url = url,
            ytId = null,
            infoHash = null,
            fileIdx = null,
            externalUrl = null,
            behaviorHints = null,
            addonName = "Tekenfilms",
            addonLogo = null,
            addonBaseUrl = addonBaseUrl
        )
    }
}
