package com.nexio.tv.data.repository

import com.nexio.tv.core.integration.IntegrationCallResult
import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.core.integration.IntegrationWorkClass
import com.nexio.tv.core.integration.RecordingIntegrationRuntime
import com.nexio.tv.data.integration.simkl.SimklIntegrationProvider
import com.nexio.tv.data.integration.simkl.transport.SimklDiscoveryTransport
import com.nexio.tv.data.integration.simkl.transport.SimklDiscoveryTransportResult
import com.nexio.tv.data.remote.api.SimklApi
import com.nexio.tv.data.remote.dto.simkl.SimklAddToListRequestDto
import com.nexio.tv.data.remote.dto.simkl.SimklAddToListResponseDto
import com.nexio.tv.data.remote.dto.simkl.SimklLibraryItemDto
import com.nexio.tv.data.remote.dto.simkl.SimklLastActivitiesResponseDto
import com.nexio.tv.data.remote.dto.simkl.SimklPlaybackItemDto
import com.nexio.tv.data.remote.dto.simkl.SimklHistoryAddRequestDto
import com.nexio.tv.data.remote.dto.simkl.SimklHistoryRemoveRequestDto
import com.nexio.tv.data.remote.dto.simkl.SimklScrobbleRequestDto
import com.nexio.tv.data.remote.dto.simkl.SimklScrobbleResponseDto
import com.nexio.tv.domain.model.TrackingProvider
import com.squareup.moshi.Moshi
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.Response

class SimklIntegrationProviderDiscoveryTest {
    @Test
    fun `fetchDiscoveryBody uses runtime call path for simkl discovery`() = runTest {
        val runtime = RecordingIntegrationRuntime<String>(
            nextCallResult = IntegrationCallResult.Success("[]")
        )
        val provider = SimklIntegrationProvider(
            runtime = runtime,
            simklApi = mockk<SimklApi>(relaxed = true),
            simklAuthService = mockk<SimklAuthService>(relaxed = true),
            moshi = Moshi.Builder().build(),
            transport = mockk<SimklDiscoveryTransport>(relaxed = true)
        )

        val body = provider.fetchDiscoveryBody("https://data.simkl.in/discover/trending/tv/today_100.json")

        assertEquals("[]", body)
        assertEquals(1, runtime.callSpecs.size)
        assertEquals(IntegrationProvider.SIMKL, runtime.callSpecs.single().provider)
        assertEquals(IntegrationWorkClass.USER_VISIBLE, runtime.callSpecs.single().workClass)
    }

    @Test
    fun `fetchDiscoveryBody maps transport result to integration call result`() = runTest {
        val runtime = mockk<com.nexio.tv.core.integration.IntegrationRuntime>()
        val transport = mockk<SimklDiscoveryTransport>()
        val specSlot = slot<com.nexio.tv.core.integration.IntegrationCallSpec<String?>>()

        every { transport.fetchDiscoveryBody(any()) } returns SimklDiscoveryTransportResult.HttpError(503)
        coEvery { runtime.call(capture(specSlot)) } coAnswers {
            specSlot.captured.call()
        }

        val provider = SimklIntegrationProvider(
            runtime = runtime,
            simklApi = mockk<SimklApi>(relaxed = true),
            simklAuthService = mockk<SimklAuthService>(relaxed = true),
            moshi = Moshi.Builder().build(),
            transport = transport
        )

        val body = provider.fetchDiscoveryBody("https://data.simkl.in/discover/trending/tv/today_100.json")
        val specResult = specSlot.captured.call()

        assertEquals(null, body)
        assertEquals(IntegrationProvider.SIMKL, specSlot.captured.provider)
        assertEquals(IntegrationWorkClass.USER_VISIBLE, specSlot.captured.workClass)
        assertTrue(specResult is IntegrationCallResult.HttpError)
        assertEquals(503, (specResult as IntegrationCallResult.HttpError).statusCode)
    }

    @Test
    fun `getLastActivities uses runtime call path for simkl tracking`() = runTest {
        val response = Response.success(SimklLastActivitiesResponseDto())
        val runtime = RecordingIntegrationRuntime<Response<SimklLastActivitiesResponseDto>>(
            nextCallResult = IntegrationCallResult.Success(response)
        )
        val provider = SimklIntegrationProvider(
            runtime = runtime,
            simklApi = mockk<SimklApi>(relaxed = true),
            simklAuthService = mockk<SimklAuthService>(relaxed = true),
            moshi = Moshi.Builder().build(),
            transport = mockk<SimklDiscoveryTransport>(relaxed = true)
        )

        val result = provider.getLastActivities()

        assertSame(response, result)
        assertEquals(1, runtime.callSpecs.size)
        assertEquals(IntegrationProvider.SIMKL, runtime.callSpecs.single().provider)
    }

