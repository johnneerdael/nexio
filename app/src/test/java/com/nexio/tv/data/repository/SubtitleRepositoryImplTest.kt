package com.nexio.tv.data.repository

import com.nexio.tv.core.integration.IntegrationCallResult
import com.nexio.tv.data.integration.addon.AddonSubtitleIntegrationProvider
import com.nexio.tv.data.remote.dto.SubtitleItemDto
import com.nexio.tv.data.remote.dto.SubtitleResponseDto
import com.nexio.tv.domain.model.Addon
import com.nexio.tv.domain.model.AddonResource
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.Subtitle
import com.nexio.tv.domain.repository.OpenSubtitlesSource
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

        val repository = SubtitleRepositoryImpl(
            provider,
            addonRepository,
            mockk<com.nexio.tv.data.integration.subtitles.wyzie.WyzieSubtitleIntegrationProvider>(relaxed = true),
            noOpenSubtitlesSource(),
        )
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

        val repository = SubtitleRepositoryImpl(
            provider,
            addonRepository,
            mockk<com.nexio.tv.data.integration.subtitles.wyzie.WyzieSubtitleIntegrationProvider>(relaxed = true),
            noOpenSubtitlesSource(),
        )
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

        val repository = SubtitleRepositoryImpl(
            provider,
            addonRepository,
            mockk<com.nexio.tv.data.integration.subtitles.wyzie.WyzieSubtitleIntegrationProvider>(relaxed = true),
            noOpenSubtitlesSource(),
        )

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

        val repository = SubtitleRepositoryImpl(
            provider,
            addonRepository,
            mockk<com.nexio.tv.data.integration.subtitles.wyzie.WyzieSubtitleIntegrationProvider>(relaxed = true),
            noOpenSubtitlesSource(),
        )

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

    @Test
    fun `wyzie lane results merge after addon results`() = runTest {
        val addonProvider = mockk<AddonSubtitleIntegrationProvider>()
        val addonRepo = mockk<AddonRepositoryImpl>()
        val wyzieProvider = mockk<com.nexio.tv.data.integration.subtitles.wyzie.WyzieSubtitleIntegrationProvider>()

        val addon = com.nexio.tv.domain.model.Addon(
            id = "addon.id", name = "Addon", displayName = "Addon",
            version = "1.0.0", description = null, logo = null,
            baseUrl = "https://addon.test", catalogs = emptyList(),
            types = listOf(com.nexio.tv.domain.model.ContentType.MOVIE),
            resources = listOf(
                com.nexio.tv.domain.model.AddonResource(
                    name = "subtitles", types = listOf("movie"), idPrefixes = null,
                ),
            ),
        )
        every { addonRepo.getInstalledAddons() } returns flowOf(listOf(addon))
        coEvery { addonProvider.getSubtitles(addon, "https://addon.test/subtitles/movie/tt1.json") } returns
            IntegrationCallResult.Success(
                SubtitleResponseDto(
                    subtitles = listOf(SubtitleItemDto(id = "addon-1", url = "https://a/1.srt", lang = "en")),
                ),
            )
        coEvery {
            wyzieProvider.search(
                type = com.nexio.tv.domain.model.ContentType.MOVIE,
                hints = any(),
                sources = any(),
                season = null,
                episode = null,
            )
        } returns listOf(
            com.nexio.tv.data.remote.dto.WyzieSubtitleDto(
                id = "w1", url = "https://w/1.srt", format = "srt", encoding = "UTF-8",
                display = "English", language = "en", media = "X", isHearingImpaired = false,
                source = "opensubtitles",
            ),
        )

        val repository = SubtitleRepositoryImpl(addonProvider, addonRepo, wyzieProvider, noOpenSubtitlesSource())
        val subs = repository.getSubtitles(
            type = "movie", id = "tt1", videoId = null,
            videoHash = null, videoSize = null, filename = null,
            wyzieHints = com.nexio.tv.domain.model.WyzieIdHints(imdb = "tt1"),
            season = null, episode = null,
        )

        assertEquals(2, subs.size)
        assertEquals("addon-1", subs[0].id)
        assertEquals("wyzie:w1", subs[1].id)
        assertEquals("Wyzie · OpenSubtitles", subs[1].addonName)
    }

    @Test
    fun `wyzie lane failure does not affect addon results`() = runTest {
        val addonProvider = mockk<AddonSubtitleIntegrationProvider>()
        val addonRepo = mockk<AddonRepositoryImpl>()
        val wyzieProvider = mockk<com.nexio.tv.data.integration.subtitles.wyzie.WyzieSubtitleIntegrationProvider>()

        val addon = com.nexio.tv.domain.model.Addon(
            id = "addon.id", name = "Addon", displayName = "Addon",
            version = "1.0.0", description = null, logo = null,
            baseUrl = "https://addon.test", catalogs = emptyList(),
            types = listOf(com.nexio.tv.domain.model.ContentType.MOVIE),
            resources = listOf(
                com.nexio.tv.domain.model.AddonResource(
                    name = "subtitles", types = listOf("movie"), idPrefixes = null,
                ),
            ),
        )
        every { addonRepo.getInstalledAddons() } returns flowOf(listOf(addon))
        coEvery { addonProvider.getSubtitles(addon, any()) } returns
            IntegrationCallResult.Success(
                SubtitleResponseDto(
                    subtitles = listOf(SubtitleItemDto(id = "addon-1", url = "https://a/1.srt", lang = "en")),
                ),
            )
        coEvery {
            wyzieProvider.search(any(), any(), any(), any(), any())
        } throws RuntimeException("wyzie boom")

        val repository = SubtitleRepositoryImpl(addonProvider, addonRepo, wyzieProvider, noOpenSubtitlesSource())
        val subs = repository.getSubtitles(
            type = "movie", id = "tt1", videoId = null,
            videoHash = null, videoSize = null, filename = null,
            wyzieHints = com.nexio.tv.domain.model.WyzieIdHints(imdb = "tt1"),
            season = null, episode = null,
        )

        assertEquals(1, subs.size)
        assertEquals("addon-1", subs.single().id)
    }

    @Test
    fun `addon lane failure does not affect wyzie results`() = runTest {
        val addonProvider = mockk<AddonSubtitleIntegrationProvider>()
        val addonRepo = mockk<AddonRepositoryImpl>()
        val wyzieProvider = mockk<com.nexio.tv.data.integration.subtitles.wyzie.WyzieSubtitleIntegrationProvider>()

        every { addonRepo.getInstalledAddons() } returns flow { throw RuntimeException("addon boom") }
        coEvery {
            wyzieProvider.search(any(), any(), any(), any(), any())
        } returns listOf(
            com.nexio.tv.data.remote.dto.WyzieSubtitleDto(
                id = "w1", url = "https://w/1.srt", format = "srt", encoding = "UTF-8",
                display = "English", language = "en", media = "X", isHearingImpaired = false,
                source = "subdl",
            ),
        )

        val repository = SubtitleRepositoryImpl(addonProvider, addonRepo, wyzieProvider, noOpenSubtitlesSource())
        val subs = repository.getSubtitles(
            type = "movie", id = "tt1", videoId = null,
            videoHash = null, videoSize = null, filename = null,
            wyzieHints = com.nexio.tv.domain.model.WyzieIdHints(imdb = "tt1"),
            season = null, episode = null,
        )

        assertEquals(1, subs.size)
        assertEquals("Wyzie · SubDL", subs.single().addonName)
    }

    @Test
    fun `wyzie hints empty skips wyzie call entirely`() = runTest {
        val addonProvider = mockk<AddonSubtitleIntegrationProvider>()
        val addonRepo = mockk<AddonRepositoryImpl>()
        val wyzieProvider = mockk<com.nexio.tv.data.integration.subtitles.wyzie.WyzieSubtitleIntegrationProvider>()

        every { addonRepo.getInstalledAddons() } returns flowOf(emptyList())

        val repository = SubtitleRepositoryImpl(addonProvider, addonRepo, wyzieProvider, noOpenSubtitlesSource())
        val subs = repository.getSubtitles(
            type = "movie", id = "tt1", videoId = null,
            videoHash = null, videoSize = null, filename = null,
            wyzieHints = com.nexio.tv.domain.model.WyzieIdHints.EMPTY,
            season = null, episode = null,
        )
        assertTrue(subs.isEmpty())
        coVerify(exactly = 0) { wyzieProvider.search(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `open subtitles lane results merge after wyzie and addon results`() = runTest {
        val addonProvider = mockk<AddonSubtitleIntegrationProvider>()
        val addonRepo = mockk<AddonRepositoryImpl>()
        val wyzieProvider = mockk<com.nexio.tv.data.integration.subtitles.wyzie.WyzieSubtitleIntegrationProvider>()
        val openSubtitlesSource = mockk<OpenSubtitlesSource>()

        every { addonRepo.getInstalledAddons() } returns flowOf(emptyList())
        coEvery { wyzieProvider.search(any(), any(), any(), any(), any()) } returns listOf(
            com.nexio.tv.data.remote.dto.WyzieSubtitleDto(
                id = "w1", url = "https://w/1.srt", format = "srt", encoding = "UTF-8",
                display = "English", language = "en", media = "X", isHearingImpaired = false,
                source = "subdl",
            ),
        )
        coEvery {
            openSubtitlesSource.search("movie", "tt1", null, "0123456789abcdef", 123L, "movie.mkv")
        } returns listOf(
            Subtitle(
                id = "opensubtitles:1",
                url = "https://os/1.srt",
                lang = "en",
                addonName = "OpenSubtitles",
                addonLogo = null,
                isHashMatch = true,
            )
        )

        val repository = SubtitleRepositoryImpl(addonProvider, addonRepo, wyzieProvider, openSubtitlesSource)
        val subs = repository.getSubtitles(
            type = "movie",
            id = "tt1",
            videoId = null,
            videoHash = "0123456789abcdef",
            videoSize = 123L,
            filename = "movie.mkv",
            wyzieHints = com.nexio.tv.domain.model.WyzieIdHints(imdb = "tt1"),
        )

        assertEquals(2, subs.size)
        assertEquals("wyzie:w1", subs[0].id)
        assertEquals("opensubtitles:1", subs[1].id)
        assertTrue(subs[1].isHashMatch)
    }

    private fun noOpenSubtitlesSource(): OpenSubtitlesSource =
        object : OpenSubtitlesSource {
            override suspend fun search(
                type: String,
                id: String,
                videoId: String?,
                videoHash: String?,
                videoSize: Long?,
                filename: String?
            ): List<Subtitle> = emptyList()
        }
}
