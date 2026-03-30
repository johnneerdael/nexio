package com.nexio.tv.data.repository

import com.nexio.tv.data.local.RealDebridAuthDataStore
import com.nexio.tv.data.remote.api.PremiumizeApi
import com.nexio.tv.data.remote.api.RealDebridApi
import com.nexio.tv.data.remote.dto.debrid.RealDebridDownloadDto
import com.nexio.tv.data.remote.dto.debrid.RealDebridTorrentDto
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

class DebridLibraryServiceTest {

    @Test
    fun `refresh real debrid exposes resolved download urls and filters unresolved torrents`() = runTest {
        val realDebridApi = mockk<RealDebridApi>()
        val realDebridAuthDataStore = mockk<RealDebridAuthDataStore>()
        val realDebridAuthService = mockk<RealDebridAuthService>()
        val premiumizeApi = mockk<PremiumizeApi>()
        val premiumizeService = mockk<PremiumizeService>()

        every { realDebridAuthDataStore.isAuthenticated } returns flowOf(true)
        every { premiumizeService.observeAccountState() } returns flowOf(PremiumizeAccountState())
        coJustRun { premiumizeService.refreshAccountState() }

        coEvery { realDebridAuthService.executeAuthorizedRequest<List<RealDebridTorrentDto>>(any()) } returns Response.success(
            listOf(
                RealDebridTorrentDto(
                    id = "resolved",
                    filename = "Resolved.Movie.2024.mkv",
                    status = "downloaded",
                    links = listOf("https://rd.test/link/resolved"),
                    ended = "2026-03-30T12:00:00Z"
                ),
                RealDebridTorrentDto(
                    id = "unresolved",
                    filename = "Unresolved.Movie.2024.mkv",
                    status = "downloaded",
                    links = listOf("https://rd.test/link/unresolved"),
                    ended = "2026-03-30T11:00:00Z"
                )
            )
        )
        coEvery { realDebridAuthService.executeAuthorizedRequest<List<RealDebridDownloadDto>>(any()) } returns Response.success(
            listOf(
                RealDebridDownloadDto(
                    id = "download-1",
                    filename = "Resolved.Movie.2024.mkv",
                    link = "https://rd.test/link/resolved",
                    download = "https://rd.test/download/resolved.mkv",
                    mimeType = "video/x-matroska"
                )
            )
        )

        val service = DebridLibraryService(
            realDebridApi = realDebridApi,
            realDebridAuthDataStore = realDebridAuthDataStore,
            realDebridAuthService = realDebridAuthService,
            premiumizeApi = premiumizeApi,
            premiumizeService = premiumizeService
        )

        service.refreshNow(DebridLibraryService.RefreshTarget.REAL_DEBRID)

        val tabs = service.observeListTabs().first()
        val items = service.observeItems().first()

        assertEquals(listOf(DebridLibraryService.REAL_DEBRID_LIST_KEY), tabs.map { it.key })
        assertEquals(1, items.size)
        assertEquals("https://rd.test/download/resolved.mkv", items.single().directPlaybackUrl)
        assertTrue(items.single().listKeys.contains(DebridLibraryService.REAL_DEBRID_LIST_KEY))
    }
}