    @Test
    fun `getAllItems runtime call uses default authorized request`() = runTest {
        val response = Response.success(
            listOf(
                SimklLibraryItemDto(
                    status = "completed"
                )
            )
        )
        val runtime = RecordingIntegrationRuntime<Response<List<SimklLibraryItemDto>>>(
            nextCallResult = IntegrationCallResult.Success(response)
        )
        val provider = SimklIntegrationProvider(
            runtime = runtime,
            simklApi = mockk<SimklApi>(relaxed = true),
            simklAuthService = mockk<SimklAuthService>(relaxed = true),
            moshi = Moshi.Builder().build(),
            transport = mockk<SimklDiscoveryTransport>(relaxed = true)
        )

        val result = provider.getAllItems(extended = "full")

        assertSame(response, result)
        assertEquals(1, runtime.callSpecs.size)
        assertEquals(IntegrationProvider.SIMKL, runtime.callSpecs.single().provider)
    }

    @Test
    fun `getPlayback uses runtime call path for simkl tracking`() = runTest {
        val response = Response.success(listOf(SimklPlaybackItemDto(id = 3L)))
        val runtime = RecordingIntegrationRuntime<Response<List<SimklPlaybackItemDto>>>(
            nextCallResult = IntegrationCallResult.Success(response)
        )
        val provider = SimklIntegrationProvider(
            runtime = runtime,
            simklApi = mockk<SimklApi>(relaxed = true),
            simklAuthService = mockk<SimklAuthService>(relaxed = true),
            moshi = Moshi.Builder().build(),
            transport = mockk<SimklDiscoveryTransport>(relaxed = true)
        )

        val result = provider.getPlayback("episodes")

        assertSame(response, result)
        assertEquals(1, runtime.callSpecs.size)
        assertEquals(IntegrationProvider.SIMKL, runtime.callSpecs.single().provider)
    }

    @Test
    fun `deletePlayback uses runtime call path for simkl tracking`() = runTest {
        val response = Response.success(Unit)
        val runtime = RecordingIntegrationRuntime<Response<Unit>>(
            nextCallResult = IntegrationCallResult.Success(response)
        )
        val provider = SimklIntegrationProvider(
            runtime = runtime,
            simklApi = mockk<SimklApi>(relaxed = true),
            simklAuthService = mockk<SimklAuthService>(relaxed = true),
            moshi = Moshi.Builder().build(),
            transport = mockk<SimklDiscoveryTransport>(relaxed = true)
        )

        val result = provider.deletePlayback(9L)

        assertSame(response, result)
        assertEquals(1, runtime.callSpecs.size)
        assertEquals(IntegrationProvider.SIMKL, runtime.callSpecs.single().provider)
    }

    @Test
    fun `addToList uses runtime call path for simkl tracking`() = runTest {
        val response = Response.success(SimklAddToListResponseDto(result = "ok"))
        val runtime = RecordingIntegrationRuntime<Response<SimklAddToListResponseDto>>(
            nextCallResult = IntegrationCallResult.Success(response)
        )
        val provider = SimklIntegrationProvider(
            runtime = runtime,
            simklApi = mockk<SimklApi>(relaxed = true),
            simklAuthService = mockk<SimklAuthService>(relaxed = true),
            moshi = Moshi.Builder().build(),
            transport = mockk<SimklDiscoveryTransport>(relaxed = true)
        )

        val result = provider.addToList(
            body = SimklAddToListRequestDto(to = "completed")
        )

        assertSame(response, result)
        assertEquals(1, runtime.callSpecs.size)
        assertEquals(IntegrationProvider.SIMKL, runtime.callSpecs.single().provider)
    }

