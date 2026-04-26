package com.nexio.tv.data.repository

import com.nexio.tv.data.integration.simkl.SimklIntegrationProvider
import com.nexio.tv.data.remote.dto.simkl.SimklAddToListRequestDto
import com.nexio.tv.data.remote.dto.simkl.SimklAddToListResponseDto
import com.nexio.tv.data.remote.dto.simkl.SimklHistoryAddRequestDto
import com.nexio.tv.data.remote.dto.simkl.SimklHistoryRemoveRequestDto
import com.nexio.tv.data.remote.dto.simkl.SimklLibraryItemDto
import com.nexio.tv.data.remote.dto.simkl.SimklLastActivitiesResponseDto
import com.nexio.tv.data.remote.dto.simkl.SimklPlaybackItemDto
import com.nexio.tv.data.remote.dto.simkl.SimklScrobbleRequestDto
import com.nexio.tv.data.remote.dto.simkl.SimklScrobbleResponseDto
import com.nexio.tv.domain.model.TrackingProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertSame
import org.junit.Test
import retrofit2.Response

class SimklTrackingRemoteDataSourceTest {
    @Test
    fun `getAllItems delegates to simkl integration provider`() = runTest {
        val provider = mockk<SimklIntegrationProvider>()
        val response = Response.success(listOf(SimklLibraryItemDto()))
        val remote = remote(provider)

        coEvery { provider.getAllItems(null, "full", null) } returns response

        val result = remote.getAllItems(extended = "full")

        assertSame(response, result)
        coVerify(exactly = 1) { provider.getAllItems(null, "full", null) }
    }

    @Test
    fun `getAllItemsByType delegates to simkl integration provider`() = runTest {
        val provider = mockk<SimklIntegrationProvider>()
        val response = Response.success(listOf(SimklLibraryItemDto()))
        val remote = remote(provider)

        coEvery { provider.getAllItemsByType("shows", null, "full", null) } returns response

        val result = remote.getAllItemsByType(type = "shows", extended = "full")

        assertSame(response, result)
        coVerify(exactly = 1) { provider.getAllItemsByType("shows", null, "full", null) }
    }

    @Test
    fun `getAllItemsByStatus delegates to simkl integration provider`() = runTest {
        val provider = mockk<SimklIntegrationProvider>()
        val response = Response.success(listOf(SimklLibraryItemDto()))
        val remote = remote(provider)

        coEvery {
            provider.getAllItemsByStatus("shows", "watching", null, "full", "watched_at", null)
        } returns response

        val result = remote.getAllItemsByStatus(
            type = "shows",
            status = "watching",
            extended = "full",
            episodeWatchedAt = "watched_at"
        )

        assertSame(response, result)
        coVerify(exactly = 1) {
            provider.getAllItemsByStatus("shows", "watching", null, "full", "watched_at", null)
        }
    }

    @Test
    fun `getLastActivities delegates to simkl integration provider`() = runTest {
        val provider = mockk<SimklIntegrationProvider>()
        val response = Response.success(SimklLastActivitiesResponseDto())
        val remote = remote(provider)

        coEvery { provider.getLastActivities(null) } returns response

        val result = remote.getLastActivities()

        assertSame(response, result)
        coVerify(exactly = 1) { provider.getLastActivities(null) }
    }

    @Test
    fun `getPlayback delegates to simkl integration provider`() = runTest {
        val provider = mockk<SimklIntegrationProvider>()
        val response = Response.success(listOf(SimklPlaybackItemDto(id = 7L)))
        val remote = remote(provider)

        coEvery { provider.getPlayback("episodes", null) } returns response

        val result = remote.getPlayback(type = "episodes")

        assertSame(response, result)
        coVerify(exactly = 1) { provider.getPlayback("episodes", null) }
    }

    @Test
    fun `deletePlayback delegates to simkl integration provider`() = runTest {
        val provider = mockk<SimklIntegrationProvider>()
        val response = Response.success(Unit)
        val remote = remote(provider)

        coEvery { provider.deletePlayback(11L, null) } returns response

        val result = remote.deletePlayback(playbackId = 11L)

        assertSame(response, result)
        coVerify(exactly = 1) { provider.deletePlayback(11L, null) }
    }

