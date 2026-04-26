package com.nexio.tv.data.repository

import com.google.gson.JsonObject
import com.nexio.tv.core.network.NetworkResult
import com.nexio.tv.data.integration.trakt.TraktIntegrationProvider
import com.nexio.tv.data.local.DebugSettingsDataStore
import com.nexio.tv.data.local.TraktAuthDataStore
import com.nexio.tv.data.local.TraktLibrarySnapshotStore
import com.nexio.tv.data.trakt.outbox.TraktMutationEnvelope
import com.nexio.tv.data.remote.dto.trakt.TraktIdsDto
import com.nexio.tv.data.remote.dto.trakt.TraktListIdsDto
import com.nexio.tv.data.remote.dto.trakt.TraktListItemDto
import com.nexio.tv.data.remote.dto.trakt.TraktListSummaryDto
import com.nexio.tv.data.remote.dto.trakt.TraktMovieDto
import com.nexio.tv.data.remote.dto.trakt.TraktShowDto
import com.nexio.tv.domain.model.LibraryEntry
import com.nexio.tv.domain.model.LibraryEntryInput
import com.nexio.tv.domain.model.LibraryListTab
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.Meta
import com.nexio.tv.domain.model.PosterShape
import com.nexio.tv.domain.repository.MetaRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.slot
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
class TraktLibraryServiceTest {

    @Test
    fun `restored snapshot is returned without observer fetch`() = runTest {
        val traktIntegrationProvider = mockk<com.nexio.tv.data.integration.trakt.TraktIntegrationProvider>()
        val traktAuthService = mockk<TraktAuthService>()
        val traktMutationOutboxCoordinator = mockk<com.nexio.tv.data.trakt.outbox.TraktMutationOutboxCoordinator>(relaxed = true)
        val metaRepository = mockk<MetaRepository>()
        val debugSettingsDataStore = mockk<DebugSettingsDataStore>()
        val traktAuthDataStore = mockk<TraktAuthDataStore>()
        val snapshotStore = mockk<TraktLibrarySnapshotStore>()

        every { debugSettingsDataStore.diskFirstHomeStartupEnabled } returns flowOf(false)
        every { traktAuthDataStore.isEffectivelyAuthenticated } returns flowOf(true)
        every { snapshotStore.read(any()) } returns samplePersistedSnapshot()
        every { metaRepository.getMetaFromAllAddons(any(), any(), any(), any(), any()) } returns
            flowOf(NetworkResult.Error("metadata unavailable"))

        val service = TraktLibraryService(
            traktIntegrationProvider = traktIntegrationProvider,
            traktAuthService = TraktRepositoryAuthGateway(traktAuthService),
            traktMutationOutboxCoordinator = traktMutationOutboxCoordinator,
            metaRepository = metaRepository,
            debugSettingsDataStore = debugSettingsDataStore,
            traktAuthDataStore = traktAuthDataStore,
            snapshotStore = snapshotStore
        )

        advanceUntilIdle()

        assertEquals(
            listOf(TraktLibraryService.WATCHLIST_KEY, "personal:123"),
            service.observeListTabs().first().map { it.key }
        )
        assertEquals(
            listOf("tt1234567", "tmdb:321"),
            service.observeAllItems().first().map { it.id }
        )
        coVerify(exactly = 0) { traktAuthService.executeAuthOwnerRequest<Any?>(any<TrackingAuthSession>(), any()) }
    }

    @Test
    fun `refresh persists renewed snapshot`() = runTest {
        val traktIntegrationProvider = mockk<com.nexio.tv.data.integration.trakt.TraktIntegrationProvider>()
        val traktAuthService = mockk<TraktAuthService>()
        val traktMutationOutboxCoordinator = mockk<com.nexio.tv.data.trakt.outbox.TraktMutationOutboxCoordinator>(relaxed = true)
        val metaRepository = mockk<MetaRepository>()
        val debugSettingsDataStore = mockk<DebugSettingsDataStore>()
        val traktAuthState = MutableStateFlow(true)
        val traktAuthDataStore = mockk<TraktAuthDataStore>()
        val snapshotStore = mockk<TraktLibrarySnapshotStore>(relaxed = true)

        every { debugSettingsDataStore.diskFirstHomeStartupEnabled } returns flowOf(false)
        every { traktAuthDataStore.isEffectivelyAuthenticated } returns traktAuthState
        every { snapshotStore.read(any()) } returns null

        stubLibraryRefresh(
            traktIntegrationProvider,
            listOf(
            successResponse(
                listOf(
                    TraktListItemDto(
                        rank = 1,
                        listedAt = "2026-03-30T12:00:00Z",
                        type = "movie",
                        movie = TraktMovieDto(
                            title = "Watchlist Movie",
                            year = 2024,
                            ids = TraktIdsDto(imdb = "tt1234567", trakt = 10)
                        )
                    )
                )
            ),
            successResponse(emptyList<TraktListItemDto>()),
            successResponse(emptyList<TraktListSummaryDto>())
            )
        )
        every { metaRepository.getMetaFromAllAddons(any(), any(), any(), any(), any()) } answers {
            val type = firstArg<String>()
            val id = secondArg<String>()
            flowOf(NetworkResult.Success(meta(id = id, type = type, name = "hydrated-$id", poster = "poster-$id", background = "background-$id", logo = "logo-$id")))
        }

        val service = TraktLibraryService(
            traktIntegrationProvider = traktIntegrationProvider,
            traktAuthService = TraktRepositoryAuthGateway(traktAuthService),
            traktMutationOutboxCoordinator = traktMutationOutboxCoordinator,
            metaRepository = metaRepository,
            debugSettingsDataStore = debugSettingsDataStore,
            traktAuthDataStore = traktAuthDataStore,
            snapshotStore = snapshotStore
        )

        advanceUntilIdle()
        service.refreshNow()
        advanceUntilIdle()

        verify(atLeast = 1) { snapshotStore.write(any(), any()) }
        assertEquals(listOf(TraktLibraryService.WATCHLIST_KEY), service.observeListTabs().first().map { it.key })
    }

