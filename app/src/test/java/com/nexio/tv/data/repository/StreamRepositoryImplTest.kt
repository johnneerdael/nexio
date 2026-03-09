package com.nexio.tv.data.repository

import com.nexio.tv.core.network.NetworkResult
import com.nexio.tv.data.local.DebugSettingsDataStore
import com.nexio.tv.data.remote.api.AddonApi
import com.nexio.tv.data.remote.api.StreamSearchRequestTag
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
import okhttp3.Call
import okhttp3.Dispatcher
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
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
        val okHttpClient = mockk<OkHttpClient>(relaxed = true)
        val dispatcher = mockk<Dispatcher>()
        every { debugSettingsDataStore.streamDiagnosticsEnabled } returns flowOf(false)
        every { okHttpClient.dispatcher } returns dispatcher
        every { dispatcher.runningCalls() } returns mutableListOf()
        every { dispatcher.queuedCalls() } returns mutableListOf()

        val addonA = streamAddon("https://addon-a.example", "Addon A")
        val addonB = streamAddon("https://addon-b.example", "Addon B")
        val addonC = streamAddon("https://addon-c.example", "Addon C")

        every { addonRepository.getInstalledAddons() } returns flowOf(listOf(addonA, addonB, addonC))
        coEvery { addonApi.getStreams(match { it.contains("addon-a.example") }, any()) } coAnswers {
            delay(60)
            Response.success(StreamResponseDto(streams = listOf(streamDto("A-1"))))
        }
        coEvery { addonApi.getStreams(match { it.contains("addon-b.example") }, any()) } coAnswers {
            delay(10)
            Response.success(StreamResponseDto(streams = emptyList()))
        }
        coEvery { addonApi.getStreams(match { it.contains("addon-c.example") }, any()) } coAnswers {
            delay(20)
            Response.success(StreamResponseDto(streams = listOf(streamDto("C-1"))))
        }

        val repository = StreamRepositoryImpl(
            api = addonApi,
            addonRepository = addonRepository,
            debugSettingsDataStore = debugSettingsDataStore,
            okHttpClient = okHttpClient
        )

        val emissions = withTimeout(5_000L) {
            repository.getStreamsFromAllAddons(
                type = "movie",
                videoId = "tt1234567",
                requestOrigin = "test_progressive",
                requestId = "request-progressive"
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
        val okHttpClient = mockk<OkHttpClient>(relaxed = true)
        val dispatcher = mockk<Dispatcher>()
        every { debugSettingsDataStore.streamDiagnosticsEnabled } returns flowOf(false)
        every { okHttpClient.dispatcher } returns dispatcher
        every { dispatcher.runningCalls() } returns mutableListOf()
        every { dispatcher.queuedCalls() } returns mutableListOf()

        val addonA = streamAddon("https://addon-a.example", "Addon A")
        val addonB = streamAddon("https://addon-b.example", "Addon B")
        val addonC = streamAddon("https://addon-c.example", "Addon C")

        every { addonRepository.getInstalledAddons() } returns flowOf(listOf(addonA, addonB, addonC))
        coEvery { addonApi.getStreams(match { it.contains("addon-a.example") }, any()) } returns Response.error(
            503,
            "Service unavailable".toResponseBody("text/plain".toMediaType())
        )
        coEvery { addonApi.getStreams(match { it.contains("addon-b.example") }, any()) } throws RuntimeException("boom")
        coEvery { addonApi.getStreams(match { it.contains("addon-c.example") }, any()) } returns Response.success(
            StreamResponseDto(streams = emptyList())
        )

        val repository = StreamRepositoryImpl(
            api = addonApi,
            addonRepository = addonRepository,
            debugSettingsDataStore = debugSettingsDataStore,
            okHttpClient = okHttpClient
        )

        val emissions = withTimeout(5_000L) {
            repository.getStreamsFromAllAddons(
                type = "movie",
                videoId = "tt7654321",
                requestOrigin = "test_no_results",
                requestId = "request-empty"
            ).toList()
        }

        assertTrue(emissions.first() is NetworkResult.Loading)
        val successes = emissions.filterIsInstance<NetworkResult.Success<List<com.nexio.tv.domain.model.AddonStreams>>>()
        assertEquals(1, successes.size)
        assertTrue(successes.single().data.isEmpty())
    }

    @Test
    fun `cancelActiveStreamRequests only cancels matching tagged addon stream calls`() {
        val addonApi = mockk<AddonApi>()
        val addonRepository = mockk<AddonRepository>()
        val debugSettingsDataStore = mockk<DebugSettingsDataStore>()
        val okHttpClient = mockk<OkHttpClient>()
        val dispatcher = mockk<Dispatcher>()
        val runningStreamCall = mockk<Call>(relaxed = true)
        val queuedStreamCall = mockk<Call>(relaxed = true)
        val metaCall = mockk<Call>(relaxed = true)

        every { debugSettingsDataStore.streamDiagnosticsEnabled } returns flowOf(false)
        every { okHttpClient.dispatcher } returns dispatcher
        every { dispatcher.runningCalls() } returns mutableListOf(runningStreamCall, metaCall)
        every { dispatcher.queuedCalls() } returns mutableListOf(queuedStreamCall)
        every { runningStreamCall.request() } returns Request.Builder()
            .url("https://addon-a.example/stream/movie/tt123.json")
            .tag(StreamSearchRequestTag::class.java, StreamSearchRequestTag("request-a"))
            .build()
        every { queuedStreamCall.request() } returns Request.Builder()
            .url("https://addon-b.example/stream/movie/tt456.json")
            .tag(StreamSearchRequestTag::class.java, StreamSearchRequestTag("request-b"))
            .build()
        every { metaCall.request() } returns Request.Builder()
            .url("https://addon-c.example/meta/movie/tt789.json")
            .tag(StreamSearchRequestTag::class.java, StreamSearchRequestTag("request-a"))
            .build()

        val repository = StreamRepositoryImpl(
            api = addonApi,
            addonRepository = addonRepository,
            debugSettingsDataStore = debugSettingsDataStore,
            okHttpClient = okHttpClient
        )

        repository.cancelActiveStreamRequests("request-a")

        io.mockk.verify(exactly = 1) { runningStreamCall.cancel() }
        io.mockk.verify(exactly = 0) { queuedStreamCall.cancel() }
        io.mockk.verify(exactly = 0) { metaCall.cancel() }
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
