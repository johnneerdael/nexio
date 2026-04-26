package com.nexio.tv.data.repository

import com.nexio.tv.core.integration.IntegrationFetchOptions
import com.nexio.tv.core.integration.IntegrationFetchResult
import com.nexio.tv.core.integration.IntegrationLoadResult
import com.nexio.tv.core.integration.IntegrationRuntime
import com.nexio.tv.data.integration.debrid.EasyDebridIntegrationProvider
import com.nexio.tv.data.integration.debrid.PremiumizeIntegrationProvider
import com.nexio.tv.data.integration.debrid.RealDebridIntegrationProvider
import com.nexio.tv.data.integration.debrid.TorBoxIntegrationProvider
import com.nexio.tv.data.local.EasyDebridSettings
import com.nexio.tv.data.local.EasyDebridSettingsDataStore
import com.nexio.tv.data.local.PremiumizeSettings
import com.nexio.tv.data.local.PremiumizeSettingsDataStore
import com.nexio.tv.data.local.RealDebridAuthDataStore
import com.nexio.tv.data.local.RealDebridAuthState
import com.nexio.tv.data.local.TorBoxSettings
import com.nexio.tv.data.local.TorBoxSettingsDataStore
import com.nexio.tv.data.remote.api.PremiumizeApi
import com.nexio.tv.data.remote.api.RealDebridApi
import com.nexio.tv.data.remote.api.TorBoxApi
import com.nexio.tv.data.remote.api.EasyDebridApi
import com.nexio.tv.data.remote.dto.debrid.EasyDebridUserDto
import com.nexio.tv.data.remote.dto.debrid.PremiumizeItemDetailsDto
import com.nexio.tv.data.remote.dto.debrid.PremiumizeListAllDto
import com.nexio.tv.data.remote.dto.debrid.PremiumizeListAllFileDto
import com.nexio.tv.data.remote.dto.debrid.PremiumizeAccountInfoDto
import com.nexio.tv.data.remote.dto.debrid.EasyDebridGenerateDto
import com.nexio.tv.data.remote.dto.debrid.EasyDebridGeneratedFileDto
import com.nexio.tv.data.remote.dto.debrid.EasyDebridLookupDetailsDto
import com.nexio.tv.data.remote.dto.debrid.EasyDebridLookupDetailsResultDto
import com.nexio.tv.data.remote.dto.debrid.EasyDebridLookupFileDto
import com.nexio.tv.data.remote.dto.debrid.EasyDebridLookupRequestDto
import com.nexio.tv.data.remote.dto.debrid.RealDebridDownloadDto
import com.nexio.tv.data.remote.dto.debrid.RealDebridTorrentFileDto
import com.nexio.tv.data.remote.dto.debrid.RealDebridTorrentInfoDto
import com.nexio.tv.data.remote.dto.debrid.RealDebridTorrentDto
import com.nexio.tv.data.remote.dto.debrid.RealDebridUnrestrictLinkDto
import com.nexio.tv.data.remote.dto.debrid.TorBoxEnvelopeDto
import com.nexio.tv.data.remote.dto.debrid.TorBoxFileDto
import com.nexio.tv.data.remote.dto.debrid.TorBoxTorrentListItemDto
import com.nexio.tv.data.remote.dto.debrid.TorBoxUserDto
import com.nexio.tv.data.repository.benchmark.DebridBenchmarkCandidateLookupResult
import com.nexio.tv.data.repository.benchmark.DebridBenchmarkProvider
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
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
        val easyDebridApi = mockk<EasyDebridApi>()
        val easyDebridService = mockk<EasyDebridService>()

        stubAuthenticatedRealDebrid(realDebridAuthDataStore)
        every { premiumizeService.observeAccountState() } returns flowOf(PremiumizeAccountState())
        coJustRun { premiumizeService.refreshAccountState() }
        stubDisconnectedTorBox(torBoxService)
        stubDisconnectedEasyDebrid(easyDebridService)

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

        val service = buildDebridLibraryService(
            realDebridApi = realDebridApi,
            realDebridAuthDataStore = realDebridAuthDataStore,
            premiumizeApi = premiumizeApi,
            premiumizeService = premiumizeService,
            torBoxApi = torBoxApi,
            torBoxService = torBoxService,
            easyDebridApi = easyDebridApi,
            easyDebridService = easyDebridService
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
        val easyDebridApi = mockk<EasyDebridApi>()
        val easyDebridService = mockk<EasyDebridService>()

        stubAuthenticatedRealDebrid(realDebridAuthDataStore)
        every { premiumizeService.observeAccountState() } returns flowOf(PremiumizeAccountState())
        coJustRun { premiumizeService.refreshAccountState() }
        stubDisconnectedTorBox(torBoxService)
        stubDisconnectedEasyDebrid(easyDebridService)

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

        val service = buildDebridLibraryService(
            realDebridApi = realDebridApi,
            realDebridAuthDataStore = realDebridAuthDataStore,
            premiumizeApi = premiumizeApi,
            premiumizeService = premiumizeService,
            torBoxApi = torBoxApi,
            torBoxService = torBoxService,
            easyDebridApi = easyDebridApi,
            easyDebridService = easyDebridService
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
        val easyDebridApi = mockk<EasyDebridApi>()
        val easyDebridService = mockk<EasyDebridService>()

        stubAuthenticatedRealDebrid(realDebridAuthDataStore)
        every { premiumizeService.observeAccountState() } returns flowOf(PremiumizeAccountState())
        coJustRun { premiumizeService.refreshAccountState() }
        stubDisconnectedTorBox(torBoxService)
        stubDisconnectedEasyDebrid(easyDebridService)

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

        val service = buildDebridLibraryService(
            realDebridApi = realDebridApi,
            realDebridAuthDataStore = realDebridAuthDataStore,
            premiumizeApi = premiumizeApi,
            premiumizeService = premiumizeService,
            torBoxApi = torBoxApi,
            torBoxService = torBoxService,
            easyDebridApi = easyDebridApi,
            easyDebridService = easyDebridService
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
        val easyDebridApi = mockk<EasyDebridApi>()
        val easyDebridService = mockk<EasyDebridService>()

        stubAuthenticatedRealDebrid(realDebridAuthDataStore)
        every { premiumizeService.observeAccountState() } returns flowOf(PremiumizeAccountState())
        coJustRun { premiumizeService.refreshAccountState() }
        stubDisconnectedTorBox(torBoxService)
        stubDisconnectedEasyDebrid(easyDebridService)

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

        val service = buildDebridLibraryService(
            realDebridApi = realDebridApi,
            realDebridAuthDataStore = realDebridAuthDataStore,
            premiumizeApi = premiumizeApi,
            premiumizeService = premiumizeService,
            torBoxApi = torBoxApi,
            torBoxService = torBoxService,
            easyDebridApi = easyDebridApi,
            easyDebridService = easyDebridService
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
        val easyDebridApi = mockk<EasyDebridApi>()
        val easyDebridService = mockk<EasyDebridService>()

        stubAuthenticatedRealDebrid(realDebridAuthDataStore)
        every { premiumizeService.observeAccountState() } returns flowOf(PremiumizeAccountState())
        coJustRun { premiumizeService.refreshAccountState() }
        stubDisconnectedTorBox(torBoxService)
        stubDisconnectedEasyDebrid(easyDebridService)

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

        val service = buildDebridLibraryService(
            realDebridApi = realDebridApi,
            realDebridAuthDataStore = realDebridAuthDataStore,
            premiumizeApi = premiumizeApi,
            premiumizeService = premiumizeService,
            torBoxApi = torBoxApi,
            torBoxService = torBoxService,
            easyDebridApi = easyDebridApi,
            easyDebridService = easyDebridService
        )

        service.refreshNow(DebridLibraryService.RefreshTarget.REAL_DEBRID)

        val items = service.observeItems().first()

        assertEquals(1, items.size)
        assertEquals("rd:torrent:samples:file:50", items.single().id)
        assertEquals("Hoppers.2026.1080p.TELESYNC.x264-SyncUP", items.single().name)
    }

    @Test
    fun `premiumize benchmark lookup prefers the 20gb tier and carries source size metadata`() = runTest {
        val realDebridApi = mockk<RealDebridApi>()
        val realDebridAuthDataStore = mockk<RealDebridAuthDataStore>()
        val premiumizeApi = mockk<PremiumizeApi>()
        val premiumizeService = mockk<PremiumizeService>()
        val torBoxApi = mockk<TorBoxApi>()
        val torBoxService = mockk<TorBoxService>()
        val easyDebridApi = mockk<EasyDebridApi>()
        val easyDebridService = mockk<EasyDebridService>()

        stubAuthenticatedRealDebrid(realDebridAuthDataStore)
        every { premiumizeService.observeAccountState() } returns flowOf(
            PremiumizeAccountState(
                apiKey = "pm-key",
                isConnected = true
            )
        )
        coJustRun { premiumizeService.refreshAccountState() }
        stubDisconnectedTorBox(torBoxService)
        stubDisconnectedEasyDebrid(easyDebridService)

        coEvery { realDebridApi.getTorrents(any(), any(), any()) } returns Response.success(emptyList())
        coEvery { realDebridApi.getDownloads(any(), any(), any()) } returns Response.success(emptyList())

        coEvery { premiumizeApi.listAllItems("pm-key") } returns Response.success(
            PremiumizeListAllDto(
                status = "success",
                files = listOf(
                    PremiumizeListAllFileDto(
                        id = "largest",
                        name = "Largest.Movie.2024.mkv",
                        createdAt = 100L,
                        size = 21L * 1024L * 1024L * 1024L,
                        mimeType = "video/x-matroska",
                        path = "/Largest.Movie.2024.mkv"
                    ),
                    PremiumizeListAllFileDto(
                        id = "large",
                        name = "Large.Movie.2024.mkv",
                        createdAt = 200L,
                        size = 16L * 1024L * 1024L * 1024L,
                        mimeType = "video/x-matroska",
                        path = "/Large.Movie.2024.mkv"
                    ),
                    PremiumizeListAllFileDto(
                        id = "mid",
                        name = "Mid.Movie.2024.mkv",
                        createdAt = 300L,
                        size = 12L * 1024L * 1024L * 1024L,
                        mimeType = "video/x-matroska",
                        path = "/Mid.Movie.2024.mkv"
                    ),
                    PremiumizeListAllFileDto(
                        id = "small",
                        name = "Small.Movie.2024.mkv",
                        createdAt = 400L,
                        size = 6L * 1024L * 1024L * 1024L,
                        mimeType = "video/x-matroska",
                        path = "/Small.Movie.2024.mkv"
                    )
                )
            )
        )
        coEvery { premiumizeApi.getItemDetails("pm-key", "largest") } returns Response.success(
            PremiumizeItemDetailsDto(
                id = "largest",
                name = "Largest.Movie.2024.mkv",
                streamLink = "https://pm.test/direct/largest",
                size = 21L * 1024L * 1024L * 1024L,
                mimeType = "video/x-matroska",
                createdAt = 100L
            )
        )

        val service = buildDebridLibraryService(
            realDebridApi = realDebridApi,
            realDebridAuthDataStore = realDebridAuthDataStore,
            premiumizeApi = premiumizeApi,
            premiumizeService = premiumizeService,
            torBoxApi = torBoxApi,
            torBoxService = torBoxService,
            easyDebridApi = easyDebridApi,
            easyDebridService = easyDebridService
        )

        service.refreshNow(DebridLibraryService.RefreshTarget.REAL_DEBRID)

        val result = service.getBenchmarkCandidates(DebridBenchmarkProvider.PREMIUMIZE)

        assertTrue(result is DebridBenchmarkCandidateLookupResult.Candidates)
        val candidates = (result as DebridBenchmarkCandidateLookupResult.Candidates).items
        assertEquals(1, candidates.size)
        assertEquals(listOf("https://pm.test/direct/largest"), candidates.map { it.directUrl })
        assertEquals(listOf(21L * 1024L * 1024L * 1024L), candidates.map { it.sourceSizeBytes })
        coVerify(exactly = 1) { premiumizeApi.getItemDetails("pm-key", "largest") }
        coVerify(exactly = 0) { premiumizeApi.getItemDetails("pm-key", "large") }
        coVerify(exactly = 0) { premiumizeApi.getItemDetails("pm-key", "mid") }
        coVerify(exactly = 0) { premiumizeApi.getItemDetails("pm-key", "small") }
    }

    @Test
    fun `real debrid benchmark lookup falls back to the 15gb tier using resolved file size`() = runTest {
        val realDebridApi = mockk<RealDebridApi>()
        val realDebridAuthDataStore = mockk<RealDebridAuthDataStore>()
        val premiumizeApi = mockk<PremiumizeApi>()
        val premiumizeService = mockk<PremiumizeService>()
        val torBoxApi = mockk<TorBoxApi>()
        val torBoxService = mockk<TorBoxService>()
        val easyDebridApi = mockk<EasyDebridApi>()
        val easyDebridService = mockk<EasyDebridService>()

        stubAuthenticatedRealDebrid(realDebridAuthDataStore)
        every { premiumizeService.observeAccountState() } returns flowOf(PremiumizeAccountState())
        coJustRun { premiumizeService.refreshAccountState() }
        stubDisconnectedTorBox(torBoxService)
        stubDisconnectedEasyDebrid(easyDebridService)

        coEvery { realDebridApi.getTorrents(any(), any(), any()) } returns Response.success(
            listOf(
                RealDebridTorrentDto(
                    id = "rd-largest",
                    filename = "Largest.Movie.2024.mkv",
                    status = "downloaded",
                    bytes = 19L * 1024L * 1024L * 1024L,
                    links = listOf("https://rd.test/link/rd-largest"),
                    ended = "2026-03-30T12:00:00Z"
                ),
                RealDebridTorrentDto(
                    id = "rd-large",
                    filename = "Large.Movie.2024.mkv",
                    status = "downloaded",
                    bytes = 12L * 1024L * 1024L * 1024L,
                    links = listOf("https://rd.test/link/rd-large"),
                    ended = "2026-03-30T11:00:00Z"
                ),
                RealDebridTorrentDto(
                    id = "rd-mid",
                    filename = "Mid.Movie.2024.mkv",
                    status = "downloaded",
                    bytes = 11L * 1024L * 1024L * 1024L,
                    links = listOf("https://rd.test/link/rd-mid"),
                    ended = "2026-03-30T10:30:00Z"
                ),
                RealDebridTorrentDto(
                    id = "rd-fallback",
                    filename = "Fallback.Movie.2024.mkv",
                    status = "downloaded",
                    bytes = 6L * 1024L * 1024L * 1024L,
                    links = listOf("https://rd.test/link/rd-fallback"),
                    ended = "2026-03-30T10:00:00Z"
                )
            )
        )
        coEvery { realDebridApi.getTorrentInfo(any(), "rd-largest") } returns Response.success(
            RealDebridTorrentInfoDto(
                id = "rd-largest",
                filename = "Largest.Movie.2024.mkv",
                status = "downloaded",
                links = listOf("https://rd.test/link/rd-largest"),
                files = listOf(
                    RealDebridTorrentFileDto(
                        id = 91,
                        path = "/Largest.Movie.2024.mkv",
                        bytes = 19L * 1024L * 1024L * 1024L,
                        selected = 1
                    )
                ),
                ended = "2026-03-30T12:00:00Z"
            )
        )
        coEvery { realDebridApi.getTorrentInfo(any(), "rd-large") } returns Response.success(
            RealDebridTorrentInfoDto(
                id = "rd-large",
                filename = "Large.Movie.2024.mkv",
                status = "downloaded",
                links = listOf("https://rd.test/link/rd-large"),
                files = listOf(
                    RealDebridTorrentFileDto(
                        id = 92,
                        path = "/Large.Movie.2024.mkv",
                        bytes = 12L * 1024L * 1024L * 1024L,
                        selected = 1
                    )
                ),
                ended = "2026-03-30T11:00:00Z"
            )
        )
        coEvery { realDebridApi.getTorrentInfo(any(), "rd-mid") } returns Response.success(
            RealDebridTorrentInfoDto(
                id = "rd-mid",
                filename = "Mid.Movie.2024.mkv",
                status = "downloaded",
                links = listOf("https://rd.test/link/rd-mid"),
                files = listOf(
                    RealDebridTorrentFileDto(
                        id = 93,
                        path = "/Mid.Movie.2024.mkv",
                        bytes = 11L * 1024L * 1024L * 1024L,
                        selected = 1
                    )
                ),
                ended = "2026-03-30T10:30:00Z"
            )
        )
        coEvery { realDebridApi.getTorrentInfo(any(), "rd-fallback") } returns Response.success(
            RealDebridTorrentInfoDto(
                id = "rd-fallback",
                filename = "Fallback.Movie.2024.mkv",
                status = "downloaded",
                links = listOf("https://rd.test/link/rd-fallback"),
                files = listOf(
                    RealDebridTorrentFileDto(
                        id = 94,
                        path = "/Fallback.Movie.2024.mkv",
                        bytes = 6L * 1024L * 1024L * 1024L,
                        selected = 1
                    )
                ),
                ended = "2026-03-30T10:00:00Z"
            )
        )
        coEvery { realDebridApi.getDownloads(any(), any(), any()) } returns Response.success(
            listOf(
                RealDebridDownloadDto(
                    id = "rd-download-largest",
                    filename = "Largest.Movie.2024.mkv",
                    mimeType = "video/x-matroska",
                    fileSize = 19L * 1024L * 1024L * 1024L,
                    link = "https://rd.test/link/rd-largest",
                    download = "https://rd.test/download/rd-largest.mkv"
                ),
                RealDebridDownloadDto(
                    id = "rd-download-large",
                    filename = "Large.Movie.2024.mkv",
                    mimeType = "video/x-matroska",
                    fileSize = 12L * 1024L * 1024L * 1024L,
                    link = "https://rd.test/link/rd-large",
                    download = "https://rd.test/download/rd-large.mkv"
                )
            )
        )
        coEvery { realDebridApi.unrestrictLink(any(), any(), any()) } returns Response.error(503, mockk(relaxed = true))

        val service = buildDebridLibraryService(
            realDebridApi = realDebridApi,
            realDebridAuthDataStore = realDebridAuthDataStore,
            premiumizeApi = premiumizeApi,
            premiumizeService = premiumizeService,
            torBoxApi = torBoxApi,
            torBoxService = torBoxService,
            easyDebridApi = easyDebridApi,
            easyDebridService = easyDebridService
        )

        val result = service.getBenchmarkCandidates(DebridBenchmarkProvider.REAL_DEBRID)

        assertTrue(result is DebridBenchmarkCandidateLookupResult.Candidates)
        val candidates = (result as DebridBenchmarkCandidateLookupResult.Candidates).items
        assertEquals(1, candidates.size)
        assertEquals(
            listOf(
                "https://rd.test/download/rd-largest.mkv"
            ),
            candidates.map { it.directUrl }
        )
        assertEquals(
            listOf(
                19L * 1024L * 1024L * 1024L
            ),
            candidates.map { it.sourceSizeBytes }
        )
        coVerify(exactly = 1) { realDebridApi.getTorrentInfo(any(), "rd-largest") }
        coVerify(exactly = 1) { realDebridApi.getTorrentInfo(any(), "rd-large") }
        coVerify(exactly = 1) { realDebridApi.getTorrentInfo(any(), "rd-mid") }
        coVerify(exactly = 1) { realDebridApi.getTorrentInfo(any(), "rd-fallback") }
    }

    @Test
    fun `real debrid benchmark lookup ignores pack size when selected playable file is smaller than the threshold tier`() = runTest {
        val realDebridApi = mockk<RealDebridApi>()
        val realDebridAuthDataStore = mockk<RealDebridAuthDataStore>()
        val premiumizeApi = mockk<PremiumizeApi>()
        val premiumizeService = mockk<PremiumizeService>()
        val torBoxApi = mockk<TorBoxApi>()
        val torBoxService = mockk<TorBoxService>()
        val easyDebridApi = mockk<EasyDebridApi>()
        val easyDebridService = mockk<EasyDebridService>()

        stubAuthenticatedRealDebrid(realDebridAuthDataStore)
        every { premiumizeService.observeAccountState() } returns flowOf(PremiumizeAccountState())
        coJustRun { premiumizeService.refreshAccountState() }
        stubDisconnectedTorBox(torBoxService)
        stubDisconnectedEasyDebrid(easyDebridService)

        coEvery { realDebridApi.getTorrents(any(), any(), any()) } returns Response.success(
            listOf(
                RealDebridTorrentDto(
                    id = "season-pack",
                    filename = "The Sopranos S01-S06 1080p BluRay REMUX AVC DTS-HD MA 5.1 [RiCK]",
                    status = "downloaded",
                    bytes = 967_342_670_000L,
                    links = listOf("https://rd.test/link/season-pack"),
                    ended = "2026-03-30T12:00:00Z"
                ),
                RealDebridTorrentDto(
                    id = "standalone-remux",
                    filename = "Standalone.Movie.2024.1080p.BluRay.REMUX.mkv",
                    status = "downloaded",
                    bytes = 26L * 1024L * 1024L * 1024L,
                    links = listOf("https://rd.test/link/standalone-remux"),
                    ended = "2026-03-30T11:00:00Z"
                )
            )
        )
        coEvery { realDebridApi.getTorrentInfo(any(), "season-pack") } returns Response.success(
            RealDebridTorrentInfoDto(
                id = "season-pack",
                filename = "The Sopranos S01-S06 1080p BluRay REMUX AVC DTS-HD MA 5.1 [RiCK]",
                status = "downloaded",
                links = listOf("https://rd.test/link/season-pack"),
                files = listOf(
                    RealDebridTorrentFileDto(
                        id = 101,
                        path = "/The Sopranos S04E13 1080p Blu-ray Remux AVC DTS-HD MA 5.1.mkv",
                        bytes = 16_590_716_963L,
                        selected = 1
                    )
                ),
                ended = "2026-03-30T12:00:00Z"
            )
        )
        coEvery { realDebridApi.getTorrentInfo(any(), "standalone-remux") } returns Response.success(
            RealDebridTorrentInfoDto(
                id = "standalone-remux",
                filename = "Standalone.Movie.2024.1080p.BluRay.REMUX.mkv",
                status = "downloaded",
                links = listOf("https://rd.test/link/standalone-remux"),
                files = listOf(
                    RealDebridTorrentFileDto(
                        id = 102,
                        path = "/Standalone.Movie.2024.1080p.BluRay.REMUX.mkv",
                        bytes = 26L * 1024L * 1024L * 1024L,
                        selected = 1
                    )
                ),
                ended = "2026-03-30T11:00:00Z"
            )
        )
        coEvery { realDebridApi.getDownloads(any(), any(), any()) } returns Response.success(
            listOf(
                RealDebridDownloadDto(
                    id = "rd-download-standalone",
                    filename = "Standalone.Movie.2024.1080p.BluRay.REMUX.mkv",
                    mimeType = "video/x-matroska",
                    fileSize = 26L * 1024L * 1024L * 1024L,
                    link = "https://rd.test/link/standalone-remux",
                    download = "https://rd.test/download/standalone-remux.mkv"
                )
            )
        )
        coEvery { realDebridApi.unrestrictLink(any(), any(), any()) } returns Response.error(503, mockk(relaxed = true))

        val service = buildDebridLibraryService(
            realDebridApi = realDebridApi,
            realDebridAuthDataStore = realDebridAuthDataStore,
            premiumizeApi = premiumizeApi,
            premiumizeService = premiumizeService,
            torBoxApi = torBoxApi,
            torBoxService = torBoxService,
            easyDebridApi = easyDebridApi,
            easyDebridService = easyDebridService
        )

        val result = service.getBenchmarkCandidates(DebridBenchmarkProvider.REAL_DEBRID)

        assertTrue(result is DebridBenchmarkCandidateLookupResult.Candidates)
        val candidates = (result as DebridBenchmarkCandidateLookupResult.Candidates).items
        assertEquals(1, candidates.size)
        assertEquals("https://rd.test/download/standalone-remux.mkv", candidates.single().directUrl)
        assertEquals(26L * 1024L * 1024L * 1024L, candidates.single().sourceSizeBytes)
        coVerify(exactly = 1) { realDebridApi.getTorrentInfo(any(), "season-pack") }
        coVerify(exactly = 1) { realDebridApi.getTorrentInfo(any(), "standalone-remux") }
    }

    @Test
    fun `benchmark lookup skips premiumize library calls when refresh reports disconnected`() = runTest {
        val realDebridApi = mockk<RealDebridApi>()
        val realDebridAuthDataStore = mockk<RealDebridAuthDataStore>()
        val premiumizeApi = mockk<PremiumizeApi>()
        val premiumizeService = mockk<PremiumizeService>()
        val torBoxApi = mockk<TorBoxApi>()
        val torBoxService = mockk<TorBoxService>()
        val easyDebridApi = mockk<EasyDebridApi>()
        val easyDebridService = mockk<EasyDebridService>()

        stubAuthenticatedRealDebrid(realDebridAuthDataStore)
        every { premiumizeService.observeAccountState() } returns flowOf(
            PremiumizeAccountState(
                apiKey = "pm-key",
                isConnected = false
            )
        )
        coJustRun { premiumizeService.refreshAccountState() }
        stubDisconnectedTorBox(torBoxService)
        stubDisconnectedEasyDebrid(easyDebridService)

        coEvery { realDebridApi.getTorrents(any(), any(), any()) } returns Response.success(emptyList())
        coEvery { realDebridApi.getDownloads(any(), any(), any()) } returns Response.success(emptyList())

        val service = buildDebridLibraryService(
            realDebridApi = realDebridApi,
            realDebridAuthDataStore = realDebridAuthDataStore,
            premiumizeApi = premiumizeApi,
            premiumizeService = premiumizeService,
            torBoxApi = torBoxApi,
            torBoxService = torBoxService,
            easyDebridApi = easyDebridApi,
            easyDebridService = easyDebridService
        )

        val result = service.getBenchmarkCandidates(DebridBenchmarkProvider.PREMIUMIZE)

        assertEquals(DebridBenchmarkCandidateLookupResult.NoPlayableLibraryItem, result)
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
        val easyDebridApi = mockk<EasyDebridApi>()
        val easyDebridService = mockk<EasyDebridService>()

        stubAuthenticatedRealDebrid(realDebridAuthDataStore)
        every { premiumizeService.observeAccountState() } returns flowOf(
            PremiumizeAccountState(
                apiKey = "pm-key",
                isConnected = true
            )
        )
        coJustRun { premiumizeService.refreshAccountState() }
        stubDisconnectedTorBox(torBoxService)
        stubDisconnectedEasyDebrid(easyDebridService)

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

        val service = buildDebridLibraryService(
            realDebridApi = realDebridApi,
            realDebridAuthDataStore = realDebridAuthDataStore,
            premiumizeApi = premiumizeApi,
            premiumizeService = premiumizeService,
            torBoxApi = torBoxApi,
            torBoxService = torBoxService,
            easyDebridApi = easyDebridApi,
            easyDebridService = easyDebridService
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
        val easyDebridApi = mockk<EasyDebridApi>()
        val easyDebridService = mockk<EasyDebridService>()

        stubAuthenticatedRealDebrid(realDebridAuthDataStore)
        every { premiumizeService.observeAccountState() } returns flowOf(
            PremiumizeAccountState(
                apiKey = "pm-key",
                isConnected = true
            )
        )
        coJustRun { premiumizeService.refreshAccountState() }
        stubDisconnectedTorBox(torBoxService)
        stubDisconnectedEasyDebrid(easyDebridService)

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

        val service = buildDebridLibraryService(
            realDebridApi = realDebridApi,
            realDebridAuthDataStore = realDebridAuthDataStore,
            premiumizeApi = premiumizeApi,
            premiumizeService = premiumizeService,
            torBoxApi = torBoxApi,
            torBoxService = torBoxService,
            easyDebridApi = easyDebridApi,
            easyDebridService = easyDebridService
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
        val easyDebridApi = mockk<EasyDebridApi>()
        val easyDebridService = mockk<EasyDebridService>()

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

        val service = buildDebridLibraryService(
            realDebridApi = realDebridApi,
            realDebridAuthDataStore = realDebridAuthDataStore,
            premiumizeApi = premiumizeApi,
            premiumizeService = premiumizeService,
            torBoxApi = torBoxApi,
            torBoxService = torBoxService,
            easyDebridApi = easyDebridApi,
            easyDebridService = easyDebridService
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

    @Test
    fun `torbox benchmark lookup returns direct candidate from cached torrent list`() = runTest {
        val realDebridApi = mockk<RealDebridApi>()
        val realDebridAuthDataStore = mockk<RealDebridAuthDataStore>()
        val premiumizeApi = mockk<PremiumizeApi>()
        val premiumizeService = mockk<PremiumizeService>()
        val torBoxApi = mockk<TorBoxApi>()
        val torBoxService = mockk<TorBoxService>()
        val easyDebridApi = mockk<EasyDebridApi>()
        val easyDebridService = mockk<EasyDebridService>()

        every { realDebridAuthDataStore.isAuthenticated } returns flowOf(false)
        every { realDebridAuthDataStore.state } returns flowOf(RealDebridAuthState())
        every { premiumizeService.observeAccountState() } returns flowOf(PremiumizeAccountState())
        coJustRun { premiumizeService.refreshAccountState() }
        every { torBoxService.observeAccountState() } returns flowOf(
            TorBoxAccountState(apiKey = "tb-key", isConnected = true)
        )
        coJustRun { torBoxService.refreshAccountState() }
        stubDisconnectedEasyDebrid(easyDebridService)

        coEvery {
            torBoxApi.getMyTorrentList("Bearer tb-key", null, true, null, 100)
        } returns Response.success(
            TorBoxEnvelopeDto(
                success = true,
                data = listOf(
                    TorBoxTorrentListItemDto(
                        id = 9,
                        name = "TorBox.Benchmark.Movie.2026",
                        downloadFinished = true,
                        downloadPresent = true,
                        createdAt = "2026-03-30T12:00:00Z",
                        files = listOf(
                            TorBoxFileDto(
                                id = 1,
                                name = "TorBox.Benchmark.Movie.2026.mkv",
                                shortName = "TorBox.Benchmark.Movie.2026.mkv",
                                size = 6L * 1024L * 1024L * 1024L,
                                mimeType = "video/x-matroska"
                            )
                        )
                    )
                )
            )
        )
        coEvery {
            torBoxApi.requestDownloadLink("tb-key", 9, 1, false, false)
        } returns Response.success(TorBoxEnvelopeDto(success = true, data = "https://tb.test/benchmark.mkv"))

        val service = buildDebridLibraryService(
            realDebridApi = realDebridApi,
            realDebridAuthDataStore = realDebridAuthDataStore,
            premiumizeApi = premiumizeApi,
            premiumizeService = premiumizeService,
            torBoxApi = torBoxApi,
            torBoxService = torBoxService,
            easyDebridApi = easyDebridApi,
            easyDebridService = easyDebridService
        )

        val result = service.getBenchmarkCandidates(DebridBenchmarkProvider.TORBOX)

        assertTrue(result is DebridBenchmarkCandidateLookupResult.Candidates)
        assertEquals("https://tb.test/benchmark.mkv", (result as DebridBenchmarkCandidateLookupResult.Candidates).items.single().directUrl)
    }

    @Test
    fun `easydebrid benchmark lookup returns generated candidate from cached source`() = runTest {
        val realDebridApi = mockk<RealDebridApi>()
        val realDebridAuthDataStore = mockk<RealDebridAuthDataStore>()
        val premiumizeApi = mockk<PremiumizeApi>()
        val premiumizeService = mockk<PremiumizeService>()
        val torBoxApi = mockk<TorBoxApi>()
        val torBoxService = mockk<TorBoxService>()
        val easyDebridApi = mockk<EasyDebridApi>()
        val easyDebridService = mockk<EasyDebridService>()

        every { realDebridAuthDataStore.isAuthenticated } returns flowOf(false)
        every { realDebridAuthDataStore.state } returns flowOf(RealDebridAuthState())
        every { premiumizeService.observeAccountState() } returns flowOf(PremiumizeAccountState())
        coJustRun { premiumizeService.refreshAccountState() }
        stubDisconnectedTorBox(torBoxService)
        every { easyDebridService.observeAccountState() } returns flowOf(
            EasyDebridAccountState(apiKey = "ed-key", userId = "ed-user", isConnected = true)
        )
        coJustRun { easyDebridService.refreshAccountState() }

        coEvery {
            easyDebridApi.lookupDetails("Bearer ed-key", any<EasyDebridLookupRequestDto>())
        } returns Response.success(
            EasyDebridLookupDetailsDto(
                result = listOf(
                    EasyDebridLookupDetailsResultDto(
                        cached = true,
                        files = listOf(
                            EasyDebridLookupFileDto(
                                size = 6L * 1024L * 1024L * 1024L,
                                name = "EasyDebrid.Benchmark.Movie.2026.mkv",
                                folder = "benchmark"
                            )
                        )
                    ),
                    EasyDebridLookupDetailsResultDto(cached = false, files = emptyList())
                )
            )
        )
        coEvery {
            easyDebridApi.generate("Bearer ed-key", any())
        } returns Response.success(
            EasyDebridGenerateDto(
                files = listOf(
                    EasyDebridGeneratedFileDto(
                        filename = "EasyDebrid.Benchmark.Movie.2026.mkv",
                        directory = listOf("benchmark"),
                        size = 6L * 1024L * 1024L * 1024L,
                        url = "https://ed.test/benchmark.mkv"
                    )
                )
            )
        )

        val service = buildDebridLibraryService(
            realDebridApi = realDebridApi,
            realDebridAuthDataStore = realDebridAuthDataStore,
            premiumizeApi = premiumizeApi,
            premiumizeService = premiumizeService,
            torBoxApi = torBoxApi,
            torBoxService = torBoxService,
            easyDebridApi = easyDebridApi,
            easyDebridService = easyDebridService
        )

        val result = service.getBenchmarkCandidates(DebridBenchmarkProvider.EASY_DEBRID)

        assertTrue(result is DebridBenchmarkCandidateLookupResult.Candidates)
        assertEquals("https://ed.test/benchmark.mkv", (result as DebridBenchmarkCandidateLookupResult.Candidates).items.single().directUrl)
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

    private fun stubDisconnectedEasyDebrid(easyDebridService: EasyDebridService) {
        every { easyDebridService.observeAccountState() } returns flowOf(EasyDebridAccountState())
        coJustRun { easyDebridService.refreshAccountState() }
    }
}

private fun buildDebridLibraryService(
    realDebridApi: RealDebridApi,
    realDebridAuthDataStore: RealDebridAuthDataStore,
    premiumizeApi: PremiumizeApi,
    premiumizeService: PremiumizeService,
    torBoxApi: TorBoxApi,
    torBoxService: TorBoxService,
    easyDebridApi: EasyDebridApi,
    easyDebridService: EasyDebridService
): DebridLibraryService {
    stubProviderAccountInfo(
        premiumizeApi = premiumizeApi,
        premiumizeService = premiumizeService,
        torBoxApi = torBoxApi,
        torBoxService = torBoxService,
        easyDebridApi = easyDebridApi,
        easyDebridService = easyDebridService
    )

    val runtime = object : IntegrationRuntime {
        override suspend fun <T> get(
            spec: com.nexio.tv.core.integration.IntegrationSpec<T>,
            options: IntegrationFetchOptions
        ): IntegrationFetchResult<T> =
            when (val result = spec.load()) {
                is IntegrationLoadResult.Success -> IntegrationFetchResult.Updated(result.value)
                is IntegrationLoadResult.HttpError -> IntegrationFetchResult.Missing
                is IntegrationLoadResult.NetworkError -> IntegrationFetchResult.Missing
            }

        override suspend fun <T> call(spec: com.nexio.tv.core.integration.IntegrationCallSpec<T>) =
            spec.call.invoke()

        override suspend fun <T> open(spec: com.nexio.tv.core.integration.IntegrationStreamSpec<T>) =
            null
    }
    val premiumizeSettingsDataStore = mockk<PremiumizeSettingsDataStore>()
    every { premiumizeSettingsDataStore.settings } returns runCatching {
        premiumizeService.observeAccountState().map { PremiumizeSettings(apiKey = it.apiKey) }
    }.getOrElse { flowOf(PremiumizeSettings()) }
    val torBoxSettingsDataStore = mockk<TorBoxSettingsDataStore>()
    every { torBoxSettingsDataStore.settings } returns runCatching {
        torBoxService.observeAccountState().map { TorBoxSettings(apiKey = it.apiKey) }
    }.getOrElse { flowOf(TorBoxSettings()) }
    val easyDebridSettingsDataStore = mockk<EasyDebridSettingsDataStore>()
    every { easyDebridSettingsDataStore.settings } returns runCatching {
        easyDebridService.observeAccountState().map { EasyDebridSettings(apiKey = it.apiKey) }
    }.getOrElse { flowOf(EasyDebridSettings()) }
    val realDebridAuthIntegrationProvider =
        com.nexio.tv.data.integration.debrid.RealDebridAuthIntegrationProvider(runtime, realDebridApi)
    val realDebridAuthService = RealDebridAuthService(realDebridAuthIntegrationProvider, realDebridAuthDataStore)
    return DebridLibraryService(
        realDebridProvider = RealDebridIntegrationProvider(runtime, realDebridApi, realDebridAuthService),
        realDebridAuthDataStore = realDebridAuthDataStore,
        premiumizeProvider = PremiumizeIntegrationProvider(runtime, premiumizeApi),
        premiumizeSettingsDataStore = premiumizeSettingsDataStore,
        torBoxProvider = TorBoxIntegrationProvider(runtime, torBoxApi),
        torBoxSettingsDataStore = torBoxSettingsDataStore,
        easyDebridProvider = EasyDebridIntegrationProvider(runtime, easyDebridApi),
        easyDebridSettingsDataStore = easyDebridSettingsDataStore
    )
}

private fun stubProviderAccountInfo(
    premiumizeApi: PremiumizeApi,
    premiumizeService: PremiumizeService,
    torBoxApi: TorBoxApi,
    torBoxService: TorBoxService,
    easyDebridApi: EasyDebridApi,
    easyDebridService: EasyDebridService
) {
    val premiumizeState = runCatching {
        runBlocking { premiumizeService.observeAccountState().first() }
    }.getOrDefault(PremiumizeAccountState())
    coEvery { premiumizeApi.getAccountInfo(any()) } answers {
        val apiKey = firstArg<String>()
        if (premiumizeState.isConnected && apiKey == premiumizeState.apiKey && apiKey.isNotBlank()) {
            Response.success(
                PremiumizeAccountInfoDto(
                    status = "success",
                    customerId = premiumizeState.customerId,
                    premiumUntil = premiumizeState.premiumUntil
                )
            )
        } else {
            Response.error(401, mockk(relaxed = true))
        }
    }

    val torBoxState = runCatching {
        runBlocking { torBoxService.observeAccountState().first() }
    }.getOrDefault(TorBoxAccountState())
    coEvery { torBoxApi.getCurrentUser(any()) } answers {
        val authorization = firstArg<String>()
        val expectedAuthorization = "Bearer ${torBoxState.apiKey}"
        if (torBoxState.isConnected &&
            torBoxState.apiKey.isNotBlank() &&
            authorization == expectedAuthorization
        ) {
            Response.success(
                TorBoxEnvelopeDto(
                    success = true,
                    data = TorBoxUserDto(
                        email = torBoxState.email,
                        plan = torBoxState.plan
                    )
                )
            )
        } else {
            Response.error(401, mockk(relaxed = true))
        }
    }

    val easyDebridState = runCatching {
        runBlocking { easyDebridService.observeAccountState().first() }
    }.getOrDefault(EasyDebridAccountState())
    coEvery { easyDebridApi.getUserDetails(any()) } answers {
        val authorization = firstArg<String>()
        val expectedAuthorization = "Bearer ${easyDebridState.apiKey}"
        if (easyDebridState.isConnected &&
            easyDebridState.apiKey.isNotBlank() &&
            authorization == expectedAuthorization
        ) {
            Response.success(
                EasyDebridUserDto(
                    id = easyDebridState.userId ?: "test-user",
                    paidUntil = easyDebridState.paidUntil
                )
            )
        } else {
            Response.error(401, mockk(relaxed = true))
        }
    }
}
