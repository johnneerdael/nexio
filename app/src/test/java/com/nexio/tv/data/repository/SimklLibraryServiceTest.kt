package com.nexio.tv.data.repository

import com.nexio.tv.core.network.NetworkResult
import com.nexio.tv.data.local.SimklAuthState
import com.nexio.tv.data.local.SimklLibrarySnapshotStore
import com.nexio.tv.data.remote.dto.simkl.SimklActivityBucketDto
import com.nexio.tv.data.remote.dto.simkl.SimklIdsDto
import com.nexio.tv.data.remote.dto.simkl.SimklLastActivitiesResponseDto
import com.nexio.tv.data.remote.dto.simkl.SimklLibraryItemDto
import com.nexio.tv.data.remote.dto.simkl.SimklMediaRefDto
import com.nexio.tv.data.repository.simkl.SimklLibraryMutationAdapter
import com.nexio.tv.data.trakt.outbox.TraktMutationEnvelope
import com.nexio.tv.data.trakt.outbox.TraktMutationOutboxCoordinator
import com.nexio.tv.domain.model.LibraryEntryInput
import com.nexio.tv.domain.model.ListMembershipChanges
import com.nexio.tv.domain.repository.MetaRepository
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.slot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

class SimklLibraryServiceTest {
    @Test
    fun `parseSimklLibraryItemsPayload accepts object wrapped all-items response`() {
        val items = parseSimklLibraryItemsPayload(
            """
            {
              "movies": [
                {
                  "status": "plantowatch",
                  "movie": {
                    "title": "Inception",
                    "year": 2010,
                    "ids": { "imdb": "tt1375666", "tmdb": "27205" }
                  }
                }
              ],
              "shows": [],
              "anime": []
            }
            """.trimIndent(),
            Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
        )

        assertEquals(1, items.size)
        assertEquals("Inception", items.single().movie?.title)
        assertEquals("tt1375666", items.single().movie?.ids?.imdb)
    }

    @Test
    fun `service restores persisted SIMKL library snapshot on init`() = runTest {
        val authDataStore = mockk<com.nexio.tv.data.local.SimklAuthDataStore>()
        everyAuth(authDataStore, true)
        val snapshotStore = mockk<SimklLibrarySnapshotStore>()
        every { snapshotStore.read(any()) } returns SimklLibrarySnapshotStore.Snapshot(
            listTabs = listOf(
                com.nexio.tv.domain.model.LibraryListTab(
                    key = SimklLibraryService.WATCHLIST_KEY,
                    title = "SIMKL Watchlist",
                    type = com.nexio.tv.domain.model.LibraryListTab.Type.WATCHLIST
                )
            ),
            entriesByList = mapOf(
                SimklLibraryService.WATCHLIST_KEY to listOf(
                    com.nexio.tv.domain.model.LibraryEntry(
                        id = "tt1375666",
                        type = "movie",
                        name = "Inception",
                        poster = null,
                        posterShape = com.nexio.tv.domain.model.PosterShape.POSTER,
                        background = null,
                        logo = null,
                        description = null,
                        releaseInfo = null,
                        imdbRating = null,
                        genres = emptyList(),
                        addonBaseUrl = null,
                        listKeys = setOf(SimklLibraryService.WATCHLIST_KEY),
                        listedAt = 1L
                    )
                )
            ),
            updatedAtMs = 100L
        )

        val service = SimklLibraryService(
            remote = mockk(relaxed = true),
            simklAuthDataStore = authDataStore,
            traktMutationOutboxCoordinator = mockk(relaxed = true),
            snapshotStore = snapshotStore,
            metaRepository = metaRepository()
        )

        val items = service.observeAllItems().first()
        val tabs = service.observeListTabs().first()

        assertEquals(1, items.size)
        assertEquals("Inception", items.first().name)
        assertTrue(tabs.any { it.key == SimklLibraryService.WATCHLIST_KEY })
    }