    @Test
    fun `first uncached refresh stays empty until disk snapshot is written`() = runTest {
        val traktIntegrationProvider = mockk<com.nexio.tv.data.integration.trakt.TraktIntegrationProvider>()
        val traktAuthService = mockk<TraktAuthService>()
        val traktMutationOutboxCoordinator = mockk<com.nexio.tv.data.trakt.outbox.TraktMutationOutboxCoordinator>(relaxed = true)
        val metaRepository = mockk<MetaRepository>()
        val debugSettingsDataStore = mockk<DebugSettingsDataStore>()
        val traktAuthState = MutableStateFlow(true)
        val traktAuthDataStore = mockk<TraktAuthDataStore>()
        val snapshotStore = mockk<TraktLibrarySnapshotStore>()
        var persistedSnapshot: TraktLibrarySnapshotStore.Snapshot? = null
        lateinit var service: TraktLibraryService

        every { debugSettingsDataStore.diskFirstHomeStartupEnabled } returns flowOf(false)
        every { traktAuthDataStore.isEffectivelyAuthenticated } returns traktAuthState
        every { snapshotStore.read(any()) } answers { persistedSnapshot }
        every { snapshotStore.write(any(), any()) } answers {
            assertTrue(runBlocking { service.observeListTabs().first().isEmpty() })
            assertTrue(runBlocking { service.observeAllItems().first().isEmpty() })
            assertTrue(runBlocking { service.observeHasCache().first().not() })
            persistedSnapshot = firstArg()
        }

        stubLibraryRefresh(
            traktIntegrationProvider,
            listOf(
            successResponse(
                listOf(
                    TraktListItemDto(
                        rank = 1,
                        listedAt = "2026-03-30T12:00:00Z",
                        type = "movie",
                        movie = TraktMovieDto(
                            title = "Watchlist Movie",
                            year = 2024,
                            ids = TraktIdsDto(imdb = "tt1234567", trakt = 10)
                        )
                    )
                )
            ),
            successResponse(emptyList<TraktListItemDto>()),
            successResponse(emptyList<TraktListSummaryDto>())
            )
        )
        every { metaRepository.getMetaFromAllAddons(any(), any(), any(), any(), any()) } returns
            flowOf(NetworkResult.Error("metadata unavailable"))

        service = TraktLibraryService(
            traktIntegrationProvider = traktIntegrationProvider,
            traktAuthService = TraktRepositoryAuthGateway(traktAuthService),
            traktMutationOutboxCoordinator = traktMutationOutboxCoordinator,
            metaRepository = metaRepository,
            debugSettingsDataStore = debugSettingsDataStore,
            traktAuthDataStore = traktAuthDataStore,
            snapshotStore = snapshotStore
        )

        advanceUntilIdle()
        assertTrue(service.observeListTabs().first().isEmpty())

        service.refreshNow()
        advanceUntilIdle()

        assertTrue(service.observeHasCache().first())
        assertEquals(listOf(TraktLibraryService.WATCHLIST_KEY), service.observeListTabs().first().map { it.key })
    }

    @Test
    fun `warm cache refresh failure preserves restored snapshot`() = runTest {
        val traktIntegrationProvider = mockk<com.nexio.tv.data.integration.trakt.TraktIntegrationProvider>()
        val traktAuthService = mockk<TraktAuthService>()
        val traktMutationOutboxCoordinator = mockk<com.nexio.tv.data.trakt.outbox.TraktMutationOutboxCoordinator>(relaxed = true)
        val metaRepository = mockk<MetaRepository>()
        val debugSettingsDataStore = mockk<DebugSettingsDataStore>()
        val traktAuthState = MutableStateFlow(true)
        val traktAuthDataStore = mockk<TraktAuthDataStore>()
        val snapshotStore = mockk<TraktLibrarySnapshotStore>(relaxed = true)

        every { debugSettingsDataStore.diskFirstHomeStartupEnabled } returns flowOf(false)
        every { traktAuthDataStore.isEffectivelyAuthenticated } returns traktAuthState
        every { snapshotStore.read(any()) } returns samplePersistedSnapshot()
        every { metaRepository.getMetaFromAllAddons(any(), any(), any(), any(), any()) } returns
            flowOf(NetworkResult.Error("metadata unavailable"))
        stubLibraryRefreshFailure(traktIntegrationProvider)

        val service = TraktLibraryService(
            traktIntegrationProvider = traktIntegrationProvider,
            traktAuthService = TraktRepositoryAuthGateway(traktAuthService),
            traktMutationOutboxCoordinator = traktMutationOutboxCoordinator,
            metaRepository = metaRepository,
            debugSettingsDataStore = debugSettingsDataStore,
            traktAuthDataStore = traktAuthDataStore,
            snapshotStore = snapshotStore
        )

        advanceUntilIdle()
        service.refreshNow()
        advanceUntilIdle()

        val items = service.observeAllItems().first()
        assertEquals(listOf("tt1234567", "tmdb:321"), items.map { it.id })
        assertTrue(items.first().poster == "https://image.test/watchlist/poster.jpg")
    }