    @Test
    fun `getLastActivities runtime call uses default authorized request`() = runTest {
        val runtime = RecordingIntegrationRuntime<Response<SimklLastActivitiesResponseDto>>()
        val api = mockk<SimklApi>()
        val authService = mockk<SimklAuthService>()
        val response = Response.success(SimklLastActivitiesResponseDto())
        val provider = SimklIntegrationProvider(
            runtime = runtime,
            simklApi = api,
            simklAuthService = authService,
            moshi = Moshi.Builder().build(),
            transport = mockk<SimklDiscoveryTransport>(relaxed = true)
        )

        coEvery { authService.executeAuthOwnerRequest<SimklLastActivitiesResponseDto>(any()) } coAnswers {
            firstArg<suspend (String) -> Response<SimklLastActivitiesResponseDto>>().invoke("Bearer token")
        }
        coEvery { api.getLastActivities("Bearer token") } returns response

        provider.getLastActivities()
        val result = runtime.callSpecs.single().call.invoke()

        assertTrue(result is IntegrationCallResult.Success)
        assertSame(response, (result as IntegrationCallResult.Success).value)
        coVerify(exactly = 1) { authService.executeAuthOwnerRequest<SimklLastActivitiesResponseDto>(any()) }
        coVerify(exactly = 1) { api.getLastActivities("Bearer token") }
    }

    @Test
    fun `addToList runtime call uses default authorized write request`() = runTest {
        val runtime = RecordingIntegrationRuntime<Response<SimklAddToListResponseDto>>()
        val api = mockk<SimklApi>()
        val authService = mockk<SimklAuthService>()
        val response = Response.success(SimklAddToListResponseDto(result = "ok"))
        val body = SimklAddToListRequestDto(to = "completed")
        val provider = SimklIntegrationProvider(
            runtime = runtime,
            simklApi = api,
            simklAuthService = authService,
            moshi = Moshi.Builder().build(),
            transport = mockk<SimklDiscoveryTransport>(relaxed = true)
        )

        coEvery { authService.executeAuthorizedWriteRequest<SimklAddToListResponseDto>(any()) } coAnswers {
            firstArg<suspend (String) -> Response<SimklAddToListResponseDto>>().invoke("Bearer token")
        }
        coEvery { api.addToList("Bearer token", body) } returns response

        provider.addToList(body)
        val result = runtime.callSpecs.single().call.invoke()

        assertTrue(result is IntegrationCallResult.Success)
        assertSame(response, (result as IntegrationCallResult.Success).value)
        coVerify(exactly = 1) { authService.executeAuthorizedWriteRequest<SimklAddToListResponseDto>(any()) }
        coVerify(exactly = 1) { api.addToList("Bearer token", body) }
    }

    @Test
    fun `addToList runtime call preserves non-success write response`() = runTest {
        val runtime = RecordingIntegrationRuntime<Response<SimklAddToListResponseDto>>()
        val api = mockk<SimklApi>()
        val authService = mockk<SimklAuthService>()
        val response = Response.error<SimklAddToListResponseDto>(503, "Service unavailable".toResponseBody())
        val body = SimklAddToListRequestDto(to = "completed")
        val provider = SimklIntegrationProvider(
            runtime = runtime,
            simklApi = api,
            simklAuthService = authService,
            moshi = Moshi.Builder().build(),
            transport = mockk<SimklDiscoveryTransport>(relaxed = true)
        )

        coEvery { authService.executeAuthorizedWriteRequest<SimklAddToListResponseDto>(any()) } coAnswers {
            firstArg<suspend (String) -> Response<SimklAddToListResponseDto>>().invoke("Bearer token")
        }
        coEvery { api.addToList("Bearer token", body) } returns response

        provider.addToList(body)
        val result = runtime.callSpecs.single().call.invoke()

        assertTrue(result is IntegrationCallResult.Success)
        assertSame(response, (result as IntegrationCallResult.Success).value)
        coVerify(exactly = 1) { authService.executeAuthorizedWriteRequest<SimklAddToListResponseDto>(any()) }
        coVerify(exactly = 1) { api.addToList("Bearer token", body) }
    }