    @Test
    fun `refreshNow populates SIMKL watchlist entries from remote all-items feed`() = runTest {
        val authDataStore = mockk<com.nexio.tv.data.local.SimklAuthDataStore>()
        everyAuth(authDataStore, true)
        val remote = mockk<SimklTrackingRemoteDataSource>()
        val snapshotStore = mockk<SimklLibrarySnapshotStore>(relaxed = true) {
            every { read(any()) } returns null
        }
        coEvery { remote.getLastActivities(any()) } returns Response.success(
            SimklLastActivitiesResponseDto(
                movies = SimklActivityBucketDto(all = "2026-04-12T00:00:00Z")
            )
        )
        coEvery { remote.getAllItems(dateFrom = null, extended = "full", session = any()) } returns Response.success(
            listOf(
                SimklLibraryItemDto(
                    status = "plantowatch",
                    movie = SimklMediaRefDto(
                        title = "Inception",
                        year = 2010,
                        ids = SimklIdsDto(imdb = "tt1375666", tmdb = "27205")
                    )
                )
            )
        )

        val service = SimklLibraryService(
            remote = remote,
            simklAuthDataStore = authDataStore,
            traktMutationOutboxCoordinator = mockk(relaxed = true),
            snapshotStore = snapshotStore,
            metaRepository = metaRepository()
        )

        service.refreshNow(force = true)

        val items = service.observeAllItems().first()
        val memberships = service.observeMembership("tt1375666", "movie").first()
        val tabs = service.observeListTabs().first()

        assertEquals(1, items.size)
        assertEquals("Inception", items.first().name)
        assertTrue(memberships.contains(SimklLibraryService.WATCHLIST_KEY))
        assertTrue(tabs.any { it.key == SimklLibraryService.WATCHLIST_KEY && it.title == "SIMKL Watchlist" })
        verify(exactly = 1) { snapshotStore.write(any(), any()) }
    }

    @Test
    fun `toggleWatchlist rolls back optimistic mutation when outbox enqueue fails`() = runTest {
        val authDataStore = mockk<com.nexio.tv.data.local.SimklAuthDataStore>()
        everyAuth(authDataStore, true)
        val remote = mockk<SimklTrackingRemoteDataSource>()
        stubEmptyRefresh(remote)
        coEvery { remote.getAllItemsByStatus(any(), any(), any(), any(), any()) } returns Response.success(emptyList())
        val outbox = mockk<TraktMutationOutboxCoordinator>()
        coEvery { outbox.enqueueAndDrain(any()) } throws IllegalStateException("boom")

        val service = SimklLibraryService(
            remote = remote,
            simklAuthDataStore = authDataStore,
            traktMutationOutboxCoordinator = outbox,
            snapshotStore = mockk(relaxed = true),
            metaRepository = metaRepository()
        )

        service.refreshNow(force = true)

        runCatching {
            service.toggleWatchlist(
                LibraryEntryInput(
                    itemId = "tt1375666",
                    itemType = "movie",
                    title = "Inception",
                    year = 2010
                )
            )
        }

        assertTrue(service.observeAllItems().first().isEmpty())
        assertTrue(service.observeMembership("tt1375666", "movie").first().isEmpty())
    }

    @Test
    fun `toggleWatchlist applies optimistic SIMKL watchlist membership before settlement`() = runTest {
        val authDataStore = mockk<com.nexio.tv.data.local.SimklAuthDataStore>()
        everyAuth(authDataStore, true)
        val remote = mockk<SimklTrackingRemoteDataSource>()
        stubEmptyRefresh(remote)
        coEvery { remote.getAllItemsByStatus(any(), any(), any(), any(), any()) } returns Response.success(emptyList())
        val outbox = mockk<TraktMutationOutboxCoordinator>()
        val envelopeSlot = slot<TraktMutationEnvelope>()
        coEvery { outbox.enqueueAndDrain(capture(envelopeSlot)) } answers { envelopeSlot.captured }

        val service = SimklLibraryService(
            remote = remote,
            simklAuthDataStore = authDataStore,
            traktMutationOutboxCoordinator = outbox,
            snapshotStore = mockk(relaxed = true),
            metaRepository = metaRepository()
        )

        service.refreshNow(force = true)
        service.toggleWatchlist(
            LibraryEntryInput(
                itemId = "tt1375666",
                itemType = "movie",
                title = "Inception",
                year = 2010
            )
        )

        val memberships = service.observeMembership("tt1375666", "movie").first()
        assertTrue(memberships.contains(SimklLibraryService.WATCHLIST_KEY))
        assertEquals(SimklLibraryMutationAdapter.ADAPTER_KEY, envelopeSlot.captured.adapterKey)
    }