    @Test
    fun `create personal list persists provisional tab before Trakt settlement`() = runTest {
        val traktIntegrationProvider = mockk<com.nexio.tv.data.integration.trakt.TraktIntegrationProvider>()
        val traktAuthService = mockk<TraktAuthService>(relaxed = true)
        val traktMutationOutboxCoordinator = mockk<com.nexio.tv.data.trakt.outbox.TraktMutationOutboxCoordinator>()
        val metaRepository = mockk<MetaRepository>(relaxed = true)
        val debugSettingsDataStore = mockk<DebugSettingsDataStore>()
        val traktAuthDataStore = mockk<TraktAuthDataStore>()
        val snapshotStore = mockk<TraktLibrarySnapshotStore>(relaxed = true)
        var persistedSnapshot: TraktLibrarySnapshotStore.Snapshot? = samplePersistedSnapshot()

        every { debugSettingsDataStore.diskFirstHomeStartupEnabled } returns flowOf(false)
        every { traktAuthDataStore.isEffectivelyAuthenticated } returns flowOf(true)
        every { snapshotStore.read(any()) } answers { persistedSnapshot }
        every { snapshotStore.write(any(), any()) } answers { persistedSnapshot = firstArg() }
        coEvery { traktMutationOutboxCoordinator.enqueueAndDrain(any()) } answers { firstArg() }

        val service = TraktLibraryService(
            traktIntegrationProvider = traktIntegrationProvider,
            traktAuthService = TraktRepositoryAuthGateway(traktAuthService),
            traktMutationOutboxCoordinator = traktMutationOutboxCoordinator,
            metaRepository = metaRepository,
            debugSettingsDataStore = debugSettingsDataStore,
            traktAuthDataStore = traktAuthDataStore,
            snapshotStore = snapshotStore
        )

        advanceUntilIdle()
        service.createPersonalList(
            name = "Queued List",
            description = "stale-safe",
            privacy = com.nexio.tv.domain.model.TraktListPrivacy.PRIVATE
        )
        advanceUntilIdle()

        val tabs = service.observeListTabs().first()
        assertTrue(tabs.any { it.key.startsWith("personal:pending:") && it.title == "Queued List" })
        verify(atLeast = 1) { snapshotStore.write(any(), any()) }
        coVerify(exactly = 1) { traktMutationOutboxCoordinator.enqueueAndDrain(any()) }
        coVerify(exactly = 0) { traktAuthService.executeAuthOwnerRequest<Any?>(any<TrackingAuthSession>(), any()) }
    }

    @Test
    fun `create personal list success reconciles provisional tab in place`() = runTest {
        val traktIntegrationProvider = mockk<com.nexio.tv.data.integration.trakt.TraktIntegrationProvider>()
        val traktAuthService = mockk<TraktAuthService>(relaxed = true)
        val traktMutationOutboxCoordinator = mockk<com.nexio.tv.data.trakt.outbox.TraktMutationOutboxCoordinator>()
        val metaRepository = mockk<MetaRepository>(relaxed = true)
        val debugSettingsDataStore = mockk<DebugSettingsDataStore>()
        val traktAuthDataStore = mockk<TraktAuthDataStore>()
        val snapshotStore = mockk<TraktLibrarySnapshotStore>(relaxed = true)
        var persistedSnapshot: TraktLibrarySnapshotStore.Snapshot? = samplePersistedSnapshot()

        every { debugSettingsDataStore.diskFirstHomeStartupEnabled } returns flowOf(false)
        every { traktAuthDataStore.isEffectivelyAuthenticated } returns flowOf(true)
        every { snapshotStore.read(any()) } answers { persistedSnapshot }
        every { snapshotStore.write(any(), any()) } answers { persistedSnapshot = firstArg() }
        coEvery { traktMutationOutboxCoordinator.enqueueAndDrain(any()) } answers { firstArg() }

        val service = TraktLibraryService(
            traktIntegrationProvider = traktIntegrationProvider,
            traktAuthService = TraktRepositoryAuthGateway(traktAuthService),
            traktMutationOutboxCoordinator = traktMutationOutboxCoordinator,
            metaRepository = metaRepository,
            debugSettingsDataStore = debugSettingsDataStore,
            traktAuthDataStore = traktAuthDataStore,
            snapshotStore = snapshotStore
        )

        advanceUntilIdle()
        service.createPersonalList(
            name = "Queued List",
            description = "desc",
            privacy = com.nexio.tv.domain.model.TraktListPrivacy.PUBLIC
        )
        advanceUntilIdle()

        val provisionalKey = service.observeListTabs().first().first { it.key.startsWith("personal:pending:") }.key
        service.reconcileQueuedCreateListSuccess(
            provisionalKey = provisionalKey,
            createdList = TraktListSummaryDto(
                name = "Queued List",
                description = "desc",
                privacy = "public",
                type = "personal",
                ids = TraktListIdsDto(trakt = 999L, slug = "queued-list")
            )
        )
        advanceUntilIdle()

        val tabs = service.observeListTabs().first()
        assertTrue(tabs.none { it.key == provisionalKey })
        assertEquals(1, tabs.count { it.key == "personal:999" })
        assertTrue(tabs.any { it.key == "personal:999" && it.slug == "queued-list" })
    }

