package com.nexio.tv.data.repository

import com.nexio.tv.core.integration.IntegrationCallResult
import com.nexio.tv.data.integration.addon.AddonSubtitleIntegrationProvider
import com.nexio.tv.data.remote.dto.SubtitleItemDto
import com.nexio.tv.data.remote.dto.SubtitleResponseDto
import com.nexio.tv.domain.model.Addon
import com.nexio.tv.domain.model.AddonResource
import com.nexio.tv.domain.model.ContentType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SubtitleRepositoryImplTest {
    @Test
    fun `series subtitle addons filter on videoId before route dispatch`() = runTest {
        val provider = mockk<AddonSubtitleIntegrationProvider>()
        val addonRepository = mockk<AddonRepositoryImpl>()
        val addon = Addon(
            id = "addon.id",
            name = "Addon",
            displayName = "Addon",
            version = "1.0.0",
            description = null,
            logo = null,
            baseUrl = "https://addon.test",
            catalogs = emptyList(),
            types = emptyList(),
            resources = listOf(
                AddonResource(
                    name = "subtitles",
                    types = listOf("series"),
                    idPrefixes = listOf("tt123:1")
                )
            )
        )
        every { addonRepository.getInstalledAddons() } returns flowOf(listOf(addon))
        coEvery {
            provider.getSubtitles(addon, "https://addon.test/subtitles/series/tt123:1:2.json")
        } returns IntegrationCallResult.Success(
            SubtitleResponseDto(
                subtitles = listOf(
                    SubtitleItemDto(
                        id = "sub-1",
                        url = "https://subtitle.test/sub-1.srt",
                        lang = "en"
                    )
                )
            )
        )

        val repository = SubtitleRepositoryImpl(provider, addonRepository)
        val subtitles = repository.getSubtitles(
            type = "tv",
            id = "tt123",
            videoId = "tt123:1:2",
            videoHash = null,
            videoSize = null,
            filename = null
        )

        assertEquals(1, subtitles.size)
        assertEquals("sub-1", subtitles.single().id)
        assertEquals("https://subtitle.test/sub-1.srt", subtitles.single().url)
        coVerify(exactly = 1) {
            provider.getSubtitles(addon, "https://addon.test/subtitles/series/tt123:1:2.json")
        }
    }

    @Test
    fun `getSubtitles fetches addon subtitles through integration provider`() = runTest {
        val provider = mockk<AddonSubtitleIntegrationProvider>()
        val addonRepository = mockk<AddonRepositoryImpl>()
        val addon = Addon(
            id = "addon.id",
            name = "Addon",
            displayName = "Addon",
            version = "1.0.0",
            description = null,
            logo = "https://logo.test/logo.png",
            baseUrl = "https://addon.test",
            catalogs = emptyList(),
            types = listOf(ContentType.MOVIE),
            resources = listOf(
                AddonResource(
                    name = "subtitles",
                    types = listOf("movie"),
                    idPrefixes = null
                )
            )
        )
        every { addonRepository.getInstalledAddons() } returns flowOf(listOf(addon))
        coEvery {
            provider.getSubtitles(addon, "https://addon.test/subtitles/movie/tt123.json")
        } returns IntegrationCallResult.Success(
            SubtitleResponseDto(
                subtitles = listOf(
                    SubtitleItemDto(
                        id = "sub-1",
                        url = "https://subtitle.test/sub-1.srt",
                        lang = "en"
                    )
                )
            )
        )

        val repository = SubtitleRepositoryImpl(provider, addonRepository)
        val subtitles = repository.getSubtitles(
            type = "movie",
            id = "tt123",
            videoId = null,
            videoHash = null,
            videoSize = null,
            filename = null
        )

        assertEquals(1, subtitles.size)
        assertEquals("sub-1", subtitles.single().id)
        assertEquals("https://subtitle.test/sub-1.srt", subtitles.single().url)
        assertEquals("en", subtitles.single().lang)
        assertEquals("Addon", subtitles.single().addonName)
        assertTrue(subtitles.single().addonLogo?.contains("logo.test") == true)
        coVerify(exactly = 1) {
            provider.getSubtitles(addon, "https://addon.test/subtitles/movie/tt123.json")
        }
    }

    @Test
    fun `getSubtitles rethrows cancellation from integration provider`() = runTest {
        val provider = mockk<AddonSubtitleIntegrationProvider>()
        val addonRepository = mockk<AddonRepositoryImpl>()
        val addon = Addon(
            id = "addon.id",
            name = "Addon",
            displayName = "Addon",
            version = "1.0.0",
            description = null,
            logo = null,
            baseUrl = "https://addon.test",
            catalogs = emptyList(),
            types = listOf(ContentType.MOVIE),
            resources = listOf(
                AddonResource(
                    name = "subtitles",
                    types = listOf("movie"),
                    idPrefixes = null
                )
            )
        )
        every { addonRepository.getInstalledAddons() } returns flowOf(listOf(addon))
        coEvery {
            provider.getSubtitles(addon, "https://addon.test/subtitles/movie/tt123.json")
        } throws CancellationException("cancelled")

        val repository = SubtitleRepositoryImpl(provider, addonRepository)

        try {
            repository.getSubtitles(
                type = "movie",
                id = "tt123",
                videoId = null,
                videoHash = null,
                videoSize = null,
                filename = null
            )
            fail("Expected CancellationException")
        } catch (exception: CancellationException) {
            assertEquals("cancelled", exception.message)
        }
    }

    @Test
    fun `getSubtitles rethrows cancellation from addon lookup`() = runTest {
        val provider = mockk<AddonSubtitleIntegrationProvider>()
        val addonRepository = mockk<AddonRepositoryImpl>()

        every { addonRepository.getInstalledAddons() } returns flow {
            throw CancellationException("addon lookup cancelled")
        }

        val repository = SubtitleRepositoryImpl(provider, addonRepository)

        try {
            repository.getSubtitles(
                type = "movie",
                id = "tt123",
                videoId = null,
                videoHash = null,
                videoSize = null,
                filename = null
            )
            fail("Expected CancellationException")
        } catch (exception: CancellationException) {
            assertEquals("addon lookup cancelled", exception.message)
        }
    }
}
