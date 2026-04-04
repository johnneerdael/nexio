package com.nexio.tv.ui.screens.stream

import android.util.Log
import android.content.Context
import androidx.lifecycle.SavedStateHandle
import com.nexio.tv.core.network.NetworkResult
import com.nexio.tv.data.local.DebugSettingsDataStore
import com.nexio.tv.data.local.DebridBenchmarkStore
import com.nexio.tv.data.local.PlayerPreference
import com.nexio.tv.data.local.PlayerSettings
import com.nexio.tv.data.local.PlayerSettingsDataStore
import com.nexio.tv.data.local.StreamAutoPlayMode
import com.nexio.tv.data.local.StreamLinkCacheDataStore
import com.nexio.tv.data.repository.benchmark.BenchmarkAwareStreamScorer
import com.nexio.tv.data.repository.benchmark.DebridBenchmarkProvider
import com.nexio.tv.data.repository.benchmark.DebridBenchmarkTransportMode
import com.nexio.tv.data.repository.benchmark.ShadowContentScoreBreakdown
import com.nexio.tv.data.repository.benchmark.ShadowDecisionBreakdown
import com.nexio.tv.data.repository.benchmark.ShadowParsedStreamFacts
import com.nexio.tv.data.repository.benchmark.ShadowRequestContext
import com.nexio.tv.data.repository.benchmark.ShadowStreamDecision
import com.nexio.tv.data.repository.benchmark.ShadowTransportScoreBreakdown
import com.nexio.tv.data.repository.benchmark.ShadowAutoPlayDecisionLogger
import com.nexio.tv.domain.model.Addon
import com.nexio.tv.domain.model.AddonResource
import com.nexio.tv.domain.model.AddonStreams
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.Stream
import com.nexio.tv.domain.model.StreamBehaviorHints
import com.nexio.tv.domain.repository.AddonRepository
import com.nexio.tv.domain.repository.MetaRepository
import com.nexio.tv.domain.repository.StreamRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.unmockkStatic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StreamScreenViewModelDeterministicAutoplayTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        mockkStatic(Log::class)
        every { Log.i(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.d(any<String>(), any<String>()) } returns 0
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkStatic(Log::class)
    }

    @Test
    fun `deterministic autoplay keeps selector hidden across error and no winner flows`() = runTest(dispatcher) {
        val errorViewModel = buildViewModel(
            streamFlow = flowOf(
                NetworkResult.Loading,
                NetworkResult.Error("Provider timeout")
            )
        )
        try {
            advanceUntilIdle()

            val errorState = errorViewModel.uiState.value
            assertTrue(errorState.isDeterministicAutoplay)
            assertTrue(errorState.isDirectAutoPlayFlow)
            assertTrue(errorState.showDirectAutoPlayOverlay)
            assertEquals(null, errorState.error)
            assertEquals("Provider timeout", errorState.deterministicAutoplayFailureMessage)
            assertEquals(null, errorState.autoPlayStream)
            assertEquals(null, errorState.autoPlayPlaybackInfo)
        } finally {
            clearViewModel(errorViewModel)
            advanceUntilIdle()
        }

        val addonStreams = listOf(
            AddonStreams(
                addonName = "Example Addon",
                addonLogo = null,
                streams = listOf(
                    stream(
                        name = "The.Movie.2002.2160p.WEB-DL.DDP5.1.HDR.mkv",
                        wrappedProviderId = null
                    )
                )
            )
        )
        val noWinnerViewModel = buildViewModel(
            streamFlow = flowOf(
                NetworkResult.Loading,
                NetworkResult.Success(addonStreams)
            )
        )

        try {
            advanceUntilIdle()

            val noWinnerState = noWinnerViewModel.uiState.value
            assertTrue(noWinnerState.isDeterministicAutoplay)
            assertTrue(noWinnerState.showDirectAutoPlayOverlay)
            assertEquals(null, noWinnerState.error)
            assertEquals(null, noWinnerState.autoPlayStream)
            assertEquals(null, noWinnerState.autoPlayPlaybackInfo)
        } finally {
            clearViewModel(noWinnerViewModel)
            advanceUntilIdle()
        }
    }

    @Test
    fun `missing runtime disables bitrate based early finish`() {
        val winners = List(3) { index ->
            remuxWinner(
                streamKey = "winner-$index",
                provider = DebridBenchmarkProvider.REAL_DEBRID,
                averageBitrateMbps = 0.0,
                filename = "Same.Release.$index.mkv"
            )
        }

        assertEquals(
            false,
            deterministicAutoplayEarlyFinishSatisfied(
                winners = winners,
                request = ShadowRequestContext(
                    requestId = "req",
                    videoId = "tt1",
                    contentType = "movie",
                    title = "Example",
                    season = null,
                    episode = null,
                    runtimeMinutes = null
                )
            )
        )
    }

    @Test
    fun `same provider duplicates do not inflate early finish counts`() {
        val winners = listOf(
            remuxWinner("rd-1", DebridBenchmarkProvider.REAL_DEBRID, 60.0, "Movie.Release.2160p.REMUX.mkv"),
            remuxWinner("rd-2", DebridBenchmarkProvider.REAL_DEBRID, 60.0, "Movie.Release.2160p.REMUX.mkv"),
            remuxWinner("rd-3", DebridBenchmarkProvider.REAL_DEBRID, 60.0, "Movie.Release.2160p.REMUX.mkv")
        )

        assertEquals(
            false,
            deterministicAutoplayEarlyFinishSatisfied(
                winners = winners,
                request = movieRequest()
            )
        )
    }

    @Test
    fun `same release across different unlockers can satisfy early finish`() {
        val winners = listOf(
            remuxWinner("rd", DebridBenchmarkProvider.REAL_DEBRID, 60.0, "Movie.Release.2160p.REMUX.mkv"),
            remuxWinner("pm", DebridBenchmarkProvider.PREMIUMIZE, 60.0, "Movie.Release.2160p.REMUX.mkv"),
            remuxWinner("pm-2", DebridBenchmarkProvider.PREMIUMIZE, 60.0, "Another.Release.2160p.REMUX.mkv")
        )

        assertEquals(
            true,
            deterministicAutoplayEarlyFinishSatisfied(
                winners = winners,
                request = movieRequest()
            )
        )
    }

    @Test
    fun `only scorer eligible winners count toward early finish`() {
        val eligibleWinners = listOf(
            remuxWinner("rd", DebridBenchmarkProvider.REAL_DEBRID, 60.0, "Eligible.One.mkv"),
            remuxWinner("pm", DebridBenchmarkProvider.PREMIUMIZE, 60.0, "Eligible.Two.mkv")
        )

        assertEquals(
            false,
            deterministicAutoplayEarlyFinishSatisfied(
                winners = eligibleWinners,
                request = movieRequest()
            )
        )
    }

    private fun buildViewModel(
        streamFlow: kotlinx.coroutines.flow.Flow<NetworkResult<List<AddonStreams>>>
    ): StreamScreenViewModel {
        val context = mockk<Context>(relaxed = true)
        val streamRepository = mockk<StreamRepository>()
        val addonRepository = mockk<AddonRepository>()
        val metaRepository = mockk<MetaRepository>(relaxed = true)
        val playerSettingsDataStore = mockk<PlayerSettingsDataStore>()
        val streamLinkCacheDataStore = mockk<StreamLinkCacheDataStore>()
        val debugSettingsDataStore = mockk<DebugSettingsDataStore>()
        val debridBenchmarkStore = mockk<DebridBenchmarkStore>()
        val shadowLogger = mockk<ShadowAutoPlayDecisionLogger>(relaxed = true)

        every {
            playerSettingsDataStore.playerSettings
        } returns flowOf(
            PlayerSettings(
                playerPreference = PlayerPreference.INTERNAL,
                streamAutoPlayMode = StreamAutoPlayMode.FIRST_STREAM
            )
        )
        coEvery { streamLinkCacheDataStore.getValid(any(), any()) } returns null
        every { debugSettingsDataStore.streamDiagnosticsEnabled } returns flowOf(false)
        every { addonRepository.getInstalledAddons() } returns flowOf(listOf(installedAddon()))
        every {
            streamRepository.getStreamsFromAllAddons(
                type = any(),
                videoId = any(),
                season = any(),
                episode = any(),
                installedAddons = any(),
                requestOrigin = any(),
                requestId = any()
            )
        } returns streamFlow
        every { streamRepository.cancelActiveStreamRequests(any()) } just runs
        every { debridBenchmarkStore.latestResult(any()) } returns flowOf(null)

        return StreamScreenViewModel(
            context = context,
            streamRepository = streamRepository,
            addonRepository = addonRepository,
            metaRepository = metaRepository,
            playerSettingsDataStore = playerSettingsDataStore,
            streamLinkCacheDataStore = streamLinkCacheDataStore,
            debugSettingsDataStore = debugSettingsDataStore,
            debridBenchmarkStore = debridBenchmarkStore,
            benchmarkAwareStreamScorer = BenchmarkAwareStreamScorer(),
            shadowAutoPlayDecisionLogger = shadowLogger,
            savedStateHandle = SavedStateHandle(
                mapOf(
                    "videoId" to "tt0167261",
                    "contentType" to "movie",
                    "title" to "The Lord of the Rings: The Two Towers",
                    "contentId" to "tt0167261",
                    "runtime" to "179",
                    "genres" to "Adventure",
                    "year" to "2002",
                    "deterministicAutoplay" to "true"
                )
            )
        )
    }

    private fun installedAddon(): Addon {
        return Addon(
            id = "addon-1",
            name = "Example Addon",
            displayName = "Example Addon",
            version = "1.0.0",
            description = null,
            logo = null,
            baseUrl = "https://example.com",
            catalogs = emptyList(),
            types = listOf(ContentType.MOVIE),
            resources = listOf(
                AddonResource(
                    name = "stream",
                    types = listOf("movie"),
                    idPrefixes = null
                )
            )
        )
    }

    private fun stream(
        name: String,
        wrappedProviderId: String?
    ): Stream {
        return Stream(
            name = name,
            title = name,
            description = null,
            url = "https://example.com/${name.hashCode()}.mkv",
            ytId = null,
            infoHash = null,
            fileIdx = null,
            externalUrl = null,
            behaviorHints = StreamBehaviorHints(
                notWebReady = false,
                bingeGroup = null,
                countryWhitelist = null,
                proxyHeaders = null,
                videoHash = null,
                videoSize = 35_839_352_065,
                filename = name
            ),
            addonName = "Example Addon",
            addonLogo = null,
            wrappedProviderId = wrappedProviderId,
            wrappedOriginalStreamKey = name
        )
    }

    private fun movieRequest(): ShadowRequestContext = ShadowRequestContext(
        requestId = "req",
        videoId = "tt1",
        contentType = "movie",
        title = "Example",
        season = null,
        episode = null,
        runtimeMinutes = 120
    )

    private fun remuxWinner(
        streamKey: String,
        provider: DebridBenchmarkProvider,
        averageBitrateMbps: Double,
        filename: String
    ): ShadowStreamDecision {
        return ShadowStreamDecision(
            streamKey = streamKey,
            parsed = ShadowParsedStreamFacts(filename = filename),
            provider = provider,
            transport = DebridBenchmarkTransportMode.OPTIMIZED,
            finalScore = 90,
            contentQualityScore = 70,
            transportFitScore = 20,
            suitabilityRatio = 5.0,
            requiredMbps = averageBitrateMbps,
            safeBudgetMbps = 500.0,
            resolution = "2160p",
            hdrTags = emptyList(),
            audioTags = emptyList(),
            breakdown = ShadowDecisionBreakdown(
                averageBitrateMbps = averageBitrateMbps,
                releaseType = "remux",
                lowQuality4k = false,
                realismRatio = 1.0,
                content = ShadowContentScoreBreakdown(
                    resolutionPoints = 30,
                    audioPoints = 0,
                    hdrPoints = 0,
                    codecPoints = 10,
                    releaseTypePoints = 20,
                    bitrateQualityPoints = 0,
                    synergyPoints = 0,
                    penaltyPoints = 0,
                    lowQuality4kPenalty = 0,
                    resolutionTier = "uhd_2160",
                    releaseTypeTier = "remux",
                    codecTier = "hevc_hw",
                    hdrTier = "sdr",
                    audioTier = "other",
                    audioSupportTier = "full_passthrough",
                    hdrSupportTier = "full",
                    realismRatio = 1.0
                ),
                transport = ShadowTransportScoreBreakdown(
                    provider = provider,
                    transport = DebridBenchmarkTransportMode.OPTIMIZED,
                    safeBudgetMbps = 500.0,
                    requiredMbps = averageBitrateMbps,
                    suitabilityRatio = 5.0,
                    ratioScore = 0,
                    startupScore = 0,
                    seekScore = 0,
                    stabilityScore = 0,
                    startupTtfbMs = null,
                    seekTtfbP95Ms = null,
                    seekFailRate = null
                )
            )
        )
    }

    private fun clearViewModel(viewModel: StreamScreenViewModel) {
        val method = androidx.lifecycle.ViewModel::class.java.getDeclaredMethod("clear\$lifecycle_viewmodel_release")
        method.isAccessible = true
        method.invoke(viewModel)
    }
}