    @Test
    fun `targeted rollback restores watchlist slice without removing unrelated provisional list`() = runTest {
        val traktIntegrationProvider = mockk<com.nexio.tv.data.integration.trakt.TraktIntegrationProvider>()
        val traktAuthService = mockk<TraktAuthService>(relaxed = true)
        val traktMutationOutboxCoordinator = mockk<com.nexio.tv.data.trakt.outbox.TraktMutationOutboxCoordinator>()
        val metaRepository = mockk<MetaRepository>(relaxed = true)
        val debugSettingsDataStore = mockk<DebugSettingsDataStore>()
        val traktAuthDataStore = mockk<TraktAuthDataStore>()
        val snapshotStore = mockk<TraktLibrarySnapshotStore>(relaxed = true)
        var persistedSnapshot: TraktLibrarySnapshotStore.Snapshot? = samplePersistedSnapshot()

        every { debugSettingsDataStore.diskFirstHomeStartupEnabled } returns flowOf(false)
        every { traktAuthDataStore.isEffectivelyAuthenticated } returns flowOf(true)
        every { snapshotStore.read(any()) } answers { persistedSnapshot }
        every { snapshotStore.write(any(), any()) } answers { persistedSnapshot = firstArg() }
        coEvery { traktMutationOutboxCoordinator.enqueueAndDrain(any()) } answers { firstArg() }

        val service = TraktLibraryService(
            traktIntegrationProvider = traktIntegrationProvider,
            traktAuthService = TraktRepositoryAuthGateway(traktAuthService),
            traktMutationOutboxCoordinator = traktMutationOutboxCoordinator,
            metaRepository = metaRepository,
            debugSettingsDataStore = debugSettingsDataStore,
            traktAuthDataStore = traktAuthDataStore,
            snapshotStore = snapshotStore
        )

        advanceUntilIdle()
        service.createPersonalList(
            name = "Queued List",
            description = null,
            privacy = com.nexio.tv.domain.model.TraktListPrivacy.PRIVATE
        )
        service.toggleWatchlist(sampleLibraryInput())
        advanceUntilIdle()

        val provisionalKey = service.observeListTabs().first().first { it.key.startsWith("personal:pending:") }.key
        assertTrue(service.observeAllItems().first().none { it.id == "tt1234567" })

        service.rollbackQueuedLibraryMutation(
            rollbackState = TraktLibraryService.LibraryRollbackState(
                entriesByList = mapOf(
                    TraktLibraryService.WATCHLIST_KEY to samplePersistedSnapshot().entriesByList.getValue(TraktLibraryService.WATCHLIST_KEY)
                )
            )
        )
        advanceUntilIdle()

        val tabs = service.observeListTabs().first()
        val items = service.observeAllItems().first()
        assertTrue(tabs.any { it.key == provisionalKey })
        assertTrue(items.any { it.id == "tt1234567" })
    }

    @Test
    fun `toggle watchlist stores list-scoped rollback payload`() = runTest {
        val traktIntegrationProvider = mockk<com.nexio.tv.data.integration.trakt.TraktIntegrationProvider>()
        val traktAuthService = mockk<TraktAuthService>(relaxed = true)
        val traktMutationOutboxCoordinator = mockk<com.nexio.tv.data.trakt.outbox.TraktMutationOutboxCoordinator>()
        val metaRepository = mockk<MetaRepository>(relaxed = true)
        val debugSettingsDataStore = mockk<DebugSettingsDataStore>()
        val traktAuthDataStore = mockk<TraktAuthDataStore>()
        val snapshotStore = mockk<TraktLibrarySnapshotStore>(relaxed = true)
        var persistedSnapshot: TraktLibrarySnapshotStore.Snapshot? = samplePersistedSnapshot()
        val envelopeSlot = slot<TraktMutationEnvelope>()

        every { debugSettingsDataStore.diskFirstHomeStartupEnabled } returns flowOf(false)
        every { traktAuthDataStore.isEffectivelyAuthenticated } returns flowOf(true)
        every { snapshotStore.read(any()) } answers { persistedSnapshot }
        every { snapshotStore.write(any(), any()) } answers { persistedSnapshot = firstArg() }
        coEvery { traktMutationOutboxCoordinator.enqueueAndDrain(capture(envelopeSlot)) } answers { envelopeSlot.captured }

        val service = TraktLibraryService(
            traktIntegrationProvider = traktIntegrationProvider,
            traktAuthService = TraktRepositoryAuthGateway(traktAuthService),
            traktMutationOutboxCoordinator = traktMutationOutboxCoordinator,
            metaRepository = metaRepository,
            debugSettingsDataStore = debugSettingsDataStore,
            traktAuthDataStore = traktAuthDataStore,
            snapshotStore = snapshotStore
        )

        advanceUntilIdle()
        service.toggleWatchlist(sampleLibraryInput())
        advanceUntilIdle()

        val rollbackPayload = envelopeSlot.captured.rollbackPayload ?: JsonObject()
        assertEquals(false, rollbackPayload.get("replaceAll")?.asBoolean ?: false)
        assertTrue(rollbackPayload.getAsJsonObject("entriesByList").has(TraktLibraryService.WATCHLIST_KEY))
        assertEquals(
            1,
            rollbackPayload.getAsJsonObject("entriesByList")
                .getAsJsonArray(TraktLibraryService.WATCHLIST_KEY)
                .size()
        )
    }

