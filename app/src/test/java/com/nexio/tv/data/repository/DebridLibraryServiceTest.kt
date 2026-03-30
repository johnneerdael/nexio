package com.nexio.tv.data.repository

import com.nexio.tv.data.local.RealDebridAuthDataStore
import com.nexio.tv.data.local.RealDebridAuthState
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
        val premiumizeApi = mockk<PremiumizeApi>()
        val premiumizeService = mockk<PremiumizeService>()

        stubAuthenticatedRealDebrid(realDebridAuthDataStore)
        every { premiumizeService.observeAccountState() } returns flowOf(PremiumizeAccountState())
        coJustRun { premiumizeService.refreshAccountState() }

        coEvery { realDebridApi.getTorrents(any(), any(), any()) } returns Response.success(
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
        coEvery { realDebridApi.getDownloads(any(), any(), any()) } returns Response.success(
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
        val realDebridAuthService = RealDebridAuthService(realDebridApi, realDebridAuthDataStore)

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
        assertEquals("Bearer rd-access-token", items.single().playbackHeaders?.get("Authorization"))
        assertTrue(items.single().listKeys.contains(DebridLibraryService.REAL_DEBRID_LIST_KEY))
    }

    @Test
    fun `refresh real debrid prefers playable download entries over non video links`() = runTest {
        val realDebridApi = mockk<RealDebridApi>()
        val realDebridAuthDataStore = mockk<RealDebridAuthDataStore>()
        val premiumizeApi = mockk<PremiumizeApi>()
        val premiumizeService = mockk<PremiumizeService>()

        stubAuthenticatedRealDebrid(realDebridAuthDataStore)
        every { premiumizeService.observeAccountState() } returns flowOf(PremiumizeAccountState())
        coJustRun { premiumizeService.refreshAccountState() }

        coEvery { realDebridApi.getTorrents(any(), any(), any()) } returns Response.success(
            listOf(
                RealDebridTorrentDto(
                    id = "multi-link",
                    filename = "Playable.Movie.2024",
                    status = "downloaded",
                    links = listOf(
                        "https://rd.test/link/readme",
                        "https://rd.test/link/video"
                    ),
                    ended = "2026-03-30T12:00:00Z"
                )
            )
        )
        coEvery { realDebridApi.getDownloads(any(), any(), any()) } returns Response.success(
            listOf(
                RealDebridDownloadDto(
                    id = "download-readme",
                    filename = "README.txt",
                    mimeType = "text/plain",
                    link = "https://rd.test/link/readme",
                    download = "https://rd.test/download/readme.txt"
                ),
                RealDebridDownloadDto(
                    id = "download-video",
                    filename = "Playable.Movie.2024.mkv",
                    mimeType = "video/x-matroska",
                    link = "https://rd.test/link/video",
                    download = "https://rd.test/download/video.mkv"
                )
            )
        )
        val realDebridAuthService = RealDebridAuthService(realDebridApi, realDebridAuthDataStore)

        val service = DebridLibraryService(
            realDebridApi = realDebridApi,
            realDebridAuthDataStore = realDebridAuthDataStore,
            realDebridAuthService = realDebridAuthService,
            premiumizeApi = premiumizeApi,
            premiumizeService = premiumizeService
        )

        service.refreshNow(DebridLibraryService.RefreshTarget.REAL_DEBRID)

        val item = service.observeItems().first().single()

        assertEquals("https://rd.test/download/video.mkv", item.directPlaybackUrl)
        assertEquals("Playable.Movie.2024.mkv", item.playbackFilename)
        assertEquals("Playable.Movie.2024", item.name)
    }

    @Test
    fun `refresh real debrid keeps torrents whose resolved download is playable even when torrent filename is generic`() = runTest {
        val realDebridApi = mockk<RealDebridApi>()
        val realDebridAuthDataStore = mockk<RealDebridAuthDataStore>()
        val premiumizeApi = mockk<PremiumizeApi>()
        val premiumizeService = mockk<PremiumizeService>()

        stubAuthenticatedRealDebrid(realDebridAuthDataStore)
        every { premiumizeService.observeAccountState() } returns flowOf(PremiumizeAccountState())
        coJustRun { premiumizeService.refreshAccountState() }

        coEvery { realDebridApi.getTorrents(any(), any(), any()) } returns Response.success(
            listOf(
                RealDebridTorrentDto(
                    id = "generic-name",
                    filename = "Some torrent job",
                    status = "downloaded",
                    links = listOf("https://rd.test/link/video"),
                    ended = "2026-03-30T12:00:00Z"
                )
            )
        )
        coEvery { realDebridApi.getDownloads(any(), any(), any()) } returns Response.success(
            listOf(
                RealDebridDownloadDto(
                    id = "download-video",
                    filename = "Playable.Movie.2024.mkv",
                    mimeType = "video/x-matroska",
                    link = "https://rd.test/link/video",
                    download = "https://rd.test/download/video.mkv"
                )
            )
        )
        val realDebridAuthService = RealDebridAuthService(realDebridApi, realDebridAuthDataStore)

        val service = DebridLibraryService(
            realDebridApi = realDebridApi,
            realDebridAuthDataStore = realDebridAuthDataStore,
            realDebridAuthService = realDebridAuthService,
            premiumizeApi = premiumizeApi,
            premiumizeService = premiumizeService
        )

        service.refreshNow(DebridLibraryService.RefreshTarget.REAL_DEBRID)

        val item = service.observeItems().first().single()

        assertEquals("https://rd.test/download/video.mkv", item.directPlaybackUrl)
        assertEquals("Playable.Movie.2024", item.name)
    }

    private fun stubAuthenticatedRealDebrid(realDebridAuthDataStore: RealDebridAuthDataStore) {
        val now = System.currentTimeMillis()
        every { realDebridAuthDataStore.isAuthenticated } returns flowOf(true)
        every { realDebridAuthDataStore.state } returns flowOf(
            RealDebridAuthState(
                accessToken = "rd-access-token",
                refreshToken = "refresh-token",
                userClientId = "client-id",
                userClientSecret = "client-secret",
                createdAt = now,
                expiresIn = 3600
            )
        )
    }
}