    @Test
    fun `addToList delegates to simkl integration provider`() = runTest {
        val provider = mockk<SimklIntegrationProvider>()
        val body = SimklAddToListRequestDto(to = "completed")
        val session = TrackingAuthSession(provider = TrackingProvider.SIMKL, profileId = 7)
        val response = Response.success(SimklAddToListResponseDto(result = "ok"))
        val remote = remote(provider)

        coEvery { provider.addToList(body, session) } returns response

        val result = remote.addToList(body = body, session = session)

        assertSame(response, result)
        coVerify(exactly = 1) { provider.addToList(body, session) }
    }

    @Test
    fun `addHistory delegates to simkl integration provider`() = runTest {
        val provider = mockk<SimklIntegrationProvider>()
        val body = SimklHistoryAddRequestDto()
        val session = TrackingAuthSession(provider = TrackingProvider.SIMKL, profileId = 8)
        val response = Response.success(Unit)
        val remote = remote(provider)

        coEvery { provider.addHistory(body, session) } returns response

        val result = remote.addHistory(body = body, session = session)

        assertSame(response, result)
        coVerify(exactly = 1) { provider.addHistory(body, session) }
    }

    @Test
    fun `removeFromHistoryAndLists delegates to simkl integration provider`() = runTest {
        val provider = mockk<SimklIntegrationProvider>()
        val body = SimklHistoryRemoveRequestDto()
        val session = TrackingAuthSession(provider = TrackingProvider.SIMKL, profileId = 9)
        val response = Response.success(Unit)
        val remote = remote(provider)

        coEvery { provider.removeFromHistoryAndLists(body, session) } returns response

        val result = remote.removeFromHistoryAndLists(body = body, session = session)

        assertSame(response, result)
        coVerify(exactly = 1) { provider.removeFromHistoryAndLists(body, session) }
    }

    @Test
    fun `scrobbleStart delegates to simkl integration provider`() = runTest {
        val provider = mockk<SimklIntegrationProvider>()
        val body = SimklScrobbleRequestDto(progress = 12.5f)
        val session = TrackingAuthSession(provider = TrackingProvider.SIMKL, profileId = 10)
        val response = Response.success(SimklScrobbleResponseDto(action = "start"))
        val remote = remote(provider)

        coEvery { provider.scrobbleStart(body, session) } returns response

        val result = remote.scrobbleStart(body = body, session = session)

        assertSame(response, result)
        coVerify(exactly = 1) { provider.scrobbleStart(body, session) }
    }

    @Test
    fun `scrobblePause delegates to simkl integration provider`() = runTest {
        val provider = mockk<SimklIntegrationProvider>()
        val body = SimklScrobbleRequestDto(progress = 18.0f)
        val session = TrackingAuthSession(provider = TrackingProvider.SIMKL, profileId = 11)
        val response = Response.success(SimklScrobbleResponseDto(action = "pause"))
        val remote = remote(provider)

        coEvery { provider.scrobblePause(body, session) } returns response

        val result = remote.scrobblePause(body = body, session = session)

        assertSame(response, result)
        coVerify(exactly = 1) { provider.scrobblePause(body, session) }
    }

    @Test
    fun `scrobbleStop delegates to simkl integration provider`() = runTest {
        val provider = mockk<SimklIntegrationProvider>()
        val body = SimklScrobbleRequestDto(progress = 24.0f)
        val session = TrackingAuthSession(provider = TrackingProvider.SIMKL, profileId = 12)
        val response = Response.success(SimklScrobbleResponseDto(action = "stop"))
        val remote = remote(provider)

        coEvery { provider.scrobbleStop(body, session) } returns response

        val result = remote.scrobbleStop(body = body, session = session)

        assertSame(response, result)
        coVerify(exactly = 1) { provider.scrobbleStop(body, session) }
    }

    @Test
    fun `checkin delegates to simkl integration provider`() = runTest {
        val provider = mockk<SimklIntegrationProvider>()
        val body = SimklScrobbleRequestDto(progress = 33.0f)
        val session = TrackingAuthSession(provider = TrackingProvider.SIMKL, profileId = 13)
        val response = Response.success(SimklScrobbleResponseDto(action = "checkin"))
        val remote = remote(provider)

        coEvery { provider.checkin(body, session) } returns response

        val result = remote.checkin(body = body, session = session)

        assertSame(response, result)
        coVerify(exactly = 1) { provider.checkin(body, session) }
    }

    private fun remote(provider: SimklIntegrationProvider): SimklTrackingRemoteDataSource =
        SimklTrackingRemoteDataSource(simklIntegrationProvider = provider)

}