    @Test
    fun `rollback queued library mutation restores affected list slice without blanking snapshot`() = runTest {
        val traktIntegrationProvider = mockk<com.nexio.tv.data.integration.trakt.TraktIntegrationProvider>()
        val traktAuthService = mockk<TraktAuthService>(relaxed = true)
        val traktMutationOutboxCoordinator = mockk<com.nexio.tv.data.trakt.outbox.TraktMutationOutboxCoordinator>(relaxed = true)
        val metaRepository = mockk<MetaRepository>(relaxed = true)
        val debugSettingsDataStore = mockk<DebugSettingsDataStore>()
        val traktAuthDataStore = mockk<TraktAuthDataStore>()
        val snapshotStore = mockk<TraktLibrarySnapshotStore>(relaxed = true)
        var persistedSnapshot: TraktLibrarySnapshotStore.Snapshot? = samplePersistedSnapshot()

        every { debugSettingsDataStore.diskFirstHomeStartupEnabled } returns flowOf(false)
        every { traktAuthDataStore.isEffectivelyAuthenticated } returns flowOf(true)
        every { snapshotStore.read(any()) } answers { persistedSnapshot }
        every { snapshotStore.write(any(), any()) } answers { persistedSnapshot = firstArg() }
        stubLibraryRefreshFailure(traktIntegrationProvider)

        val service = TraktLibraryService(
            traktIntegrationProvider = traktIntegrationProvider,
            traktAuthService = TraktRepositoryAuthGateway(traktAuthService),
            traktMutationOutboxCoordinator = traktMutationOutboxCoordinator,
            metaRepository = metaRepository,
            debugSettingsDataStore = debugSettingsDataStore,
            traktAuthDataStore = traktAuthDataStore,
            snapshotStore = snapshotStore
        )
        advanceUntilIdle()

        service.rollbackQueuedLibraryMutation(
            rollbackState = TraktLibraryService.LibraryRollbackState(
                listTabs = samplePersistedSnapshot().listTabs,
                entriesByList = samplePersistedSnapshot().entriesByList
            )
        )
        advanceUntilIdle()

        val tabs = service.observeListTabs().first()
        val items = service.observeAllItems().first()
        assertEquals(listOf(TraktLibraryService.WATCHLIST_KEY, "personal:123"), tabs.map { it.key })
        assertEquals(listOf("tt1234567", "tmdb:321"), items.map { it.id })
        assertTrue(items.none { it.id == "trakt:999" })
    }

    @Test
    fun `warm refresh keeps showing persisted cache until replacement snapshot is written`() = runTest {
        val traktIntegrationProvider = mockk<com.nexio.tv.data.integration.trakt.TraktIntegrationProvider>()
        val traktAuthService = mockk<TraktAuthService>()
        val traktMutationOutboxCoordinator = mockk<com.nexio.tv.data.trakt.outbox.TraktMutationOutboxCoordinator>(relaxed = true)
        val metaRepository = mockk<MetaRepository>()
        val debugSettingsDataStore = mockk<DebugSettingsDataStore>()
        val traktAuthState = MutableStateFlow(true)
        val traktAuthDataStore = mockk<TraktAuthDataStore>()
        val snapshotStore = mockk<TraktLibrarySnapshotStore>()
        var persistedSnapshot: TraktLibrarySnapshotStore.Snapshot? = samplePersistedSnapshot()
        lateinit var service: TraktLibraryService

        every { debugSettingsDataStore.diskFirstHomeStartupEnabled } returns flowOf(false)
        every { traktAuthDataStore.isEffectivelyAuthenticated } returns traktAuthState
        every { snapshotStore.read(any()) } answers { persistedSnapshot }
        every { snapshotStore.write(any(), any()) } answers {
            assertEquals(
                listOf("tt1234567", "tmdb:321"),
                runBlocking { service.observeAllItems().first().map { it.id } }
            )
            persistedSnapshot = firstArg()
        }
        every { metaRepository.getMetaFromAllAddons(any(), any(), any(), any(), any()) } returns
            flowOf(NetworkResult.Error("metadata unavailable"))
        stubLibraryRefresh(
            traktIntegrationProvider,
            listOf(
            successResponse(
                listOf(
                    TraktListItemDto(
                        rank = 1,
                        listedAt = "2026-04-01T12:00:00Z",
                        type = "movie",
                        movie = TraktMovieDto(
                            title = "Replacement Movie",
                            year = 2025,
                            ids = TraktIdsDto(imdb = "tt7654321", trakt = 20)
                        )
                    )
                )
            ),
            successResponse(emptyList<TraktListItemDto>()),
            successResponse(emptyList<TraktListSummaryDto>())
            )
        )

        service = TraktLibraryService(
            traktIntegrationProvider = traktIntegrationProvider,
            traktAuthService = TraktRepositoryAuthGateway(traktAuthService),
            traktMutationOutboxCoordinator = traktMutationOutboxCoordinator,
            metaRepository = metaRepository,
            debugSettingsDataStore = debugSettingsDataStore,
            traktAuthDataStore = traktAuthDataStore,
            snapshotStore = snapshotStore
        )

        advanceUntilIdle()
        assertEquals(listOf("tt1234567", "tmdb:321"), service.observeAllItems().first().map { it.id })

        service.refreshNow()
        advanceUntilIdle()

        assertEquals(listOf("tt7654321"), service.observeAllItems().first().map { it.id })
        assertEquals(listOf(TraktLibraryService.WATCHLIST_KEY), service.observeListTabs().first().map { it.key })
    }

