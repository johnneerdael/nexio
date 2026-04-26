package com.nexio.tv.data.repository.trakt

import com.nexio.tv.data.local.PlayerSettings
import com.nexio.tv.data.local.PlayerSettingsDataStore
import com.nexio.tv.data.local.TraktAuthState
import com.nexio.tv.data.remote.dto.trakt.TraktIdsDto
import com.nexio.tv.data.repository.TrackingAuthSession
import com.nexio.tv.data.repository.TraktScrobbleItem
import com.nexio.tv.data.repository.TraktProgressService
import com.nexio.tv.data.trakt.outbox.TraktMutationExecutionResult
import com.nexio.tv.data.trakt.outbox.TraktMutationSettlement
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

class TraktScrobbleMutationAdapterTest {

    @Test
    fun `scrobble envelope keeps item collapse key and rollback metadata`() {
        val envelope = TraktScrobbleMutationAdapter.buildScrobbleEnvelope(
            item = movieItem("Arrival"),
            action = "start",
            progressPercent = 14f,
            rollbackState = TraktWatchingNowStateController.Snapshot(
                active = false,
                title = "Server truth",
                contentType = "movie"
            ),
            optimisticVersion = 7L
        )

        assertEquals("scrobble:${movieItem("Arrival").itemKey}", envelope.collapseKey)
        assertEquals(TraktScrobbleMutationAdapter.MUTATION_KIND_SCROBBLE, envelope.mutationKind)
    }

    @Test
    fun `adapter rolls back watching state when terminal failure settles`() = kotlinx.coroutines.test.runTest {
        val traktIntegrationProvider = mockk<com.nexio.tv.data.integration.trakt.TraktIntegrationProvider>(relaxed = true)
        val traktProgressService = mockk<TraktProgressService>(relaxed = true)
        val controller = TraktWatchingNowStateController()
        val adapter = TraktScrobbleMutationAdapter(
            traktIntegrationProvider = traktIntegrationProvider,
            traktProgressService = traktProgressService,
            watchingNowStateController = controller,
            playerSettingsDataStore = playerSettingsStore()
        )
        controller.publish(
            TraktWatchingNowStateController.Snapshot(
                active = true,
                title = "Arrival",
                contentType = "movie"
            )
        )
        val envelope = TraktScrobbleMutationAdapter.buildCheckinEnvelope(
            item = movieItem("Arrival"),
            message = null,
            rollbackState = TraktWatchingNowStateController.Snapshot(),
            optimisticVersion = controller.nextOptimisticVersion()
        )

        adapter.rollbackToServerTruth(
            envelope = envelope,
            failure = TraktMutationSettlement.TerminalFailure(reason = "boom", httpStatusCode = 500)
        )

        assertEquals(null, controller.current().title)
        assertTrue(!controller.current().active)
    }

    @Test
    fun `checkin execute returns success when trakt accepts request`() = kotlinx.coroutines.test.runTest {
        val traktIntegrationProvider = mockk<com.nexio.tv.data.integration.trakt.TraktIntegrationProvider>(relaxed = true)
        val traktProgressService = mockk<TraktProgressService>(relaxed = true)
        val controller = TraktWatchingNowStateController()
        val adapter = TraktScrobbleMutationAdapter(
            traktIntegrationProvider = traktIntegrationProvider,
            traktProgressService = traktProgressService,
            watchingNowStateController = controller,
            playerSettingsDataStore = playerSettingsStore()
        )
        val envelope = TraktScrobbleMutationAdapter.buildCheckinEnvelope(
            item = movieItem("Arrival"),
            message = null,
            rollbackState = TraktWatchingNowStateController.Snapshot(),
            optimisticVersion = 1L
        ).copy(profileId = 2)

        val sessionSlot = io.mockk.slot<TrackingAuthSession>()
        coEvery {
            traktIntegrationProvider.checkin(capture(sessionSlot), any())
        } returns Response.success(null)

        val result = adapter.execute(envelope)

        assertTrue(result is TraktMutationExecutionResult.Success)
        assertEquals(2, sessionSlot.captured.profileId)
        coVerify(exactly = 0) { traktProgressService.refreshNow() }
    }

    private fun movieItem(title: String) = TraktScrobbleItem.Movie(
        title = title,
        year = 2025,
        ids = TraktIdsDto(imdb = "tt1234567")
    )

    private fun playerSettingsStore(
        settings: PlayerSettings = PlayerSettings()
    ): PlayerSettingsDataStore {
        return mockk<PlayerSettingsDataStore> {
            every { playerSettings } returns flowOf(settings)
        }
    }
}