    @Test
    fun `scrobbleStart runtime call uses default authorized write request`() = runTest {
        val runtime = RecordingIntegrationRuntime<Response<SimklScrobbleResponseDto>>()
        val api = mockk<SimklApi>()
        val authService = mockk<SimklAuthService>()
        val response = Response.success(SimklScrobbleResponseDto(action = "start"))
        val body = SimklScrobbleRequestDto(progress = 45.0f)
        val provider = SimklIntegrationProvider(
            runtime = runtime,
            simklApi = api,
            simklAuthService = authService,
            moshi = Moshi.Builder().build(),
            transport = mockk<SimklDiscoveryTransport>(relaxed = true)
        )

        coEvery { authService.executeAuthorizedWriteRequest<SimklScrobbleResponseDto>(any()) } coAnswers {
            firstArg<suspend (String) -> Response<SimklScrobbleResponseDto>>().invoke("Bearer token")
        }
        coEvery { api.scrobbleStart("Bearer token", body) } returns response

        provider.scrobbleStart(body)
        val result = runtime.callSpecs.single().call.invoke()

        assertTrue(result is IntegrationCallResult.Success)
        assertSame(response, (result as IntegrationCallResult.Success).value)
        coVerify(exactly = 1) { authService.executeAuthorizedWriteRequest<SimklScrobbleResponseDto>(any()) }
        coVerify(exactly = 1) { api.scrobbleStart("Bearer token", body) }
    }

    @Test
    fun `scrobbleStart runtime call preserves non-success write response`() = runTest {
        val runtime = RecordingIntegrationRuntime<Response<SimklScrobbleResponseDto>>()
        val api = mockk<SimklApi>()
        val authService = mockk<SimklAuthService>()
        val response = Response.error<SimklScrobbleResponseDto>(503, "Service unavailable".toResponseBody())
        val body = SimklScrobbleRequestDto(progress = 45.0f)
        val provider = SimklIntegrationProvider(
            runtime = runtime,
            simklApi = api,
            simklAuthService = authService,
            moshi = Moshi.Builder().build(),
            transport = mockk<SimklDiscoveryTransport>(relaxed = true)
        )

        coEvery { authService.executeAuthorizedWriteRequest<SimklScrobbleResponseDto>(any()) } coAnswers {
            firstArg<suspend (String) -> Response<SimklScrobbleResponseDto>>().invoke("Bearer token")
        }
        coEvery { api.scrobbleStart("Bearer token", body) } returns response

        provider.scrobbleStart(body)
        val result = runtime.callSpecs.single().call.invoke()

        assertTrue(result is IntegrationCallResult.Success)
        assertSame(response, (result as IntegrationCallResult.Success).value)
        coVerify(exactly = 1) { authService.executeAuthorizedWriteRequest<SimklScrobbleResponseDto>(any()) }
        coVerify(exactly = 1) { api.scrobbleStart("Bearer token", body) }
    }

    @Test
    fun `checkin runtime call uses default authorized write request`() = runTest {
        val runtime = RecordingIntegrationRuntime<Response<SimklScrobbleResponseDto>>()
        val api = mockk<SimklApi>()
        val authService = mockk<SimklAuthService>()
        val response = Response.success(SimklScrobbleResponseDto(action = "checkin"))
        val body = SimklScrobbleRequestDto(progress = 52.0f)
        val provider = SimklIntegrationProvider(
            runtime = runtime,
            simklApi = api,
            simklAuthService = authService,
            moshi = Moshi.Builder().build(),
            transport = mockk<SimklDiscoveryTransport>(relaxed = true)
        )

        coEvery { authService.executeAuthorizedWriteRequest<SimklScrobbleResponseDto>(any()) } coAnswers {
            firstArg<suspend (String) -> Response<SimklScrobbleResponseDto>>().invoke("Bearer token")
        }
        coEvery { api.checkin("Bearer token", body) } returns response

        provider.checkin(body)
        val result = runtime.callSpecs.single().call.invoke()

        assertTrue(result is IntegrationCallResult.Success)
        assertSame(response, (result as IntegrationCallResult.Success).value)
        coVerify(exactly = 1) { authService.executeAuthorizedWriteRequest<SimklScrobbleResponseDto>(any()) }
        coVerify(exactly = 1) { api.checkin("Bearer token", body) }
    }

