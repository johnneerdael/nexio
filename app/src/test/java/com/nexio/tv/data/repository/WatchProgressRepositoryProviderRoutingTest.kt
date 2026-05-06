package com.nexio.tv.data.repository

import com.nexio.tv.data.repository.TestTrackingAccountScopeProvider
import com.nexio.tv.core.metadata.router.MetadataRouterFacade
import com.nexio.tv.data.local.WatchProgressPreferences
import com.nexio.tv.data.repository.simkl.SimklProgressHistoryMutationAdapter
import com.nexio.tv.data.repository.trakt.SeasonMarkBatcher
import com.nexio.tv.data.trakt.outbox.TraktMutationEnvelope
import com.nexio.tv.data.trakt.outbox.TraktMutationOutboxCoordinator
import com.nexio.tv.domain.model.TrackingProvider
import com.nexio.tv.domain.model.WatchProgress
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import javax.inject.Provider
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class WatchProgressRepositoryProviderRoutingTest {

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

        repo.markAsCompleted(sampleEpisodeProgress())

        assertEquals(SimklProgressHistoryMutationAdapter.ADAPTER_KEY, envelopeSlot.captured.adapterKey)
        assertEquals(SimklProgressHistoryMutationAdapter.MUTATION_KIND_HISTORY_ADD, envelopeSlot.captured.mutationKind)
        coVerify(exactly = 1) {
            preferences.saveProgress(
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

        repo.removeFromHistory(contentId = "tt1520211", season = 1, episode = 2)

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

        repo.removeProgress(contentId = "tt1520211", season = 1, episode = 2)

        assertEquals(SimklProgressHistoryMutationAdapter.ADAPTER_KEY, envelopeSlot.captured.adapterKey)
        assertEquals(SimklProgressHistoryMutationAdapter.MUTATION_KIND_PLAYBACK_DELETE, envelopeSlot.captured.mutationKind)
        coVerify(exactly = 1) { trackingProgressService.applyOptimisticRemoval("tt1520211", 1, 2) }
    }

    private fun repository(
        providerState: EffectiveTrackingProviderState,
        trackingProgressService: TrackingProgressService = mockTrackingProgressService(),
        outbox: TraktMutationOutboxCoordinator = mockk(relaxed = true),
        preferences: WatchProgressPreferences = mockk(relaxed = true)
    ): WatchProgressRepositoryImpl {
        val trackingProviderStateService = mockk<TrackingProviderStateService> {
            every { state } returns flowOf(providerState)
            coEvery { currentState() } returns providerState
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
            accountScopeProvider = TestTrackingAccountScopeProvider()
        )
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
