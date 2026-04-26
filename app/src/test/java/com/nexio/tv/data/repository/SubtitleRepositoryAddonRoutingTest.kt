package com.nexio.tv.data.repository

import com.nexio.tv.core.integration.IntegrationCallResult
import com.nexio.tv.core.integration.IntegrationCallSpec
import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.core.integration.IntegrationRuntime
import com.nexio.tv.core.integration.IntegrationScope
import com.nexio.tv.core.integration.IntegrationWorkClass
import com.nexio.tv.core.integration.defaultIntegrationPolicyRegistry
import com.nexio.tv.core.sync.buildAddonRequestUrl
import com.nexio.tv.data.integration.addon.AddonSubtitleIntegrationProvider
import com.nexio.tv.data.remote.api.AddonApi
import com.nexio.tv.data.remote.dto.SubtitleItemDto
import com.nexio.tv.data.remote.dto.SubtitleResponseDto
import com.nexio.tv.domain.model.Addon
import com.nexio.tv.domain.model.AddonResource
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertSame
import org.junit.Test
import retrofit2.Response

class SubtitleRepositoryAddonRoutingTest {
    @Test
    fun `default integration policy allows addon subtitle requests during playback without serializing them`() {
        val policy = defaultIntegrationPolicyRegistry().policyFor(IntegrationProvider.ADDON)

        assertTrue(policy.allowDuringPlayback)
        assertTrue(policy.maxConcurrentNetworkStarts > 1)
    }

    @Test
    fun `addon subtitle provider routes addon calls through integration runtime`() = runTest {
        val runtime = mockk<IntegrationRuntime>(relaxed = true)
        val addonApi = mockk<AddonApi>()
        val specSlot = slot<IntegrationCallSpec<SubtitleResponseDto>>()
        var runtimeResult: IntegrationCallResult<SubtitleResponseDto>? = null
        val addon = subtitleAddon()
        val subtitleUrl = "https://addon.example/subtitles/series/tt123:1:2.json"

        coEvery { runtime.call(capture(specSlot)) } coAnswers {
            val result = specSlot.captured.call()
            runtimeResult = result
            result
        }
        coEvery { addonApi.getSubtitles(subtitleUrl) } returns Response.success(
            SubtitleResponseDto(
                subtitles = listOf(
                    SubtitleItemDto(
                        id = "sub-1",
                        url = "https://cdn.example/sub-1.vtt",
                        lang = "en"
                    )
                )
            )
        )

        val provider = AddonSubtitleIntegrationProvider(runtime, addonApi)
        val result = provider.getSubtitles(addon = addon, subtitleUrl = subtitleUrl)

        assertTrue(result is IntegrationCallResult.Success<*>)
        assertSame(runtimeResult, result)
        assertEquals(IntegrationProvider.ADDON, specSlot.captured.provider)
        assertEquals(IntegrationWorkClass.USER_VISIBLE, specSlot.captured.workClass)
        assertEquals(IntegrationScope.Account("addon:${addon.id}"), specSlot.captured.scope)
        coVerify(exactly = 1) {
            runtime.call(any<IntegrationCallSpec<SubtitleResponseDto>>())
            addonApi.getSubtitles(subtitleUrl)
        }
    }

    @Test
    fun `subtitle repository fetches subtitles through addon integration provider and preserves mapping`() = runTest {
        val addon = subtitleAddon()
        val addonRepository = mockk<AddonRepositoryImpl>()
        val provider = mockk<AddonSubtitleIntegrationProvider>()
        val subtitleUrl = buildAddonRequestUrl(
            addon.baseUrl,
            "subtitles/series/tt123:1:2/videoHash=hash123&videoSize=321&filename=Episode.mkv.json"
        )

        every { addonRepository.getInstalledAddons() } returns flowOf(listOf(addon))
        coEvery { provider.getSubtitles(addon, subtitleUrl) } returns IntegrationCallResult.Success(
            SubtitleResponseDto(
                subtitles = listOf(
                    SubtitleItemDto(
                        id = null,
                        url = "https://cdn.example/subtitles/en.vtt",
                        lang = "en"
                    )
                )
            )
        )

        val repository = SubtitleRepositoryImpl(
            addonSubtitleIntegrationProvider = provider,
            addonRepository = addonRepository
        )

        val result = repository.getSubtitles(
            type = "tv",
            id = "tt123",
            videoId = "tt123:1:2",
            videoHash = "hash123",
            videoSize = 321L,
            filename = "Episode.mkv"
        )

        assertEquals(1, result.size)
        assertEquals("https://cdn.example/subtitles/en.vtt", result.single().url)
        assertEquals("en", result.single().lang)
        assertEquals("Addon Display", result.single().addonName)
        assertEquals("https://addon.example/logo.png", result.single().addonLogo)
        assertEquals(
            "en-${result.single().url.hashCode()}",
            result.single().id
        )
        coVerify(exactly = 1) { provider.getSubtitles(addon, subtitleUrl) }
    }

    private fun subtitleAddon(): Addon {
        return Addon(
            id = "community.addon",
            name = "Community Addon",
            displayName = "Addon Display",
            version = "1.0.0",
            description = null,
            logo = "https://addon.example/logo.png",
            baseUrl = "https://addon.example",
            catalogs = emptyList(),
            types = emptyList(),
            resources = listOf(
                AddonResource(
                    name = "subtitles",
                    types = listOf("series"),
                    idPrefixes = listOf("tt")
                )
            )
        )
    }
}