    @Test
    fun `checkin runtime call preserves non-success write response`() = runTest {
        val runtime = RecordingIntegrationRuntime<Response<SimklScrobbleResponseDto>>()
        val api = mockk<SimklApi>()
        val authService = mockk<SimklAuthService>()
        val response = Response.error<SimklScrobbleResponseDto>(503, "Service unavailable".toResponseBody())
        val body = SimklScrobbleRequestDto(progress = 52.0f)
        val provider = SimklIntegrationProvider(
            runtime = runtime,
            simklApi = api,
            simklAuthService = authService,
            moshi = Moshi.Builder().build(),
            transport = mockk<SimklDiscoveryTransport>(relaxed = true)
        )

        coEvery { authService.executeAuthorizedWriteRequest<SimklScrobbleResponseDto>(any()) } coAnswers {
            firstArg<suspend (String) -> Response<SimklScrobbleResponseDto>>().invoke("Bearer token")
        }
        coEvery { api.checkin("Bearer token", body) } returns response

        provider.checkin(body)
        val result = runtime.callSpecs.single().call.invoke()

        assertTrue(result is IntegrationCallResult.Success)
        assertSame(response, (result as IntegrationCallResult.Success).value)
        coVerify(exactly = 1) { authService.executeAuthorizedWriteRequest<SimklScrobbleResponseDto>(any()) }
        coVerify(exactly = 1) { api.checkin("Bearer token", body) }
    }

    @Test
    fun `scrobbleStop runtime call uses default authorized write request`() = runTest {
        val runtime = RecordingIntegrationRuntime<Response<SimklScrobbleResponseDto>>()
        val api = mockk<SimklApi>()
        val authService = mockk<SimklAuthService>()
        val response = Response.success(SimklScrobbleResponseDto(action = "stop"))
        val body = SimklScrobbleRequestDto(progress = 45.0f)
        val provider = SimklIntegrationProvider(
            runtime = runtime,
            simklApi = api,
            simklAuthService = authService,
            moshi = Moshi.Builder().build(),
            transport = mockk<SimklDiscoveryTransport>(relaxed = true)
        )

        coEvery { authService.executeAuthorizedWriteRequest<SimklScrobbleResponseDto>(any()) } coAnswers {
            firstArg<suspend (String) -> Response<SimklScrobbleResponseDto>>().invoke("Bearer token")
        }
        coEvery { api.scrobbleStop("Bearer token", body) } returns response

        provider.scrobbleStop(body)
        val result = runtime.callSpecs.single().call.invoke()

        assertTrue(result is IntegrationCallResult.Success)
        assertSame(response, (result as IntegrationCallResult.Success).value)
        coVerify(exactly = 1) { authService.executeAuthorizedWriteRequest<SimklScrobbleResponseDto>(any()) }
        coVerify(exactly = 1) { api.scrobbleStop("Bearer token", body) }
    }

    @Test
    fun `scrobbleStop runtime call preserves non-success write response`() = runTest {
        val runtime = RecordingIntegrationRuntime<Response<SimklScrobbleResponseDto>>()
        val api = mockk<SimklApi>()
        val authService = mockk<SimklAuthService>()
        val response = Response.error<SimklScrobbleResponseDto>(503, "Service unavailable".toResponseBody())
        val body = SimklScrobbleRequestDto(progress = 45.0f)
        val provider = SimklIntegrationProvider(
            runtime = runtime,
            simklApi = api,
            simklAuthService = authService,
            moshi = Moshi.Builder().build(),
            transport = mockk<SimklDiscoveryTransport>(relaxed = true)
        )

        coEvery { authService.executeAuthorizedWriteRequest<SimklScrobbleResponseDto>(any()) } coAnswers {
            firstArg<suspend (String) -> Response<SimklScrobbleResponseDto>>().invoke("Bearer token")
        }
        coEvery { api.scrobbleStop("Bearer token", body) } returns response

        provider.scrobbleStop(body)
        val result = runtime.callSpecs.single().call.invoke()

        assertTrue(result is IntegrationCallResult.Success)
        assertSame(response, (result as IntegrationCallResult.Success).value)
        coVerify(exactly = 1) { authService.executeAuthorizedWriteRequest<SimklScrobbleResponseDto>(any()) }
        coVerify(exactly = 1) { api.scrobbleStop("Bearer token", body) }
    }

