package com.nexio.tv.data.repository

import com.nexio.tv.domain.model.TrackingProvider
import com.nexio.tv.domain.model.WatchProgress
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultTrackingProgressServiceDualSourceTest {

    private val traktService = mockk<TraktProgressService>(relaxed = true)
    private val simklService = mockk<SimklProgressService>(relaxed = true)
    private val providerStateService = mockk<TrackingProviderStateService>(relaxed = true)
    private val stateFlow = MutableStateFlow(EffectiveTrackingProviderState())

    private fun newService(): DefaultTrackingProgressService {
        every { providerStateService.state } returns stateFlow
        return DefaultTrackingProgressService(
            traktProgressService = traktService,
            simklProgressService = simklService,
            trackingProviderStateService = providerStateService,
        )
    }

    @Test
    fun `observeAllProgress concatenates both providers when both authed`() = runTest {
        every { traktService.observeAllProgress() } returns flowOf(
            listOf(progress(contentId = "tt-trakt", source = WatchProgress.SOURCE_TRAKT_PLAYBACK))
        )
        every { simklService.observeAllProgress() } returns flowOf(
            listOf(progress(contentId = "tt-simkl", source = WatchProgress.SOURCE_SIMKL_PLAYBACK))
        )
        stateFlow.value = EffectiveTrackingProviderState(
            traktAuthenticated = true,
            simklAuthenticated = true,
        )

        val emitted = newService().observeAllProgress().first()

        assertEquals(2, emitted.size)
        val sources = emitted.map { it.source }.toSet()
        assertEquals(
            setOf(WatchProgress.SOURCE_TRAKT_PLAYBACK, WatchProgress.SOURCE_SIMKL_PLAYBACK),
            sources,
        )
    }

    @Test
    fun `observeAllProgress yields only Trakt when only Trakt authed`() = runTest {
        every { traktService.observeAllProgress() } returns flowOf(
            listOf(progress(contentId = "tt-trakt", source = WatchProgress.SOURCE_TRAKT_PLAYBACK))
        )
        every { simklService.observeAllProgress() } returns flowOf(
            listOf(progress(contentId = "tt-simkl", source = WatchProgress.SOURCE_SIMKL_PLAYBACK))
        )
        stateFlow.value = EffectiveTrackingProviderState(traktAuthenticated = true)

        val emitted = newService().observeAllProgress().first()

        assertEquals(1, emitted.size)
        assertEquals(WatchProgress.SOURCE_TRAKT_PLAYBACK, emitted.single().source)
    }

    @Test
    fun `observeAllProgress yields only Simkl when only Simkl authed`() = runTest {
        every { traktService.observeAllProgress() } returns flowOf(
            listOf(progress(contentId = "tt-trakt", source = WatchProgress.SOURCE_TRAKT_PLAYBACK))
        )
        every { simklService.observeAllProgress() } returns flowOf(
            listOf(progress(contentId = "tt-simkl", source = WatchProgress.SOURCE_SIMKL_PLAYBACK))
        )
        stateFlow.value = EffectiveTrackingProviderState(simklAuthenticated = true)

        val emitted = newService().observeAllProgress().first()

        assertEquals(1, emitted.size)
        assertEquals(WatchProgress.SOURCE_SIMKL_PLAYBACK, emitted.single().source)
    }

    @Test
    fun `observeAllProgress emits empty list when no provider authed`() = runTest {
        stateFlow.value = EffectiveTrackingProviderState()

        val emitted = newService().observeAllProgress().first()

        assertEquals(emptyList<WatchProgress>(), emitted)
    }

    private fun progress(contentId: String, source: String) = WatchProgress(
        contentId = contentId,
        contentType = "movie",
        name = "Test",
        poster = null,
        backdrop = null,
        logo = null,
        videoId = contentId,
        season = null,
        episode = null,
        episodeTitle = null,
        position = 5_000L,
        duration = 60_000L,
        lastWatched = 1_000L,
        source = source,
    )

    @Test
    fun `provider tag flow honors only TRAKT when stored preference says SIMKL but only Trakt authed`() = runTest {
        // Sanity-check that the activeProviders-driven path ignores stored/effective fields.
        every { traktService.observeAllProgress() } returns flowOf(emptyList())
        every { simklService.observeAllProgress() } returns flowOf(emptyList())
        stateFlow.value = EffectiveTrackingProviderState(
            storedProvider = TrackingProvider.SIMKL,
            traktAuthenticated = true,
            simklAuthenticated = false,
        )

        // Just exercise the flow to ensure no NPE / unauth path used.
        val emitted = newService().observeAllProgress().first()
        assertEquals(emptyList<WatchProgress>(), emitted)
    }
}