    @Test
    fun `applyMembershipChanges moves item between SIMKL status lists optimistically`() = runTest {
        val authDataStore = mockk<com.nexio.tv.data.local.SimklAuthDataStore>()
        everyAuth(authDataStore, true)
        val remote = mockk<SimklTrackingRemoteDataSource>()
        stubEmptyRefresh(remote)
        coEvery { remote.getAllItemsByStatus(any(), any(), any(), any(), any()) } returns Response.success(emptyList())
        val outbox = mockk<TraktMutationOutboxCoordinator>()
        coEvery { outbox.enqueueAndDrain(any()) } answers { firstArg() }

        val service = SimklLibraryService(
            remote = remote,
            simklAuthDataStore = authDataStore,
            traktMutationOutboxCoordinator = outbox,
            snapshotStore = mockk(relaxed = true),
            metaRepository = metaRepository()
        )

        service.refreshNow(force = true)
        service.applyMembershipChanges(
            item = LibraryEntryInput(
                itemId = "tt1375666",
                itemType = "movie",
                title = "Inception",
                year = 2010
            ),
            changes = ListMembershipChanges(
                desiredMembership = mapOf(SimklLibraryService.COMPLETED_KEY to true)
            )
        )

        val memberships = service.observeMembership("tt1375666", "movie").first()
        assertTrue(memberships.contains(SimklLibraryService.COMPLETED_KEY))
        assertTrue(!memberships.contains(SimklLibraryService.WATCHLIST_KEY))
    }

    @Test
    fun `applyMembershipChanges rolls back status move when outbox enqueue fails`() = runTest {
        val authDataStore = mockk<com.nexio.tv.data.local.SimklAuthDataStore>()
        everyAuth(authDataStore, true)
        val remote = mockk<SimklTrackingRemoteDataSource>()
        coEvery { remote.getLastActivities(any()) } returns Response.success(
            SimklLastActivitiesResponseDto(
                movies = SimklActivityBucketDto(all = "2026-04-12T00:00:00Z")
            )
        )
        coEvery { remote.getAllItems(dateFrom = null, extended = "full", session = any()) } returns Response.success(
            listOf(
                SimklLibraryItemDto(
                    status = "plantowatch",
                    movie = SimklMediaRefDto(
                        title = "Inception",
                        year = 2010,
                        ids = SimklIdsDto(imdb = "tt1375666", tmdb = "27205")
                    )
                )
            )
        )
        coEvery {
            remote.getAllItemsByStatus(
                type = "movies",
                status = "plantowatch",
                dateFrom = any(),
                extended = any(),
                episodeWatchedAt = any()
            )
        } returns Response.success(
            listOf(
                SimklLibraryItemDto(
                    status = "plantowatch",
                    movie = SimklMediaRefDto(
                        title = "Inception",
                        year = 2010,
                        ids = SimklIdsDto(imdb = "tt1375666", tmdb = "27205")
                    )
                )
            )
        )
        coEvery { remote.getAllItemsByStatus(any(), any(), any(), any(), any()) } returns Response.success(emptyList())
        val outbox = mockk<TraktMutationOutboxCoordinator>()
        coEvery { outbox.enqueueAndDrain(any()) } throws IllegalStateException("boom")

        val service = SimklLibraryService(
            remote = remote,
            simklAuthDataStore = authDataStore,
            traktMutationOutboxCoordinator = outbox,
            snapshotStore = mockk(relaxed = true),
            metaRepository = metaRepository()
        )

        service.refreshNow(force = true)
        runCatching {
            service.applyMembershipChanges(
                item = LibraryEntryInput(
                    itemId = "tt1375666",
                    itemType = "movie",
                    title = "Inception",
                    year = 2010
                ),
                changes = ListMembershipChanges(
                    desiredMembership = mapOf(SimklLibraryService.COMPLETED_KEY to true)
                )
            )
        }

        val memberships = service.observeMembership("tt1375666", "movie").first()
        assertTrue(!memberships.contains(SimklLibraryService.COMPLETED_KEY))
    }

    private fun everyAuth(
        authDataStore: com.nexio.tv.data.local.SimklAuthDataStore,
        authenticated: Boolean
    ) {
        val state = MutableStateFlow(
            SimklAuthState(
                accessToken = if (authenticated) "token" else null
            )
        )
        every { authDataStore.state } returns state
        every { authDataStore.isEffectivelyAuthenticated } returns state.map { it.isAuthenticated }
        every { authDataStore.stateForProfile(any()) } returns state
    }

    private fun stubEmptyRefresh(remote: SimklTrackingRemoteDataSource) {
        coEvery { remote.getLastActivities(any()) } returns Response.success(
            SimklLastActivitiesResponseDto(
                movies = SimklActivityBucketDto(all = "2026-04-12T00:00:00Z")
            )
        )
        coEvery { remote.getAllItems(dateFrom = null, extended = "full", session = any()) } returns Response.success(emptyList())
    }

    private fun metaRepository(): MetaRepository {
        return mockk {
            every { getMetaFromAllAddons(any(), any(), any(), any(), any()) } returns flowOf(
                NetworkResult.Error("no metadata")
            )
        }
    }
}