    @Test
    fun `scrobblePause runtime call uses default authorized write request`() = runTest {
        val runtime = RecordingIntegrationRuntime<Response<SimklScrobbleResponseDto>>()
        val api = mockk<SimklApi>()
        val authService = mockk<SimklAuthService>()
        val response = Response.success(SimklScrobbleResponseDto(action = "pause"))
        val body = SimklScrobbleRequestDto(progress = 45.0f)
        val provider = SimklIntegrationProvider(
            runtime = runtime,
            simklApi = api,
            simklAuthService = authService,
            moshi = Moshi.Builder().build(),
            transport = mockk<SimklDiscoveryTransport>(relaxed = true)
        )

        coEvery { authService.executeAuthorizedWriteRequest<SimklScrobbleResponseDto>(any()) } coAnswers {
            firstArg<suspend (String) -> Response<SimklScrobbleResponseDto>>().invoke("Bearer token")
        }
        coEvery { api.scrobblePause("Bearer token", body) } returns response

        provider.scrobblePause(body)
        val result = runtime.callSpecs.single().call.invoke()

        assertTrue(result is IntegrationCallResult.Success)
        assertSame(response, (result as IntegrationCallResult.Success).value)
        coVerify(exactly = 1) { authService.executeAuthorizedWriteRequest<SimklScrobbleResponseDto>(any()) }
        coVerify(exactly = 1) { api.scrobblePause("Bearer token", body) }
    }

    @Test
    fun `scrobblePause runtime call preserves non-success write response`() = runTest {
        val runtime = RecordingIntegrationRuntime<Response<SimklScrobbleResponseDto>>()
        val api = mockk<SimklApi>()
        val authService = mockk<SimklAuthService>()
        val response = Response.error<SimklScrobbleResponseDto>(503, "Service unavailable".toResponseBody())
        val body = SimklScrobbleRequestDto(progress = 45.0f)
        val provider = SimklIntegrationProvider(
            runtime = runtime,
            simklApi = api,
            simklAuthService = authService,
            moshi = Moshi.Builder().build(),
            transport = mockk<SimklDiscoveryTransport>(relaxed = true)
        )

        coEvery { authService.executeAuthorizedWriteRequest<SimklScrobbleResponseDto>(any()) } coAnswers {
            firstArg<suspend (String) -> Response<SimklScrobbleResponseDto>>().invoke("Bearer token")
        }
        coEvery { api.scrobblePause("Bearer token", body) } returns response

        provider.scrobblePause(body)
        val result = runtime.callSpecs.single().call.invoke()

        assertTrue(result is IntegrationCallResult.Success)
        assertSame(response, (result as IntegrationCallResult.Success).value)
        coVerify(exactly = 1) { authService.executeAuthorizedWriteRequest<SimklScrobbleResponseDto>(any()) }
        coVerify(exactly = 1) { api.scrobblePause("Bearer token", body) }
    }

    @Test
    fun `addHistory runtime call uses default authorized write request`() = runTest {
        val runtime = RecordingIntegrationRuntime<Response<Unit>>()
        val api = mockk<SimklApi>()
        val authService = mockk<SimklAuthService>()
        val response = Response.success(Unit)
        val body = SimklHistoryAddRequestDto()
        val provider = SimklIntegrationProvider(
            runtime = runtime,
            simklApi = api,
            simklAuthService = authService,
            moshi = Moshi.Builder().build(),
            transport = mockk<SimklDiscoveryTransport>(relaxed = true)
        )

        coEvery { authService.executeAuthorizedWriteRequest<Unit>(any()) } coAnswers {
            firstArg<suspend (String) -> Response<Unit>>().invoke("Bearer token")
        }
        coEvery { api.addHistory("Bearer token", body) } returns response

        provider.addHistory(body)
        val result = runtime.callSpecs.single().call.invoke()

        assertTrue(result is IntegrationCallResult.Success)
        assertSame(response, (result as IntegrationCallResult.Success).value)
        coVerify(exactly = 1) { authService.executeAuthorizedWriteRequest<Unit>(any()) }
        coVerify(exactly = 1) { api.addHistory("Bearer token", body) }
    }

    @Test
    fun `addHistory runtime call preserves non-success write response`() = runTest {
        val runtime = RecordingIntegrationRuntime<Response<Unit>>()
        val api = mockk<SimklApi>()
        val authService = mockk<SimklAuthService>()
        val response = Response.error<Unit>(503, "Service unavailable".toResponseBody())
        val body = SimklHistoryAddRequestDto()
        val provider = SimklIntegrationProvider(
            runtime = runtime,
            simklApi = api,
            simklAuthService = authService,
            moshi = Moshi.Builder().build(),
            transport = mockk<SimklDiscoveryTransport>(relaxed = true)
        )

        coEvery { authService.executeAuthorizedWriteRequest<Unit>(any()) } coAnswers {
            firstArg<suspend (String) -> Response<Unit>>().invoke("Bearer token")
        }
        coEvery { api.addHistory("Bearer token", body) } returns response

        provider.addHistory(body)
        val result = runtime.callSpecs.single().call.invoke()

        assertTrue(result is IntegrationCallResult.Success)
        assertSame(response, (result as IntegrationCallResult.Success).value)
        coVerify(exactly = 1) { authService.executeAuthorizedWriteRequest<Unit>(any()) }
        coVerify(exactly = 1) { api.addHistory("Bearer token", body) }
    }