    @Test
    fun `auth loss preserves restored snapshot and persisted cache`() = runTest {
        val traktIntegrationProvider = mockk<com.nexio.tv.data.integration.trakt.TraktIntegrationProvider>()
        val traktAuthService = mockk<TraktAuthService>()
        val traktMutationOutboxCoordinator = mockk<com.nexio.tv.data.trakt.outbox.TraktMutationOutboxCoordinator>(relaxed = true)
        val metaRepository = mockk<MetaRepository>()
        val debugSettingsDataStore = mockk<DebugSettingsDataStore>()
        val traktAuthState = MutableStateFlow(true)
        val traktAuthDataStore = mockk<TraktAuthDataStore>()
        val snapshotStore = mockk<TraktLibrarySnapshotStore>(relaxed = true)

        every { debugSettingsDataStore.diskFirstHomeStartupEnabled } returns flowOf(false)
        every { traktAuthDataStore.isEffectivelyAuthenticated } returns traktAuthState
        every { snapshotStore.read(any()) } returns samplePersistedSnapshot()
        every { metaRepository.getMetaFromAllAddons(any(), any(), any(), any(), any()) } returns flowOf(NetworkResult.Loading)

        val service = TraktLibraryService(
            traktIntegrationProvider = traktIntegrationProvider,
            traktAuthService = TraktRepositoryAuthGateway(traktAuthService),
            traktMutationOutboxCoordinator = traktMutationOutboxCoordinator,
            metaRepository = metaRepository,
            debugSettingsDataStore = debugSettingsDataStore,
            traktAuthDataStore = traktAuthDataStore,
            snapshotStore = snapshotStore
        )

        advanceUntilIdle()
        assertTrue(service.observeHasCache().first())

        traktAuthState.value = false
        advanceUntilIdle()

        assertEquals(
            listOf(TraktLibraryService.WATCHLIST_KEY, "personal:123"),
            service.observeListTabs().first().map { it.key }
        )
        assertEquals(
            listOf("tt1234567", "tmdb:321"),
            service.observeAllItems().first().map { it.id }
        )
        assertTrue(service.observeHasCache().first())
        verify(exactly = 0) { snapshotStore.clear(any()) }
    }

    @Test
    fun `startup unauthenticated emission does not wipe restored snapshot before auth settles`() = runTest {
        val traktIntegrationProvider = mockk<com.nexio.tv.data.integration.trakt.TraktIntegrationProvider>()
        val traktAuthService = mockk<TraktAuthService>()
        val traktMutationOutboxCoordinator = mockk<com.nexio.tv.data.trakt.outbox.TraktMutationOutboxCoordinator>(relaxed = true)
        val metaRepository = mockk<MetaRepository>()
        val debugSettingsDataStore = mockk<DebugSettingsDataStore>()
        val traktAuthState = MutableStateFlow(false)
        val traktAuthDataStore = mockk<TraktAuthDataStore>()
        val snapshotStore = mockk<TraktLibrarySnapshotStore>(relaxed = true)

        every { debugSettingsDataStore.diskFirstHomeStartupEnabled } returns flowOf(false)
        every { traktAuthDataStore.isEffectivelyAuthenticated } returns traktAuthState
        every { snapshotStore.read(any()) } returns samplePersistedSnapshot()
        every { metaRepository.getMetaFromAllAddons(any(), any(), any(), any(), any()) } returns flowOf(NetworkResult.Loading)

        val service = TraktLibraryService(
            traktIntegrationProvider = traktIntegrationProvider,
            traktAuthService = TraktRepositoryAuthGateway(traktAuthService),
            traktMutationOutboxCoordinator = traktMutationOutboxCoordinator,
            metaRepository = metaRepository,
            debugSettingsDataStore = debugSettingsDataStore,
            traktAuthDataStore = traktAuthDataStore,
            snapshotStore = snapshotStore
        )

        advanceUntilIdle()

        assertTrue(service.observeHasCache().first())
        assertEquals(
            listOf(TraktLibraryService.WATCHLIST_KEY, "personal:123"),
            service.observeListTabs().first().map { it.key }
        )
        verify(exactly = 0) { snapshotStore.clear(any()) }

        traktAuthState.value = true
        advanceUntilIdle()

        assertTrue(service.observeHasCache().first())
        assertEquals(
            listOf("tt1234567", "tmdb:321"),
            service.observeAllItems().first().map { it.id }
        )
        coVerify(exactly = 0) { traktAuthService.executeAuthOwnerRequest<Any?>(any<TrackingAuthSession>(), any()) }
    }

