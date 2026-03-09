package com.nexio.tv.data.repository

import com.nexio.tv.core.network.NetworkResult
import com.nexio.tv.data.local.DebugSettingsDataStore
import com.nexio.tv.data.remote.api.AddonApi
import com.nexio.tv.data.remote.dto.StreamDto
import com.nexio.tv.data.remote.dto.StreamResponseDto
import com.nexio.tv.domain.model.Addon
import com.nexio.tv.domain.model.AddonResource
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.repository.AddonRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

class StreamRepositoryImplTest {

    @Test
    fun `getStreamsFromAllAddons emits progressive results and completes`() = runTest {
        val addonApi = mockk<AddonApi>()
        val addonRepository = mockk<AddonRepository>()
        val debugSettingsDataStore = mockk<DebugSettingsDataStore>()
        every { debugSettingsDataStore.streamDiagnosticsEnabled } returns flowOf(false)

        val addonA = streamAddon("https://addon-a.example", "Addon A")
        val addonB = streamAddon("https://addon-b.example", "Addon B")
        val addonC = streamAddon("https://addon-c.example", "Addon C")

        every { addonRepository.getInstalledAddons() } returns flowOf(listOf(addonA, addonB, addonC))
        coEvery { addonApi.getStreams(match { it.contains("addon-a.example") }) } coAnswers {
            delay(60)
            Response.success(StreamResponseDto(streams = listOf(streamDto("A-1"))))
        }
        coEvery { addonApi.getStreams(match { it.contains("addon-b.example") }) } coAnswers {
            delay(10)
            Response.success(StreamResponseDto(streams = emptyList()))
        }
        coEvery { addonApi.getStreams(match { it.contains("addon-c.example") }) } coAnswers {
            delay(20)
            Response.success(StreamResponseDto(streams = listOf(streamDto("C-1"))))
        }

        val repository = StreamRepositoryImpl(
            api = addonApi,
            addonRepository = addonRepository,
            debugSettingsDataStore = debugSettingsDataStore
        )

        val emissions = withTimeout(5_000L) {
            repository.getStreamsFromAllAddons(
                type = "movie",
                videoId = "tt1234567",
                requestOrigin = "test_progressive"
            ).toList()
        }

        assertTrue(emissions.first() is NetworkResult.Loading)
        val successes = emissions.filterIsInstance<NetworkResult.Success<List<com.nexio.tv.domain.model.AddonStreams>>>()
        assertEquals(2, successes.size)
        assertEquals(1, successes[0].data.size)
        assertEquals(2, successes[1].data.size)
        val finalAddonNames = successes.last().data.map { it.addonName }.toSet()
        assertEquals(setOf("Addon A", "Addon C"), finalAddonNames)
    }

    @Test
    fun `getStreamsFromAllAddons completes with empty success when no addon returns links`() = runTest {
        val addonApi = mockk<AddonApi>()
        val addonRepository = mockk<AddonRepository>()
        val debugSettingsDataStore = mockk<DebugSettingsDataStore>()
        every { debugSettingsDataStore.streamDiagnosticsEnabled } returns flowOf(false)

        val addonA = streamAddon("https://addon-a.example", "Addon A")
        val addonB = streamAddon("https://addon-b.example", "Addon B")
        val addonC = streamAddon("https://addon-c.example", "Addon C")

        every { addonRepository.getInstalledAddons() } returns flowOf(listOf(addonA, addonB, addonC))
        coEvery { addonApi.getStreams(match { it.contains("addon-a.example") }) } returns Response.error(
            503,
            "Service unavailable".toResponseBody("text/plain".toMediaType())
        )
        coEvery { addonApi.getStreams(match { it.contains("addon-b.example") }) } throws RuntimeException("boom")
        coEvery { addonApi.getStreams(match { it.contains("addon-c.example") }) } returns Response.success(
            StreamResponseDto(streams = emptyList())
        )

        val repository = StreamRepositoryImpl(
            api = addonApi,
            addonRepository = addonRepository,
            debugSettingsDataStore = debugSettingsDataStore
        )

        val emissions = withTimeout(5_000L) {
            repository.getStreamsFromAllAddons(
                type = "movie",
                videoId = "tt7654321",
                requestOrigin = "test_no_results"
            ).toList()
        }

        assertTrue(emissions.first() is NetworkResult.Loading)
        val successes = emissions.filterIsInstance<NetworkResult.Success<List<com.nexio.tv.domain.model.AddonStreams>>>()
        assertEquals(1, successes.size)
        assertTrue(successes.single().data.isEmpty())
    }

    private fun streamAddon(baseUrl: String, displayName: String): Addon {
        return Addon(
            id = displayName.lowercase().replace(" ", "_"),
            name = displayName,
            displayName = displayName,
            version = "1.0.0",
            description = null,
            logo = null,
            baseUrl = baseUrl,
            catalogs = emptyList(),
            types = listOf(ContentType.MOVIE),
            resources = listOf(
                AddonResource(
                    name = "stream",
                    types = listOf("movie"),
                    idPrefixes = null
                )
            )
        )
    }

    private fun streamDto(name: String): StreamDto {
        return StreamDto(
            name = name,
            url = "https://cdn.example/$name.m3u8"
        )
    }
}