    @Test
    fun `removeFromHistoryAndLists runtime call uses default authorized write request`() = runTest {
        val runtime = RecordingIntegrationRuntime<Response<Unit>>()
        val api = mockk<SimklApi>()
        val authService = mockk<SimklAuthService>()
        val response = Response.success(Unit)
        val body = SimklHistoryRemoveRequestDto()
        val provider = SimklIntegrationProvider(
            runtime = runtime,
            simklApi = api,
            simklAuthService = authService,
            moshi = Moshi.Builder().build(),
            transport = mockk<SimklDiscoveryTransport>(relaxed = true)
        )

        coEvery { authService.executeAuthorizedWriteRequest<Unit>(any()) } coAnswers {
            firstArg<suspend (String) -> Response<Unit>>().invoke("Bearer token")
        }
        coEvery { api.removeFromHistoryAndLists("Bearer token", body) } returns response

        provider.removeFromHistoryAndLists(body)
        val result = runtime.callSpecs.single().call.invoke()

        assertTrue(result is IntegrationCallResult.Success)
        assertSame(response, (result as IntegrationCallResult.Success).value)
        coVerify(exactly = 1) { authService.executeAuthorizedWriteRequest<Unit>(any()) }
        coVerify(exactly = 1) { api.removeFromHistoryAndLists("Bearer token", body) }
    }

    @Test
    fun `removeFromHistoryAndLists runtime call preserves non-success write response`() = runTest {
        val runtime = RecordingIntegrationRuntime<Response<Unit>>()
        val api = mockk<SimklApi>()
        val authService = mockk<SimklAuthService>()
        val response = Response.error<Unit>(503, "Service unavailable".toResponseBody())
        val body = SimklHistoryRemoveRequestDto()
        val provider = SimklIntegrationProvider(
            runtime = runtime,
            simklApi = api,
            simklAuthService = authService,
            moshi = Moshi.Builder().build(),
            transport = mockk<SimklDiscoveryTransport>(relaxed = true)
        )

        coEvery { authService.executeAuthorizedWriteRequest<Unit>(any()) } coAnswers {
            firstArg<suspend (String) -> Response<Unit>>().invoke("Bearer token")
        }
        coEvery { api.removeFromHistoryAndLists("Bearer token", body) } returns response

        provider.removeFromHistoryAndLists(body)
        val result = runtime.callSpecs.single().call.invoke()

        assertTrue(result is IntegrationCallResult.Success)
        assertSame(response, (result as IntegrationCallResult.Success).value)
        coVerify(exactly = 1) { authService.executeAuthorizedWriteRequest<Unit>(any()) }
        coVerify(exactly = 1) { api.removeFromHistoryAndLists("Bearer token", body) }
    }

    @Test
    fun `addToList runtime call uses session authorized write request when provided`() = runTest {
        val runtime = RecordingIntegrationRuntime<Response<SimklAddToListResponseDto>>()
        val api = mockk<SimklApi>()
        val authService = mockk<SimklAuthService>()
        val response = Response.success(SimklAddToListResponseDto(result = "ok"))
        val body = SimklAddToListRequestDto(to = "completed")
        val session = TrackingAuthSession(provider = TrackingProvider.SIMKL, profileId = 17)
        val provider = SimklIntegrationProvider(
            runtime = runtime,
            simklApi = api,
            simklAuthService = authService,
            moshi = Moshi.Builder().build(),
            transport = mockk<SimklDiscoveryTransport>(relaxed = true)
        )

        coEvery {
            authService.executeAuthorizedWriteRequest<SimklAddToListResponseDto>(session, any())
        } coAnswers {
            secondArg<suspend (String) -> Response<SimklAddToListResponseDto>>().invoke("Bearer token")
        }
        coEvery { api.addToList("Bearer token", body) } returns response

        provider.addToList(body, session)
        val result = runtime.callSpecs.single().call.invoke()

        assertTrue(result is IntegrationCallResult.Success)
        assertSame(response, (result as IntegrationCallResult.Success).value)
        coVerify(exactly = 1) {
            authService.executeAuthorizedWriteRequest<SimklAddToListResponseDto>(session, any())
        }
        coVerify(exactly = 1) { api.addToList("Bearer token", body) }
    }