    @Test
    fun `refresh keeps custom lists and hydrates artwork for watchlist and custom list items`() = runTest {
        val traktIntegrationProvider = mockk<com.nexio.tv.data.integration.trakt.TraktIntegrationProvider>()
        val traktAuthService = mockk<TraktAuthService>()
        val traktMutationOutboxCoordinator = mockk<com.nexio.tv.data.trakt.outbox.TraktMutationOutboxCoordinator>(relaxed = true)
        val metaRepository = mockk<MetaRepository>()
        val debugSettingsDataStore = mockk<DebugSettingsDataStore>()
        val traktAuthState = MutableStateFlow(true)
        val traktAuthDataStore = mockk<TraktAuthDataStore>()
        val snapshotStore = mockk<TraktLibrarySnapshotStore>(relaxed = true)

        every { debugSettingsDataStore.diskFirstHomeStartupEnabled } returns flowOf(false)
        every { traktAuthDataStore.isEffectivelyAuthenticated } returns traktAuthState
        every { snapshotStore.read(any()) } returns null

        stubLibraryRefresh(
            traktIntegrationProvider,
            listOf(
            successResponse(
                listOf(
                    TraktListItemDto(
                        rank = 1,
                        listedAt = "2026-03-30T12:00:00Z",
                        type = "movie",
                        movie = TraktMovieDto(
                            title = "Watchlist Movie",
                            year = 2024,
                            ids = TraktIdsDto(imdb = "tt1234567", trakt = 10)
                        )
                    )
                )
            ),
            successResponse(emptyList<TraktListItemDto>()),
            successResponse(
                listOf(
                    TraktListSummaryDto(
                        name = "My Custom List",
                        type = "personal",
                        ids = TraktListIdsDto(trakt = 123L, slug = "my-custom-list")
                    )
                )
            ),
            successResponse(emptyList<TraktListItemDto>()),
            successResponse(
                listOf(
                    TraktListItemDto(
                        rank = 1,
                        listedAt = "2026-03-29T12:00:00Z",
                        type = "show",
                        show = TraktShowDto(
                            title = "Custom List Show",
                            year = 2023,
                            ids = TraktIdsDto(tmdb = 321, trakt = 11)
                        )
                    )
                )
            )
            )
        )

        every { metaRepository.getMetaFromAllAddons(any(), any(), any(), any(), any()) } answers {
            val type = firstArg<String>()
            val id = secondArg<String>()
            flowOf(
                NetworkResult.Success(
                    meta(
                        id = id,
                        type = type,
                        name = "hydrated-$id",
                        poster = "https://image.test/$id/poster.jpg",
                        background = "https://image.test/$id/background.jpg",
                        logo = "https://image.test/$id/logo.png"
                    )
                )
            )
        }

        val service = TraktLibraryService(
            traktIntegrationProvider = traktIntegrationProvider,
            traktAuthService = TraktRepositoryAuthGateway(traktAuthService),
            traktMutationOutboxCoordinator = traktMutationOutboxCoordinator,
            metaRepository = metaRepository,
            debugSettingsDataStore = debugSettingsDataStore,
            traktAuthDataStore = traktAuthDataStore,
            snapshotStore = snapshotStore
        )

        advanceUntilIdle()
        service.refreshNow()
        advanceUntilIdle()

        coVerify(exactly = 2) { traktIntegrationProvider.getWatchlist(any(), any()) }
        coVerify(exactly = 1) { traktIntegrationProvider.getUserLists(any(), any()) }
        coVerify(exactly = 2) { traktIntegrationProvider.getUserListItems(any(), any(), any(), any()) }

        val tabs = service.observeListTabs().first()
        val items = service.observeAllItems().first()

        assertEquals(listOf(TraktLibraryService.WATCHLIST_KEY, "personal:123"), tabs.map { it.key })

        val watchlistItem = items.first { it.listKeys.contains(TraktLibraryService.WATCHLIST_KEY) }
        assertEquals("tt1234567", watchlistItem.id)
        assertNotNull(watchlistItem.poster)
        assertNotNull(watchlistItem.background)
        assertNotNull(watchlistItem.logo)

        val customListItem = items.first { it.listKeys.contains("personal:123") }
        assertEquals("tmdb:321", customListItem.id)
        assertNotNull(customListItem.poster)
        assertNotNull(customListItem.background)
        assertNotNull(customListItem.logo)
    }

    @Test
    fun `refresh hydrates metadata when trakt ids are the only stable ids`() = runTest {
        val traktIntegrationProvider = mockk<com.nexio.tv.data.integration.trakt.TraktIntegrationProvider>()
        val traktAuthService = mockk<TraktAuthService>()
        val traktMutationOutboxCoordinator = mockk<com.nexio.tv.data.trakt.outbox.TraktMutationOutboxCoordinator>(relaxed = true)
        val metaRepository = mockk<MetaRepository>()
        val debugSettingsDataStore = mockk<DebugSettingsDataStore>()
        val traktAuthState = MutableStateFlow(true)
        val traktAuthDataStore = mockk<TraktAuthDataStore>()
        val snapshotStore = mockk<TraktLibrarySnapshotStore>(relaxed = true)

        every { debugSettingsDataStore.diskFirstHomeStartupEnabled } returns flowOf(false)
        every { traktAuthDataStore.isEffectivelyAuthenticated } returns traktAuthState
        every { snapshotStore.read(any()) } returns null

        stubLibraryRefresh(
            traktIntegrationProvider,
            listOf(
            successResponse(
                listOf(
                    TraktListItemDto(
                        rank = 1,
                        listedAt = "2026-03-30T12:00:00Z",
                        type = "movie",
                        movie = TraktMovieDto(
                            title = "Trakt Only Movie",
                            year = 2025,
                            ids = TraktIdsDto(trakt = 999)
                        )
                    )
                )
            ),
            successResponse(emptyList<TraktListItemDto>()),
            successResponse(emptyList<TraktListSummaryDto>())
            )
        )

        every { metaRepository.getMetaFromAllAddons(any(), any(), any(), any(), any()) } answers {
            val type = firstArg<String>()
            val id = secondArg<String>()
            flowOf(
                NetworkResult.Success(
                    meta(
                        id = id,
                        type = type,
                        name = "hydrated-$id",
                        poster = "https://image.test/$id/poster.jpg",
                        background = "https://image.test/$id/background.jpg",
                        logo = "https://image.test/$id/logo.png"
                    )
                )
            )
        }

        val service = TraktLibraryService(
            traktIntegrationProvider = traktIntegrationProvider,
            traktAuthService = TraktRepositoryAuthGateway(traktAuthService),
            traktMutationOutboxCoordinator = traktMutationOutboxCoordinator,
            metaRepository = metaRepository,
            debugSettingsDataStore = debugSettingsDataStore,
            traktAuthDataStore = traktAuthDataStore,
            snapshotStore = snapshotStore
        )

        advanceUntilIdle()
        service.refreshNow()
        advanceUntilIdle()

        coVerify(exactly = 2) { traktIntegrationProvider.getWatchlist(any(), any()) }
        coVerify(exactly = 1) { traktIntegrationProvider.getUserLists(any(), any()) }

        val item = service.observeAllItems().first().single()

        assertEquals("trakt:999", item.id)
        assertNotNull(item.poster)
        assertNotNull(item.background)
        assertNotNull(item.logo)
    }

