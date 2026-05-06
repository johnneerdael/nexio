package com.nexio.tv.data.repository

import com.nexio.tv.core.integration.ActiveProfileSession
import com.nexio.tv.core.integration.ProfileBoundaryException
import com.nexio.tv.data.repository.TestTrackingAccountScopeProvider
import com.nexio.tv.core.metadata.router.MetadataRouterFacade
import com.nexio.tv.core.profile.ProfileManager
import com.nexio.tv.data.local.WatchProgressPreferences
import com.nexio.tv.data.repository.simkl.SimklProgressHistoryMutationAdapter
import com.nexio.tv.data.repository.trakt.SeasonMarkBatcher
import com.nexio.tv.data.repository.trakt.TraktProgressHistoryMutationAdapter
import com.nexio.tv.data.trakt.outbox.TraktMutationEnvelope
import com.nexio.tv.data.trakt.outbox.TraktMutationOutboxCoordinator
import com.nexio.tv.domain.model.TrackingProvider
import com.nexio.tv.domain.model.WatchProgress
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import javax.inject.Provider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class WatchProgressRepositoryProviderRoutingTest {
    private val profileSession = ActiveProfileSession(
        profileId = 1,
        sessionId = "session-1",
        sessionOrdinal = 1L,
        startedAtMs = 1_000L
    )

    @Test
    fun `watch progress persists locally without tracking account`() = runTest {
        val preferences = mockk<WatchProgressPreferences>(relaxed = true)
        val trackingProgressService = mockTrackingProgressService()
        val progress = sampleEpisodeProgress()
        val repo = repository(
            providerState = EffectiveTrackingProviderState(),
            trackingProgressService = trackingProgressService,
            preferences = preferences
        )

        repo.upsertProgress(profileSession, progress, syncRemote = true)

        coVerify(exactly = 1) { preferences.saveProgress(1, progress) }
        verify(exactly = 0) { trackingProgressService.applyOptimisticProgress(any()) }
    }

    @Test
    fun `watch progress remote sync is skipped when no tracking account is authenticated`() = runTest {
        val trackingProgressService = mockTrackingProgressService()
        val repo = repository(
            providerState = EffectiveTrackingProviderState(),
            trackingProgressService = trackingProgressService
        )

        repo.upsertProgress(profileSession, sampleEpisodeProgress(), syncRemote = true)

        verify(exactly = 0) { trackingProgressService.applyOptimisticProgress(any()) }
    }

    @Test
    fun `watch progress write rejects stale profile session before local persistence`() = runTest {
        val staleSession = profileSession.copy(sessionId = "stale-session")
        val activeSession = profileSession.copy(sessionId = "active-session")
        val preferences = mockk<WatchProgressPreferences>(relaxed = true)
        val repo = repository(
            providerState = EffectiveTrackingProviderState(),
            preferences = preferences,
            profileManager = testProfileManager(activeSession)
        )

        try {
            repo.upsertProgress(staleSession, sampleEpisodeProgress(), syncRemote = false)
            fail("Expected stale profile session to be rejected")
        } catch (_: ProfileBoundaryException) {
        }
        coVerify(exactly = 0) { preferences.saveProgress(any(), any()) }
    }

    @Test
    fun `markAsCompleted routes history add through simkl adapter when simkl is selected`() = runTest {
        val outbox = mockk<TraktMutationOutboxCoordinator>()
        val envelopeSlot = slot<TraktMutationEnvelope>()
        coEvery { outbox.enqueueAndDrain(capture(envelopeSlot)) } answers { envelopeSlot.captured }
        val preferences = mockk<WatchProgressPreferences>(relaxed = true)

        val repo = repository(
            providerState = EffectiveTrackingProviderState(
                effectiveProvider = TrackingProvider.SIMKL,
                simklAuthenticated = true
            ),
            outbox = outbox,
            preferences = preferences
        )

        repo.markAsCompleted(profileSession, sampleEpisodeProgress())

        assertEquals(SimklProgressHistoryMutationAdapter.ADAPTER_KEY, envelopeSlot.captured.adapterKey)
        assertEquals(SimklProgressHistoryMutationAdapter.MUTATION_KIND_HISTORY_ADD, envelopeSlot.captured.mutationKind)
        coVerify(exactly = 1) {
            preferences.saveProgress(
                1,
                match { progress ->
                    progress.contentId == "tt1520211" &&
                        progress.season == 1 &&
                        progress.episode == 2 &&
                        progress.progressPercent == 100f &&
                        progress.isCompleted()
                }
            )
        }
    }

    @Test
    fun `markAsCompleted scopes simkl mutation envelope to captured profile session`() = runTest {
        val secondarySession = profileSession.copy(
            profileId = 2,
            sessionId = "session-2",
            sessionOrdinal = 2L
        )
        val outbox = mockk<TraktMutationOutboxCoordinator>()
        val envelopeSlot = slot<TraktMutationEnvelope>()
        coEvery { outbox.enqueueAndDrain(capture(envelopeSlot)) } answers { envelopeSlot.captured }

        val repo = repository(
            providerState = EffectiveTrackingProviderState(
                effectiveProvider = TrackingProvider.SIMKL,
                simklAuthenticated = true
            ),
            outbox = outbox,
            profileManager = testProfileManager(secondarySession)
        )

        repo.markAsCompleted(secondarySession, sampleEpisodeProgress())

        assertEquals(2, envelopeSlot.captured.profileId)
        assertEquals("simkl-test-credential", envelopeSlot.captured.credentialHash)
    }

    @Test
    fun `markAsCompleted scopes trakt mutation envelope to captured profile session`() = runTest {
        val secondarySession = profileSession.copy(
            profileId = 2,
            sessionId = "session-2",
            sessionOrdinal = 2L
        )
        val outbox = mockk<TraktMutationOutboxCoordinator>()
        val envelopeSlot = slot<TraktMutationEnvelope>()
        coEvery { outbox.enqueueAndDrain(capture(envelopeSlot)) } answers { envelopeSlot.captured }

        val repo = repository(
            providerState = EffectiveTrackingProviderState(
                effectiveProvider = TrackingProvider.TRAKT,
                traktAuthenticated = true
            ),
            outbox = outbox,
            profileManager = testProfileManager(secondarySession)
        )

        repo.markAsCompleted(secondarySession, sampleEpisodeProgress())

        assertEquals(TraktProgressHistoryMutationAdapter.ADAPTER_KEY, envelopeSlot.captured.adapterKey)
        assertEquals(2, envelopeSlot.captured.profileId)
        assertEquals("trakt-test-credential", envelopeSlot.captured.credentialHash)
    }

    @Test
    fun `removeFromHistory routes history remove through simkl adapter when simkl is selected`() = runTest {
        val outbox = mockk<TraktMutationOutboxCoordinator>()
        val envelopeSlot = slot<TraktMutationEnvelope>()
        coEvery { outbox.enqueueAndDrain(capture(envelopeSlot)) } answers { envelopeSlot.captured }

        val repo = repository(
            providerState = EffectiveTrackingProviderState(
                effectiveProvider = TrackingProvider.SIMKL,
                simklAuthenticated = true
            ),
            outbox = outbox
        )

        repo.removeFromHistory(profileSession, contentId = "tt1520211", season = 1, episode = 2)

        assertEquals(SimklProgressHistoryMutationAdapter.ADAPTER_KEY, envelopeSlot.captured.adapterKey)
        assertEquals(SimklProgressHistoryMutationAdapter.MUTATION_KIND_HISTORY_REMOVE, envelopeSlot.captured.mutationKind)
    }

    @Test
    fun `removeProgress routes playback delete through simkl adapter when simkl is selected`() = runTest {
        val trackingProgressService = mockk<TrackingProgressService>(relaxed = true)
        every { trackingProgressService.observeAllProgress() } returns flowOf(emptyList())
        every { trackingProgressService.observeRemoteSnapshotLoaded() } returns flowOf(true)
        every { trackingProgressService.observeContinueWatchingNextUp() } returns flowOf(emptyList())
        every { trackingProgressService.observeSyntheticContinueWatchingNextUp() } returns flowOf(emptyList())
        every { trackingProgressService.observeEpisodeProgress(any()) } returns flowOf(emptyMap())
        every { trackingProgressService.observeMovieWatched(any()) } returns flowOf(false)
        coEvery { trackingProgressService.resolvePlaybackDeleteIdsForOutbox("tt1520211", 1, 2) } returns listOf(44L)

        val outbox = mockk<TraktMutationOutboxCoordinator>()
        val envelopeSlot = slot<TraktMutationEnvelope>()
        coEvery { outbox.enqueueAndDrain(capture(envelopeSlot)) } answers { envelopeSlot.captured }

        val repo = repository(
            providerState = EffectiveTrackingProviderState(
                effectiveProvider = TrackingProvider.SIMKL,
                simklAuthenticated = true
            ),
            trackingProgressService = trackingProgressService,
            outbox = outbox
        )

        repo.removeProgress(profileSession, contentId = "tt1520211", season = 1, episode = 2)

        assertEquals(SimklProgressHistoryMutationAdapter.ADAPTER_KEY, envelopeSlot.captured.adapterKey)
        assertEquals(SimklProgressHistoryMutationAdapter.MUTATION_KIND_PLAYBACK_DELETE, envelopeSlot.captured.mutationKind)
        coVerify(exactly = 1) { trackingProgressService.applyOptimisticRemoval("tt1520211", 1, 2) }
    }

    private fun repository(
        providerState: EffectiveTrackingProviderState,
        trackingProgressService: TrackingProgressService = mockTrackingProgressService(),
        outbox: TraktMutationOutboxCoordinator = mockk(relaxed = true),
        preferences: WatchProgressPreferences = mockk(relaxed = true),
        profileManager: ProfileManager = testProfileManager()
    ): WatchProgressRepositoryImpl {
        val trackingProviderStateService = mockk<TrackingProviderStateService> {
            every { state } returns flowOf(providerState)
            coEvery { currentState() } returns providerState
            every { stateForProfile(any()) } returns flowOf(providerState)
            coEvery { currentState(any()) } returns providerState
        }
        return WatchProgressRepositoryImpl(
            watchProgressPreferences = preferences,
            trackingProviderStateService = trackingProviderStateService,
            trackingProgressService = trackingProgressService,
            traktMutationOutboxCoordinator = outbox,
            seasonMarkBatcher = mockk<SeasonMarkBatcher>(relaxed = true),
            traktAuthService = mockk(relaxed = true) {
                every { currentTraktProfileId() } returns 1
            },
            snapshotServiceProvider = Provider {
                mockk<ContinueWatchingSnapshotService>(relaxed = true)
            },
            metadataRouterFacade = mockk<MetadataRouterFacade>(relaxed = true),
            accountScopeProvider = TestTrackingAccountScopeProvider(),
            profileManager = profileManager
        )
    }

    private fun testProfileManager(session: ActiveProfileSession = profileSession): ProfileManager {
        val activeProfileId = MutableStateFlow(session.profileId)
        val activeProfileSession = MutableStateFlow(session)
        return mockk {
            every { this@mockk.activeProfileId } returns activeProfileId
            every { this@mockk.activeProfileSession } returns activeProfileSession
            every { this@mockk.isPrimaryProfileActive } returns true
        }
    }

    private fun mockTrackingProgressService(): TrackingProgressService {
        return mockk(relaxed = true) {
            every { observeAllProgress() } returns flowOf(emptyList())
            every { observeRemoteSnapshotLoaded() } returns flowOf(true)
            every { observeContinueWatchingNextUp() } returns flowOf(emptyList())
            every { observeSyntheticContinueWatchingNextUp() } returns flowOf(emptyList())
            every { observeEpisodeProgress(any()) } returns flowOf(emptyMap())
            every { observeMovieWatched(any()) } returns flowOf(false)
            coEvery { resolvePlaybackDeleteIdsForOutbox(any(), any(), any()) } returns emptyList()
        }
    }

    private fun sampleEpisodeProgress(): WatchProgress {
        return WatchProgress(
            contentId = "tt1520211",
            contentType = "series",
            name = "Episode",
            poster = null,
            backdrop = null,
            logo = null,
            videoId = "tt1520211:1:2",
            season = 1,
            episode = 2,
            episodeTitle = "Episode 2",
            position = 100L,
            duration = 100L,
            lastWatched = 1234L,
            progressPercent = 100f
        )
    }
}