    @Test
    fun `getLastActivities runtime call uses session authorized request when provided`() = runTest {
        val runtime = RecordingIntegrationRuntime<Response<SimklLastActivitiesResponseDto>>()
        val api = mockk<SimklApi>()
        val authService = mockk<SimklAuthService>()
        val response = Response.success(SimklLastActivitiesResponseDto())
        val session = TrackingAuthSession(provider = TrackingProvider.SIMKL, profileId = 42)
        val provider = SimklIntegrationProvider(
            runtime = runtime,
            simklApi = api,
            simklAuthService = authService,
            moshi = Moshi.Builder().build(),
            transport = mockk<SimklDiscoveryTransport>(relaxed = true)
        )

        coEvery { authService.executeAuthOwnerRequest<SimklLastActivitiesResponseDto>(session, any()) } coAnswers {
            secondArg<suspend (String) -> Response<SimklLastActivitiesResponseDto>>().invoke("Bearer token")
        }
        coEvery { api.getLastActivities("Bearer token") } returns response

        provider.getLastActivities(session)
        val result = runtime.callSpecs.single().call.invoke()

        assertTrue(result is IntegrationCallResult.Success)
        assertSame(response, (result as IntegrationCallResult.Success).value)
        coVerify(exactly = 1) { authService.executeAuthOwnerRequest<SimklLastActivitiesResponseDto>(session, any()) }
        coVerify(exactly = 1) { api.getLastActivities("Bearer token") }
    }

    @Test
    fun `getPlayback runtime call uses default authorized request`() = runTest {
        val runtime = RecordingIntegrationRuntime<Response<List<SimklPlaybackItemDto>>>()
        val api = mockk<SimklApi>()
        val authService = mockk<SimklAuthService>()
        val response = Response.success(listOf(SimklPlaybackItemDto(id = 5L)))
        val provider = SimklIntegrationProvider(
            runtime = runtime,
            simklApi = api,
            simklAuthService = authService,
            moshi = Moshi.Builder().build(),
            transport = mockk<SimklDiscoveryTransport>(relaxed = true)
        )

        coEvery { authService.executeAuthOwnerRequest<List<SimklPlaybackItemDto>>(any()) } coAnswers {
            firstArg<suspend (String) -> Response<List<SimklPlaybackItemDto>>>().invoke("Bearer token")
        }
        coEvery { api.getPlayback("Bearer token", "episodes") } returns response

        provider.getPlayback("episodes")
        val result = runtime.callSpecs.single().call.invoke()

        assertTrue(result is IntegrationCallResult.Success)
        assertSame(response, (result as IntegrationCallResult.Success).value)
        coVerify(exactly = 1) { authService.executeAuthOwnerRequest<List<SimklPlaybackItemDto>>(any()) }
        coVerify(exactly = 1) { api.getPlayback("Bearer token", "episodes") }
    }

    @Test
    fun `deletePlayback runtime call uses default authorized request`() = runTest {
        val runtime = RecordingIntegrationRuntime<Response<Unit>>()
        val api = mockk<SimklApi>()
        val authService = mockk<SimklAuthService>()
        val response = Response.success(Unit)
        val provider = SimklIntegrationProvider(
            runtime = runtime,
            simklApi = api,
            simklAuthService = authService,
            moshi = Moshi.Builder().build(),
            transport = mockk<SimklDiscoveryTransport>(relaxed = true)
        )

        coEvery { authService.executeAuthOwnerRequest<Unit>(any()) } coAnswers {
            firstArg<suspend (String) -> Response<Unit>>().invoke("Bearer token")
        }
        coEvery { api.deletePlayback("Bearer token", 13L) } returns response

        provider.deletePlayback(13L)
        val result = runtime.callSpecs.single().call.invoke()

        assertTrue(result is IntegrationCallResult.Success)
        assertSame(response, (result as IntegrationCallResult.Success).value)
        coVerify(exactly = 1) { authService.executeAuthOwnerRequest<Unit>(any()) }
        coVerify(exactly = 1) { api.deletePlayback("Bearer token", 13L) }
    }
}