    private fun meta(
        id: String,
        type: String,
        name: String,
        poster: String,
        background: String,
        logo: String
    ): Meta {
        val contentType = if (type == "movie") ContentType.MOVIE else ContentType.SERIES
        return Meta(
            id = id,
            type = contentType,
            rawType = type,
            name = name,
            poster = poster,
            posterShape = PosterShape.POSTER,
            background = background,
            logo = logo,
            description = "description-$id",
            releaseInfo = "2024",
            imdbRating = 8.4f,
            genres = listOf("Drama"),
            runtime = null,
            director = emptyList(),
            writer = emptyList(),
            cast = emptyList(),
            castMembers = emptyList(),
            videos = emptyList(),
            productionCompanies = emptyList(),
            networks = emptyList(),
            country = null,
            awards = null,
            language = null,
            links = emptyList(),
            trailerYtIds = emptyList()
        )
    }

    private fun samplePersistedSnapshot(): TraktLibrarySnapshotStore.Snapshot {
        val watchlistItem = LibraryEntry(
            id = "tt1234567",
            type = "movie",
            name = "tt1234567",
            poster = null,
            posterShape = PosterShape.POSTER,
            background = null,
            logo = null,
            description = null,
            releaseInfo = null,
            imdbRating = null,
            genres = emptyList(),
            addonBaseUrl = null,
            listKeys = setOf(TraktLibraryService.WATCHLIST_KEY),
            listedAt = 100L,
            traktRank = 1,
            imdbId = "tt1234567",
            traktId = 10
        )
        val personalItem = LibraryEntry(
            id = "tmdb:321",
            type = "series",
            name = "tmdb:321",
            poster = null,
            posterShape = PosterShape.POSTER,
            background = null,
            logo = null,
            description = null,
            releaseInfo = null,
            imdbRating = null,
            genres = emptyList(),
            addonBaseUrl = null,
            listKeys = setOf("personal:123"),
            listedAt = 90L,
            traktRank = 1,
            tmdbId = 321,
            traktId = 11
        )
        return TraktLibrarySnapshotStore.Snapshot(
            listTabs = listOf(
                LibraryListTab(
                    key = TraktLibraryService.WATCHLIST_KEY,
                    title = "Watchlist",
                    type = LibraryListTab.Type.WATCHLIST,
                    sortBy = "rank",
                    sortHow = "asc"
                ),
                LibraryListTab(
                    key = "personal:123",
                    title = "My List",
                    type = LibraryListTab.Type.PERSONAL,
                    traktListId = 123L,
                    slug = "my-list"
                )
            ),
            entriesByList = linkedMapOf(
                TraktLibraryService.WATCHLIST_KEY to listOf(watchlistItem),
                "personal:123" to listOf(personalItem)
            ),
            metadataByContentKey = mapOf(
                "movie:tt1234567" to TraktLibrarySnapshotStore.PersistedLibraryMetadata(
                    name = "Hydrated Watchlist Movie",
                    poster = "https://image.test/watchlist/poster.jpg",
                    background = "https://image.test/watchlist/background.jpg",
                    logo = "https://image.test/watchlist/logo.png",
                    description = "Hydrated watchlist description",
                    releaseInfo = "2024",
                    imdbRating = 8.5f,
                    genres = listOf("Drama")
                ),
                "series:tmdb:321" to TraktLibrarySnapshotStore.PersistedLibraryMetadata(
                    name = "Hydrated Custom List Show",
                    poster = "https://image.test/custom/poster.jpg",
                    background = "https://image.test/custom/background.jpg",
                    logo = "https://image.test/custom/logo.png",
                    description = "Hydrated custom list description",
                    releaseInfo = "2023",
                    imdbRating = 8.1f,
                    genres = listOf("Sci-Fi")
                )
            ),
            updatedAtMs = 4321L
        )
    }

    @Suppress("unused")
    private fun sampleLibraryInput(): LibraryEntryInput {
        return LibraryEntryInput(
            itemId = "tt1234567",
            itemType = "movie",
            title = "Watchlist Movie",
            traktId = 10,
            imdbId = "tt1234567"
        )
    }

    private suspend fun waitUntil(condition: suspend () -> Boolean) {
        val deadline = System.currentTimeMillis() + 1_000L
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(10L)
        }
        throw AssertionError("Condition not met before timeout")
    }

    @Suppress("UNCHECKED_CAST")
    private fun stubLibraryRefresh(
        provider: TraktIntegrationProvider,
        responses: List<Response<Any?>>
    ) {
        val queue = ArrayDeque(responses)
        coEvery { provider.getWatchlist(any(), any()) } answers {
            queue.removeFirst() as Response<List<TraktListItemDto>>
        }
        coEvery { provider.getUserLists(any(), any()) } answers {
            queue.removeFirst() as Response<List<TraktListSummaryDto>>
        }
        coEvery { provider.getUserListItems(any(), any(), any(), any()) } answers {
            queue.removeFirst() as Response<List<TraktListItemDto>>
        }
    }

    private fun stubLibraryRefreshFailure(provider: TraktIntegrationProvider) {
        coEvery { provider.getWatchlist(any(), any()) } returns null
        coEvery { provider.getUserLists(any(), any()) } returns null
        coEvery { provider.getUserListItems(any(), any(), any(), any()) } returns null
    }

    private fun successResponse(body: Any?): Response<Any?> {
        return Response.success(body)
    }
}
