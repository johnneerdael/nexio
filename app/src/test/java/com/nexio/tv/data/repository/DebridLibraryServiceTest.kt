package com.nexio.tv.data.repository

import com.nexio.tv.data.local.RealDebridAuthDataStore
import com.nexio.tv.data.local.RealDebridAuthState
import com.nexio.tv.data.remote.api.PremiumizeApi
import com.nexio.tv.data.remote.api.RealDebridApi
import com.nexio.tv.data.remote.api.TorBoxApi
import com.nexio.tv.data.remote.dto.debrid.PremiumizeItemDetailsDto
import com.nexio.tv.data.remote.dto.debrid.PremiumizeListAllDto
import com.nexio.tv.data.remote.dto.debrid.PremiumizeListAllFileDto
import com.nexio.tv.data.remote.dto.debrid.RealDebridDownloadDto
import com.nexio.tv.data.remote.dto.debrid.RealDebridTorrentFileDto
import com.nexio.tv.data.remote.dto.debrid.RealDebridTorrentInfoDto
import com.nexio.tv.data.remote.dto.debrid.RealDebridTorrentDto
import com.nexio.tv.data.remote.dto.debrid.RealDebridUnrestrictLinkDto
import com.nexio.tv.data.remote.dto.debrid.TorBoxEnvelopeDto
import com.nexio.tv.data.remote.dto.debrid.TorBoxFileDto
import com.nexio.tv.data.remote.dto.debrid.TorBoxTorrentListItemDto
import com.nexio.tv.data.repository.benchmark.DebridBenchmarkProvider
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.coVerify
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
        val torBoxApi = mockk<TorBoxApi>()
        val torBoxService = mockk<TorBoxService>()

        stubAuthenticatedRealDebrid(realDebridAuthDataStore)
        every { premiumizeService.observeAccountState() } returns flowOf(PremiumizeAccountState())
        coJustRun { premiumizeService.refreshAccountState() }
        stubDisconnectedTorBox(torBoxService)

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
        coEvery { realDebridApi.getTorrentInfo(any(), "resolved") } returns Response.success(
            RealDebridTorrentInfoDto(
                id = "resolved",
                filename = "Resolved.Movie.2024.mkv",
                status = "downloaded",
                links = listOf("https://rd.test/link/resolved"),
                files = listOf(
                    RealDebridTorrentFileDto(
                        id = 10,
                        path = "/Resolved.Movie.2024.mkv",
                        bytes = 1_000L,
                        selected = 1
                    )
                ),
                ended = "2026-03-30T12:00:00Z"
            )
        )
        coEvery { realDebridApi.getTorrentInfo(any(), "unresolved") } returns Response.success(
            RealDebridTorrentInfoDto(
                id = "unresolved",
                filename = "Unresolved.Movie.2024.mkv",
                status = "downloaded",
                links = listOf("https://rd.test/link/unresolved"),
                files = listOf(
                    RealDebridTorrentFileDto(
                        id = 11,
                        path = "/Unresolved.Movie.2024.mkv",
                        bytes = 1_000L,
                        selected = 1
                    )
                ),
                ended = "2026-03-30T11:00:00Z"
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
        coEvery { realDebridApi.unrestrictLink(any(), any(), any()) } returns Response.error(503, mockk(relaxed = true))
        val realDebridAuthService = RealDebridAuthService(realDebridApi, realDebridAuthDataStore)

        val service = DebridLibraryService(
            realDebridApi = realDebridApi,
            realDebridAuthDataStore = realDebridAuthDataStore,
            realDebridAuthService = realDebridAuthService,
            premiumizeApi = premiumizeApi,
            premiumizeService = premiumizeService,
            torBoxApi = torBoxApi,
            torBoxService = torBoxService
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
        val torBoxApi = mockk<TorBoxApi>()
        val torBoxService = mockk<TorBoxService>()

        stubAuthenticatedRealDebrid(realDebridAuthDataStore)
        every { premiumizeService.observeAccountState() } returns flowOf(PremiumizeAccountState())
        coJustRun { premiumizeService.refreshAccountState() }
        stubDisconnectedTorBox(torBoxService)

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
        coEvery { realDebridApi.getTorrentInfo(any(), "multi-link") } returns Response.success(
            RealDebridTorrentInfoDto(
                id = "multi-link",
                filename = "Playable.Movie.2024",
                status = "downloaded",
                links = listOf(
                    "https://rd.test/link/readme",
                    "https://rd.test/link/video"
                ),
                files = listOf(
                    RealDebridTorrentFileDto(
                        id = 20,
                        path = "/README.txt",
                        bytes = 100L,
                        selected = 1
                    ),
                    RealDebridTorrentFileDto(
                        id = 21,
                        path = "/Playable.Movie.2024.mkv",
                        bytes = 2_000L,
                        selected = 1
                    )
                ),
                ended = "2026-03-30T12:00:00Z"
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
        coEvery { realDebridApi.unrestrictLink(any(), any(), any()) } returns Response.error(503, mockk(relaxed = true))
        val realDebridAuthService = RealDebridAuthService(realDebridApi, realDebridAuthDataStore)

        val service = DebridLibraryService(
            realDebridApi = realDebridApi,
            realDebridAuthDataStore = realDebridAuthDataStore,
            realDebridAuthService = realDebridAuthService,
            premiumizeApi = premiumizeApi,
            premiumizeService = premiumizeService,
            torBoxApi = torBoxApi,
            torBoxService = torBoxService
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
        val torBoxApi = mockk<TorBoxApi>()
        val torBoxService = mockk<TorBoxService>()

        stubAuthenticatedRealDebrid(realDebridAuthDataStore)
        every { premiumizeService.observeAccountState() } returns flowOf(PremiumizeAccountState())
        coJustRun { premiumizeService.refreshAccountState() }
        stubDisconnectedTorBox(torBoxService)

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
        coEvery { realDebridApi.getTorrentInfo(any(), "generic-name") } returns Response.success(
            RealDebridTorrentInfoDto(
                id = "generic-name",
                filename = "Some torrent job",
                status = "downloaded",
                links = listOf("https://rd.test/link/video"),
                files = listOf(
                    RealDebridTorrentFileDto(
                        id = 30,
                        path = "/Playable.Movie.2024.mkv",
                        bytes = 2_000L,
                        selected = 1
                    )
                ),
                ended = "2026-03-30T12:00:00Z"
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
        coEvery { realDebridApi.unrestrictLink(any(), any(), any()) } returns Response.error(503, mockk(relaxed = true))
        val realDebridAuthService = RealDebridAuthService(realDebridApi, realDebridAuthDataStore)

        val service = DebridLibraryService(
            realDebridApi = realDebridApi,
            realDebridAuthDataStore = realDebridAuthDataStore,
            realDebridAuthService = realDebridAuthService,
            premiumizeApi = premiumizeApi,
            premiumizeService = premiumizeService,
            torBoxApi = torBoxApi,
            torBoxService = torBoxService
        )

        service.refreshNow(DebridLibraryService.RefreshTarget.REAL_DEBRID)

        val item = service.observeItems().first().single()

        assertEquals("https://rd.test/download/video.mkv", item.directPlaybackUrl)
        assertEquals("Playable.Movie.2024", item.name)
    }

    @Test
    fun `refresh real debrid unrestricts selected file links when downloads list has no match`() = runTest {
        val realDebridApi = mockk<RealDebridApi>()
        val realDebridAuthDataStore = mockk<RealDebridAuthDataStore>()
        val premiumizeApi = mockk<PremiumizeApi>()
        val premiumizeService = mockk<PremiumizeService>()
        val torBoxApi = mockk<TorBoxApi>()
        val torBoxService = mockk<TorBoxService>()

        stubAuthenticatedRealDebrid(realDebridAuthDataStore)
        every { premiumizeService.observeAccountState() } returns flowOf(PremiumizeAccountState())
        coJustRun { premiumizeService.refreshAccountState() }
        stubDisconnectedTorBox(torBoxService)

        coEvery { realDebridApi.getTorrents(any(), any(), any()) } returns Response.success(
            listOf(
                RealDebridTorrentDto(
                    id = "no-download-match",
                    filename = "Generic release name",
                    status = "downloaded",
                    links = listOf("https://real-debrid.com/d/generated-link"),
                    ended = "2026-03-30T12:00:00Z"
                )
            )
        )
        coEvery { realDebridApi.getTorrentInfo(any(), "no-download-match") } returns Response.success(
            RealDebridTorrentInfoDto(
                id = "no-download-match",
                filename = "Generic release name",
                status = "downloaded",
                links = listOf("https://real-debrid.com/d/generated-link"),
                files = listOf(
                    RealDebridTorrentFileDto(
                        id = 40,
                        path = "/Actual.Movie.2026.2160p.mkv",
                        bytes = 4_000L,
                        selected = 1
                    )
                ),
                ended = "2026-03-30T12:00:00Z"
            )
        )
        coEvery { realDebridApi.getDownloads(any(), any(), any()) } returns Response.success(emptyList())
        coEvery { realDebridApi.unrestrictLink(any(), "https://real-debrid.com/d/generated-link", any()) } returns Response.success(
            RealDebridUnrestrictLinkDto(
                id = "unrestricted-1",
                filename = "Actual.Movie.2026.2160p.mkv",
                mimeType = "video/x-matroska",
                fileSize = 4_000L,
                link = "https://real-debrid.com/d/generated-link",
                download = "https://rd.test/download/actual.mkv",
                host = "real-debrid.com",
                chunks = 32,
                streamable = 1
            )
        )
        val realDebridAuthService = RealDebridAuthService(realDebridApi, realDebridAuthDataStore)

        val service = DebridLibraryService(
            realDebridApi = realDebridApi,
            realDebridAuthDataStore = realDebridAuthDataStore,
            realDebridAuthService = realDebridAuthService,
            premiumizeApi = premiumizeApi,
            premiumizeService = premiumizeService,
            torBoxApi = torBoxApi,
            torBoxService = torBoxService
        )

        service.refreshNow(DebridLibraryService.RefreshTarget.REAL_DEBRID)

        val item = service.observeItems().first().single()

        assertEquals("https://rd.test/download/actual.mkv", item.directPlaybackUrl)
        assertEquals("Actual.Movie.2026.2160p", item.name)
    }

    @Test
    fun `refresh real debrid excludes selected sample files from multi file torrents`() = runTest {
        val realDebridApi = mockk<RealDebridApi>()
        val realDebridAuthDataStore = mockk<RealDebridAuthDataStore>()
        val premiumizeApi = mockk<PremiumizeApi>()
        val premiumizeService = mockk<PremiumizeService>()
        val torBoxApi = mockk<TorBoxApi>()
        val torBoxService = mockk<TorBoxService>()

        stubAuthenticatedRealDebrid(realDebridAuthDataStore)
        every { premiumizeService.observeAccountState() } returns flowOf(PremiumizeAccountState())
        coJustRun { premiumizeService.refreshAccountState() }
        stubDisconnectedTorBox(torBoxService)

        coEvery { realDebridApi.getTorrents(any(), any(), any()) } returns Response.success(
            listOf(
                RealDebridTorrentDto(
                    id = "samples",
                    filename = "Hoppers",
                    status = "downloaded",
                    links = listOf(
                        "https://real-debrid.com/d/main",
                        "https://real-debrid.com/d/sample1",
                        "https://real-debrid.com/d/sample2"
                    ),
                    ended = "2026-03-30T12:00:00Z"
                )
            )
        )
        coEvery { realDebridApi.getTorrentInfo(any(), "samples") } returns Response.success(
            RealDebridTorrentInfoDto(
                id = "samples",
                filename = "Hoppers",
                status = "downloaded",
                links = listOf(
                    "https://real-debrid.com/d/main",
                    "https://real-debrid.com/d/sample1",
                    "https://real-debrid.com/d/sample2"
                ),
                files = listOf(
                    RealDebridTorrentFileDto(
                        id = 50,
                        path = "/Hoppers.2026.1080p.TELESYNC.x264-SyncUP.mkv",
                        bytes = 5_000L,
                        selected = 1
                    ),
                    RealDebridTorrentFileDto(
                        id = 51,
                        path = "/Samples/Sample1.mkv",
                        bytes = 100L,
                        selected = 1
                    ),
                    RealDebridTorrentFileDto(
                        id = 52,
                        path = "/Samples/Sample2.mkv",
                        bytes = 100L,
                        selected = 1
                    )
                ),
                ended = "2026-03-30T12:00:00Z"
            )
        )
        coEvery { realDebridApi.getDownloads(any(), any(), any()) } returns Response.success(emptyList())
        coEvery { realDebridApi.unrestrictLink(any(), any(), any()) } returns Response.error(503, mockk(relaxed = true))
        coEvery { realDebridApi.unrestrictLink(any(), "https://real-debrid.com/d/main", any()) } returns Response.success(
            RealDebridUnrestrictLinkDto(
                id = "unrestricted-main",
                filename = "Hoppers.2026.1080p.TELESYNC.x264-SyncUP.mkv",
                mimeType = "video/x-matroska",
                fileSize = 5_000L,
                link = "https://real-debrid.com/d/main",
                download = "https://rd.test/download/hoppers.mkv",
                host = "real-debrid.com",
                chunks = 32,
                streamable = 1
            )
        )
        val realDebridAuthService = RealDebridAuthService(realDebridApi, realDebridAuthDataStore)

        val service = DebridLibraryService(
            realDebridApi = realDebridApi,
            realDebridAuthDataStore = realDebridAuthDataStore,
            realDebridAuthService = realDebridAuthService,
            premiumizeApi = premiumizeApi,
            premiumizeService = premiumizeService,
            torBoxApi = torBoxApi,
            torBoxService = torBoxService
        )

        service.refreshNow(DebridLibraryService.RefreshTarget.REAL_DEBRID)

        val items = service.observeItems().first()

        assertEquals(1, items.size)
        assertEquals("rd:torrent:samples:file:50", items.single().id)
        assertEquals("Hoppers.2026.1080p.TELESYNC.x264-SyncUP", items.single().name)
    }

    @Test
    fun `get benchmark candidates uses provider-specific freshness and excludes items without direct playback urls`() = runTest {
        val realDebridApi = mockk<RealDebridApi>()
        val realDebridAuthDataStore = mockk<RealDebridAuthDataStore>()
        val premiumizeApi = mockk<PremiumizeApi>()
        val premiumizeService = mockk<PremiumizeService>()
        val torBoxApi = mockk<TorBoxApi>()
        val torBoxService = mockk<TorBoxService>()

        stubAuthenticatedRealDebrid(realDebridAuthDataStore)
        every { premiumizeService.observeAccountState() } returns flowOf(
            PremiumizeAccountState(
                apiKey = "pm-key",
                isConnected = true
            )
        )
        coJustRun { premiumizeService.refreshAccountState() }
        stubDisconnectedTorBox(torBoxService)

        coEvery { realDebridApi.getTorrents(any(), any(), any()) } returns Response.success(emptyList())
        coEvery { realDebridApi.getDownloads(any(), any(), any()) } returns Response.success(emptyList())

        coEvery { premiumizeApi.listAllItems("pm-key") } returns Response.success(
            PremiumizeListAllDto(
                status = "success",
                files = listOf(
                    PremiumizeListAllFileDto(
                        id = "old",
                        name = "Old.Movie.2024.mkv",
                        createdAt = 100L,
                        mimeType = "video/x-matroska",
                        path = "/Old.Movie.2024.mkv"
                    ),
                    PremiumizeListAllFileDto(
                        id = "skip",
                        name = "Skip.Movie.2024.mkv",
                        createdAt = 200L,
                        mimeType = "video/x-matroska",
                        path = "/Skip.Movie.2024.mkv"
                    ),
                    PremiumizeListAllFileDto(
                        id = "new",
                        name = "New.Movie.2024.mkv",
                        createdAt = 300L,
                        mimeType = "video/x-matroska",
                        path = "/New.Movie.2024.mkv"
                    )
                )
            )
        )
        coEvery { premiumizeApi.getItemDetails("pm-key", "old") } returns Response.success(
            PremiumizeItemDetailsDto(
                id = "old",
                name = "Old.Movie.2024.mkv",
                streamLink = "https://pm.test/direct/old",
                mimeType = "video/x-matroska",
                createdAt = 100L
            )
        )
        coEvery { premiumizeApi.getItemDetails("pm-key", "skip") } returns Response.success(
            PremiumizeItemDetailsDto(
                id = "skip",
                name = "Skip.Movie.2024.mkv",
                mimeType = "video/x-matroska",
                createdAt = 200L
            )
        )
        coEvery { premiumizeApi.getItemDetails("pm-key", "new") } returns Response.success(
            PremiumizeItemDetailsDto(
                id = "new",
                name = "New.Movie.2024.mkv",
                streamLink = "https://pm.test/direct/new",
                mimeType = "video/x-matroska",
                createdAt = 300L
            )
        )

        val realDebridAuthService = RealDebridAuthService(realDebridApi, realDebridAuthDataStore)
        val service = DebridLibraryService(
            realDebridApi = realDebridApi,
            realDebridAuthDataStore = realDebridAuthDataStore,
            realDebridAuthService = realDebridAuthService,
            premiumizeApi = premiumizeApi,
            premiumizeService = premiumizeService,
            torBoxApi = torBoxApi,
            torBoxService = torBoxService
        )

        service.refreshNow(DebridLibraryService.RefreshTarget.REAL_DEBRID)

        val candidates = service.getBenchmarkCandidates(DebridBenchmarkProvider.PREMIUMIZE)

        assertEquals(2, candidates.size)
        assertEquals(listOf("https://pm.test/direct/new", "https://pm.test/direct/old"), candidates.map { it.directUrl })
    }

    @Test
    fun `benchmark lookup skips premiumize library calls when refresh reports disconnected`() = runTest {
        val realDebridApi = mockk<RealDebridApi>()
        val realDebridAuthDataStore = mockk<RealDebridAuthDataStore>()
        val premiumizeApi = mockk<PremiumizeApi>()
        val premiumizeService = mockk<PremiumizeService>()
        val torBoxApi = mockk<TorBoxApi>()
        val torBoxService = mockk<TorBoxService>()

        stubAuthenticatedRealDebrid(realDebridAuthDataStore)
        every { premiumizeService.observeAccountState() } returns flowOf(
            PremiumizeAccountState(
                apiKey = "pm-key",
                isConnected = false
            )
        )
        coJustRun { premiumizeService.refreshAccountState() }
        stubDisconnectedTorBox(torBoxService)

        coEvery { realDebridApi.getTorrents(any(), any(), any()) } returns Response.success(emptyList())
        coEvery { realDebridApi.getDownloads(any(), any(), any()) } returns Response.success(emptyList())

        val realDebridAuthService = RealDebridAuthService(realDebridApi, realDebridAuthDataStore)
        val service = DebridLibraryService(
            realDebridApi = realDebridApi,
            realDebridAuthDataStore = realDebridAuthDataStore,
            realDebridAuthService = realDebridAuthService,
            premiumizeApi = premiumizeApi,
            premiumizeService = premiumizeService,
            torBoxApi = torBoxApi,
            torBoxService = torBoxService
        )

        val candidates = service.getBenchmarkCandidates(DebridBenchmarkProvider.PREMIUMIZE)

        assertTrue(candidates.isEmpty())
        coVerify(exactly = 1) { premiumizeService.refreshAccountState() }
        coVerify(exactly = 0) { premiumizeApi.listAllItems(any()) }
        coVerify(exactly = 0) { premiumizeApi.getItemDetails(any(), any()) }
    }

    @Test
    fun `premiumize benchmark lookup does not suppress later all provider refreshes`() = runTest {
        val realDebridApi = mockk<RealDebridApi>()
        val realDebridAuthDataStore = mockk<RealDebridAuthDataStore>()
        val premiumizeApi = mockk<PremiumizeApi>()
        val premiumizeService = mockk<PremiumizeService>()
        val torBoxApi = mockk<TorBoxApi>()
        val torBoxService = mockk<TorBoxService>()

        stubAuthenticatedRealDebrid(realDebridAuthDataStore)
        every { premiumizeService.observeAccountState() } returns flowOf(
            PremiumizeAccountState(
                apiKey = "pm-key",
                isConnected = true
            )
        )
        coJustRun { premiumizeService.refreshAccountState() }
        stubDisconnectedTorBox(torBoxService)

        coEvery { realDebridApi.getTorrents(any(), any(), any()) } returns Response.success(
            listOf(
                RealDebridTorrentDto(
                    id = "rd-1",
                    filename = "RealDebrid.Movie.2024.mkv",
                    status = "downloaded",
                    links = listOf("https://rd.test/link/rd-1"),
                    ended = "2026-03-30T12:00:00Z"
                )
            )
        )
        coEvery { realDebridApi.getTorrentInfo(any(), "rd-1") } returns Response.success(
            RealDebridTorrentInfoDto(
                id = "rd-1",
                filename = "RealDebrid.Movie.2024.mkv",
                status = "downloaded",
                links = listOf("https://rd.test/link/rd-1"),
                files = listOf(
                    RealDebridTorrentFileDto(
                        id = 90,
                        path = "/RealDebrid.Movie.2024.mkv",
                        bytes = 1_000L,
                        selected = 1
                    )
                ),
                ended = "2026-03-30T12:00:00Z"
            )
        )
        coEvery { realDebridApi.getDownloads(any(), any(), any()) } returns Response.success(
            listOf(
                RealDebridDownloadDto(
                    id = "rd-download-1",
                    filename = "RealDebrid.Movie.2024.mkv",
                    link = "https://rd.test/link/rd-1",
                    download = "https://rd.test/download/rd-1.mkv",
                    mimeType = "video/x-matroska"
                )
            )
        )
        coEvery { realDebridApi.unrestrictLink(any(), any(), any()) } returns Response.error(503, mockk(relaxed = true))

        coEvery { premiumizeApi.listAllItems("pm-key") } returns Response.success(
            PremiumizeListAllDto(
                status = "success",
                files = listOf(
                    PremiumizeListAllFileDto(
                        id = "pm-1",
                        name = "Premiumize.Movie.2024.mkv",
                        createdAt = 300L,
                        mimeType = "video/x-matroska",
                        path = "/Premiumize.Movie.2024.mkv"
                    )
                )
            )
        )
        coEvery { premiumizeApi.getItemDetails("pm-key", "pm-1") } returns Response.success(
            PremiumizeItemDetailsDto(
                id = "pm-1",
                name = "Premiumize.Movie.2024.mkv",
                streamLink = "https://pm.test/direct/pm-1",
                mimeType = "video/x-matroska",
                createdAt = 300L
            )
        )

        val realDebridAuthService = RealDebridAuthService(realDebridApi, realDebridAuthDataStore)
        val service = DebridLibraryService(
            realDebridApi = realDebridApi,
            realDebridAuthDataStore = realDebridAuthDataStore,
            realDebridAuthService = realDebridAuthService,
            premiumizeApi = premiumizeApi,
            premiumizeService = premiumizeService,
            torBoxApi = torBoxApi,
            torBoxService = torBoxService
        )

        service.getBenchmarkCandidates(DebridBenchmarkProvider.PREMIUMIZE)
        val items = service.observeItems().first()

        assertTrue(items.any { it.listKeys.contains(DebridLibraryService.REAL_DEBRID_LIST_KEY) })
        assertTrue(items.any { it.listKeys.contains(DebridLibraryService.PREMIUMIZE_LIST_KEY) })
        coVerify(exactly = 1) { realDebridApi.getTorrents(any(), any(), any()) }
        coVerify(exactly = 2) { premiumizeApi.listAllItems("pm-key") }
    }

    @Test
    fun `benchmark lookup does not suppress later all provider refreshes`() = runTest {
        val realDebridApi = mockk<RealDebridApi>()
        val realDebridAuthDataStore = mockk<RealDebridAuthDataStore>()
        val premiumizeApi = mockk<PremiumizeApi>()
        val premiumizeService = mockk<PremiumizeService>()
        val torBoxApi = mockk<TorBoxApi>()
        val torBoxService = mockk<TorBoxService>()

        stubAuthenticatedRealDebrid(realDebridAuthDataStore)
        every { premiumizeService.observeAccountState() } returns flowOf(
            PremiumizeAccountState(
                apiKey = "pm-key",
                isConnected = true
            )
        )
        coJustRun { premiumizeService.refreshAccountState() }
        stubDisconnectedTorBox(torBoxService)

        coEvery { realDebridApi.getTorrents(any(), any(), any()) } returns Response.success(emptyList())
        coEvery { realDebridApi.getDownloads(any(), any(), any()) } returns Response.success(emptyList())

        coEvery { premiumizeApi.listAllItems("pm-key") } returns Response.success(
            PremiumizeListAllDto(
                status = "success",
                files = listOf(
                    PremiumizeListAllFileDto(
                        id = "pm-1",
                        name = "Premiumize.Movie.2024.mkv",
                        createdAt = 300L,
                        mimeType = "video/x-matroska",
                        path = "/Premiumize.Movie.2024.mkv"
                    )
                )
            )
        )
        coEvery { premiumizeApi.getItemDetails("pm-key", "pm-1") } returns Response.success(
            PremiumizeItemDetailsDto(
                id = "pm-1",
                name = "Premiumize.Movie.2024.mkv",
                streamLink = "https://pm.test/direct/pm-1",
                mimeType = "video/x-matroska",
                createdAt = 300L
            )
        )

        val realDebridAuthService = RealDebridAuthService(realDebridApi, realDebridAuthDataStore)
        val service = DebridLibraryService(
            realDebridApi = realDebridApi,
            realDebridAuthDataStore = realDebridAuthDataStore,
            realDebridAuthService = realDebridAuthService,
            premiumizeApi = premiumizeApi,
            premiumizeService = premiumizeService,
            torBoxApi = torBoxApi,
            torBoxService = torBoxService
        )

        service.getBenchmarkCandidates(DebridBenchmarkProvider.REAL_DEBRID)
        val items = service.observeItems().first()

        assertTrue(items.any { it.listKeys.contains(DebridLibraryService.PREMIUMIZE_LIST_KEY) })
        coVerify(exactly = 1) { premiumizeApi.listAllItems("pm-key") }
    }

    @Test
    fun `refresh torbox exposes cached playable files with direct playback urls`() = runTest {
        val realDebridApi = mockk<RealDebridApi>()
        val realDebridAuthDataStore = mockk<RealDebridAuthDataStore>()
        val premiumizeApi = mockk<PremiumizeApi>()
        val premiumizeService = mockk<PremiumizeService>()
        val torBoxApi = mockk<TorBoxApi>()
        val torBoxService = mockk<TorBoxService>()

        every { realDebridAuthDataStore.isAuthenticated } returns flowOf(false)
        every { realDebridAuthDataStore.state } returns flowOf(RealDebridAuthState())
        every { premiumizeService.observeAccountState() } returns flowOf(PremiumizeAccountState())
        coJustRun { premiumizeService.refreshAccountState() }
        every { torBoxService.observeAccountState() } returns flowOf(
            TorBoxAccountState(apiKey = "tb-key", email = "user@example.com", plan = "pro", isConnected = true)
        )
        coJustRun { torBoxService.refreshAccountState() }

        coEvery {
            torBoxApi.getMyTorrentList(
                authorization = "Bearer tb-key",
                id = null,
                bypassCache = true,
                offset = null,
                limit = 100
            )
        } returns Response.success(
            TorBoxEnvelopeDto(
                success = true,
                data = listOf(
                    TorBoxTorrentListItemDto(
                        id = 22,
                        hash = "ABCDEF0123456789ABCDEF0123456789ABCDEF01",
                        name = "TorBox.Movie.2026",
                        downloadFinished = true,
                        downloadPresent = true,
                        createdAt = "2026-03-30T12:00:00Z",
                        files = listOf(
                            TorBoxFileDto(
                                id = 7,
                                name = "TorBox.Movie.2026.1080p.mkv",
                                shortName = "TorBox.Movie.2026.1080p.mkv",
                                size = 4_000_000_000L,
                                mimeType = "video/x-matroska"
                            )
                        )
                    )
                )
            )
        )
        coEvery {
            torBoxApi.requestDownloadLink(
                token = "tb-key",
                torrentId = 22,
                fileId = 7,
                zipLink = false,
                redirect = false
            )
        } returns Response.success(
            TorBoxEnvelopeDto(success = true, data = "https://tb.test/download/movie.mkv")
        )

        val realDebridAuthService = RealDebridAuthService(realDebridApi, realDebridAuthDataStore)
        val service = DebridLibraryService(
            realDebridApi = realDebridApi,
            realDebridAuthDataStore = realDebridAuthDataStore,
            realDebridAuthService = realDebridAuthService,
            premiumizeApi = premiumizeApi,
            premiumizeService = premiumizeService,
            torBoxApi = torBoxApi,
            torBoxService = torBoxService
        )

        service.refreshNow(DebridLibraryService.RefreshTarget.TORBOX)

        val tabs = service.observeListTabs().first()
        val items = service.observeItems().first()

        assertEquals(listOf(DebridLibraryService.TORBOX_LIST_KEY), tabs.map { it.key })
        assertEquals(1, items.size)
        assertEquals("https://tb.test/download/movie.mkv", items.single().directPlaybackUrl)
        assertEquals(setOf(DebridLibraryService.TORBOX_LIST_KEY), items.single().listKeys)
        assertEquals("TorBox.Movie.2026.1080p", items.single().name)
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

    private fun stubDisconnectedTorBox(torBoxService: TorBoxService) {
        every { torBoxService.observeAccountState() } returns flowOf(TorBoxAccountState())
        coJustRun { torBoxService.refreshAccountState() }
    }
}
