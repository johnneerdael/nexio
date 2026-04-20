package com.nexio.tv.ui.screens.stream

import android.util.Log
import android.content.Context
import androidx.lifecycle.SavedStateHandle
import com.nexio.tv.core.network.NetworkResult
import com.nexio.tv.data.local.CachedStreamLink
import com.nexio.tv.data.local.DebugSettingsDataStore
import com.nexio.tv.data.local.PlayerPreference
import com.nexio.tv.data.local.PlayerSettings
import com.nexio.tv.data.local.PlayerSettingsDataStore
import com.nexio.tv.data.local.StreamAutoPlayMode
import com.nexio.tv.data.local.StreamLinkCacheDataStore
import com.nexio.tv.data.repository.benchmark.AudioEncodingSupport
import com.nexio.tv.data.repository.benchmark.BenchmarkAwareScoringScenarioStream
import com.nexio.tv.data.repository.benchmark.BenchmarkAwareStreamScorer
import com.nexio.tv.data.repository.benchmark.CodecSupport
import com.nexio.tv.data.repository.benchmark.DebridBenchmarkProvider
import com.nexio.tv.data.repository.benchmark.DebridBenchmarkTransportMode
import com.nexio.tv.data.repository.benchmark.DeviceAudioOutputCapabilities
import com.nexio.tv.data.repository.benchmark.DeviceCapabilitySnapshot
import com.nexio.tv.data.repository.benchmark.DeviceVideoDecodeCapabilities
import com.nexio.tv.data.repository.benchmark.ShadowRejectReason
import com.nexio.tv.data.repository.benchmark.ShadowContentScoreBreakdown
import com.nexio.tv.data.repository.benchmark.ShadowDecisionBreakdown
import com.nexio.tv.data.repository.benchmark.ShadowParsedStreamFacts
import com.nexio.tv.data.repository.benchmark.ShadowRequestContext
import com.nexio.tv.data.repository.benchmark.ShadowStreamDecision
import com.nexio.tv.data.repository.benchmark.ShadowTransportScoreBreakdown
import com.nexio.tv.data.repository.benchmark.ShadowAutoPlayDecisionLogger
import com.nexio.tv.data.repository.benchmark.ShadowAutoPlayDecisionEvent
import com.nexio.tv.data.repository.benchmark.ShadowAutoplayCollectionUploader
import com.nexio.tv.data.repository.device.DeviceCapabilityRepository
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
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StreamScreenViewModelDeterministicAutoplayTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var cachedStore: StreamLinkCacheDataStore

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
            assertEquals(null, errorState.directAutoPlayMessage)
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
            assertEquals(null, noWinnerState.directAutoPlayMessage)
            assertEquals(null, noWinnerState.error)
            assertEquals(null, noWinnerState.autoPlayStream)
            assertEquals(null, noWinnerState.autoPlayPlaybackInfo)
        } finally {
            clearViewModel(noWinnerViewModel)
            advanceUntilIdle()
        }
    }

    @Test
    fun `stream selection does not show no streams while stream search is still active`() {
        val viewModel = buildViewModel(
            streamFlow = flow {
                emit(NetworkResult.Loading)
                awaitCancellation()
            }
        )

        try {
            dispatcher.scheduler.runCurrent()
            dispatcher.scheduler.advanceTimeBy(45_000L)
            dispatcher.scheduler.runCurrent()

            assertEquals(false, viewModel.uiState.value.showNoStreamsState)
        } finally {
            clearViewModel(viewModel)
            dispatcher.scheduler.advanceUntilIdle()
        }
    }

    @Test
    fun `deterministic autoplay invalidates cached dv webdl link and does not reuse it`() = runTest(dispatcher) {
        val addonStreams = listOf(
            AddonStreams(
                addonName = "Example Addon",
                addonLogo = null,
                streams = listOf(
                    stream(
                        name = "The.Movie.2002.2160p.WEB-DL.HDR10.DDP5.1.mkv",
                        wrappedProviderId = null
                    )
                )
            )
        )
        val viewModel = buildViewModel(
            streamFlow = flowOf(
                NetworkResult.Loading,
                NetworkResult.Success(addonStreams)
            ),
            cachedLink = CachedStreamLink(
                url = "https://example.com/cached-dv.mkv",
                streamName = "Cached DV",
                headers = emptyMap(),
                cachedAtMs = System.currentTimeMillis(),
                filename = "The.Movie.2002.2160p.WEB-DL.DV.DDP5.1.mkv",
                videoSize = 35_839_352_065
            ),
            playerSettings = PlayerSettings(
                playerPreference = PlayerPreference.INTERNAL,
                streamAutoPlayMode = StreamAutoPlayMode.FIRST_STREAM,
                streamReuseLastLinkEnabled = true
            )
        )

        try {
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertEquals(null, state.autoPlayPlaybackInfo)
            coVerify(exactly = 1) { cachedStore.invalidate(any()) }
            verify {
                Log.i(
                    any<String>(),
                    match { message ->
                        message.contains("AUTOPLAY_CACHE_RESELECTION_TRIGGERED") &&
                            message.contains("mode=deterministic") &&
                            message.contains("invalidated_cached_dv_webdl")
                    }
                )
            }
        } finally {
            clearViewModel(viewModel)
            advanceUntilIdle()
        }
    }

    @Test
    fun `hero gated autoplay resolution logs start and ready before playback handoff`() = runTest(dispatcher) {
        val viewModel = buildViewModel(
            streamFlow = flowOf(NetworkResult.Loading)
        )

        try {
            val resolved = viewModel.resolveAutoPlayPlaybackInfo(
                StreamPlaybackInfo(
                    url = "https://example.com/final.mkv",
                    title = "Example",
                    streamName = "Primary",
                    playerBackend = PlayerPreference.INTERNAL,
                    year = "2002",
                    isExternal = false,
                    isTorrent = false,
                    infoHash = null,
                    ytId = null,
                    headers = null,
                    contentId = "tt0167261",
                    contentType = "movie",
                    contentName = "Example",
                    originalLanguage = "en",
                    poster = null,
                    backdrop = null,
                    logo = null,
                    videoId = "tt0167261",
                    season = null,
                    episode = null,
                    episodeTitle = null,
                    bingeGroup = null,
                    rememberedAudioLanguage = null,
                    rememberedAudioName = null,
                    filename = "Example.2002.1080p.WEB-DL.HDR10.mkv",
                    videoHash = null,
                    videoSize = 1L,
                    streamKey = "primary",
                    isWebDl = true,
                    isDolbyVisionCandidate = false
                )
            )

            assertEquals("primary", resolved?.streamKey)
            verify {
                Log.i(
                    any<String>(),
                    match { message ->
                        message.contains("AUTOPLAY_HERO_GATED_RESOLUTION_START") &&
                            message.contains("overlayActive=true")
                    }
                )
            }
            verify {
                Log.i(
                    any<String>(),
                    match { message ->
                        message.contains("AUTOPLAY_HERO_GATED_RESOLUTION_READY") &&
                            message.contains("readyForPlayback=true")
                    }
                )
            }
        } finally {
            clearViewModel(viewModel)
            advanceUntilIdle()
        }
    }

    @Test
    fun `autoplay fallback list preserves non dv candidate outside cap`() {
        val fallback = selectAutoplayFallbackCandidatesForTesting(
            selectedKey = "dv-1",
            fallbackCandidates = listOf(
                scenarioCard(
                    "dv-1",
                    "rd",
                    visualTags = listOf("DV"),
                    quality = "WEB-DL",
                    filename = "Movie.2160p.WEB-DL.DV.mkv"
                ),
                scenarioCard(
                    "dv-2",
                    "pm",
                    visualTags = listOf("DV"),
                    quality = "WEB-DL",
                    filename = "Movie.2160p.WEB-DL.DV.mkv"
                ),
                scenarioCard(
                    "dv-3",
                    "rd",
                    visualTags = listOf("DV"),
                    quality = "WEB-DL",
                    filename = "Movie.2160p.WEB-DL.DV.mkv"
                ),
                scenarioCard(
                    "hdr-1",
                    "pm",
                    visualTags = listOf("HDR10"),
                    quality = "WEB-DL",
                    filename = "Movie.2160p.WEB-DL.HDR.mkv"
                )
            ),
            maxFallbackCandidates = 3
        )

        assertTrue(
            fallback.any { it.stream.wrappedOriginalStreamKey == "hdr-1" }
        )
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
    fun `early finish decision reports threshold details when triggered`() {
        val decision = deterministicAutoplayEarlyFinishDecision(
            winners = listOf(
                remuxWinner("rd", DebridBenchmarkProvider.REAL_DEBRID, 60.0, "A.mkv"),
                remuxWinner("pm", DebridBenchmarkProvider.PREMIUMIZE, 60.0, "B.mkv"),
                remuxWinner("rd2", DebridBenchmarkProvider.REAL_DEBRID, 60.0, "C.mkv")
            ),
            request = movieRequest()
        )

        assertEquals(true, decision.triggered)
        assertEquals("threshold_met", decision.reason)
        assertEquals("2160p", decision.resolution)
        assertEquals("remux", decision.releaseType)
        assertEquals(3, decision.matchingCount)
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

    @Test
    fun `shadow autoplay replay coordinator emits manual cap event without benchmarks`() {
        val request = ShadowRequestContext(
            requestId = "manual-cap",
            videoId = "tt123",
            contentType = "movie",
            title = "Example",
            season = null,
            episode = null,
            runtimeMinutes = 120
        )
        val coordinator = ShadowAutoPlayReplayCoordinator(BenchmarkAwareStreamScorer())
        val device = DeviceCapabilitySnapshot(
            model = "AM9 PRO",
            manufacturer = "UGOOS",
            sdkInt = 34,
            videoDecode = DeviceVideoDecodeCapabilities(
                h264 = CodecSupport(true, false, true)
            ),
            audioOutput = DeviceAudioOutputCapabilities(
                truehd = AudioEncodingSupport(false, false),
                eac3 = AudioEncodingSupport(true, true),
                atmos = AudioEncodingSupport(true, true)
            ),
            capturedAtMs = 42L
        )

        coordinator.updateCandidates(
            request = request,
            organizedStreams = listOf(
                BenchmarkAwareScoringScenarioStream(
                    streamKey = "manual-cap-1080p",
                    providerId = "RD",
                    resolution = "1080p",
                    quality = "WEB-DL",
                    encode = "H264",
                    sizeBytes = 12L * 1024L * 1024L * 1024L,
                    durationMs = 120L * 60_000L,
                    visualTags = emptyList(),
                    audioTags = listOf("DD+")
                ).toStreamCardModel()
            ),
            manualBitrateCapMbps = 20.0,
            deviceSnapshot = device,
            isFinalPass = true,
            allowEarlyFinishTerminal = false
        )

        val event = coordinator.buildEventIfReady(
            timingsMs = 7L
        )

        assertNotNull(event)
        assertEquals("manual-cap-1080p|RD", event?.selected?.streamKey)
        assertEquals(emptyList<ShadowRejectReason>(), event?.rejected?.flatMap { it.reasons })
        assertEquals(7L, event?.timingsMs)
        assertEquals("supported", event?.selected?.breakdown?.content?.audioSupportTier)
    }

    @Test
    fun `deterministic autoplay candidate selection skips failed primary and tries next ranked winner`() = runBlocking {
        val cards = listOf(
            scenarioCard("primary", providerId = "RD"),
            scenarioCard("secondary", providerId = "PM"),
            scenarioCard("tertiary", providerId = "RD")
        )
        val event = autoplayDecisionEvent(
            winners = listOf(
                remuxWinner("primary", DebridBenchmarkProvider.REAL_DEBRID, 60.0, "Primary.2160p.REMUX.mkv"),
                remuxWinner("secondary", DebridBenchmarkProvider.PREMIUMIZE, 55.0, "Secondary.2160p.REMUX.mkv"),
                remuxWinner("tertiary", DebridBenchmarkProvider.REAL_DEBRID, 50.0, "Tertiary.2160p.REMUX.mkv")
            ),
            selected = remuxWinner("primary", DebridBenchmarkProvider.REAL_DEBRID, 60.0, "Primary.2160p.REMUX.mkv")
        )

        val selected = selectDeterministicAutoplayCandidate(
            event = event,
            eligibleStreams = cards,
            maxCandidates = 3,
            isPlayable = { item -> item.stream.wrappedOriginalStreamKey != "primary" }
        )

        assertEquals("secondary", selected?.selectedItem?.stream?.wrappedOriginalStreamKey)
    }

    @Test
    fun `deterministic autoplay candidate selection drops 1WinStudio filenames`() = runBlocking {
        val cards = listOf(
            scenarioCard(
                streamKey = "bad-release",
                providerId = "RD",
                filename = "High.Potential.S02E16.WEB-DL.1080p.1WinStudio.mkv"
            ),
            scenarioCard(
                streamKey = "safe-release",
                providerId = "PM",
                filename = "High.Potential.S02E16.WEB-DL.1080p.CleanGroup.mkv"
            )
        )
        val event = autoplayDecisionEvent(
            winners = listOf(
                remuxWinner(
                    streamKey = "bad-release",
                    provider = DebridBenchmarkProvider.REAL_DEBRID,
                    averageBitrateMbps = 20.0,
                    filename = "High.Potential.S02E16.WEB-DL.1080p.1WinStudio.mkv"
                ),
                remuxWinner(
                    streamKey = "safe-release",
                    provider = DebridBenchmarkProvider.PREMIUMIZE,
                    averageBitrateMbps = 18.0,
                    filename = "High.Potential.S02E16.WEB-DL.1080p.CleanGroup.mkv"
                )
            ),
            selected = remuxWinner(
                streamKey = "bad-release",
                provider = DebridBenchmarkProvider.REAL_DEBRID,
                averageBitrateMbps = 20.0,
                filename = "High.Potential.S02E16.WEB-DL.1080p.1WinStudio.mkv"
            )
        )
        val preflighted = mutableListOf<String>()

        val selected = selectDeterministicAutoplayCandidate(
            event = event,
            eligibleStreams = cards,
            maxCandidates = 3,
            isPlayable = { item ->
                preflighted += item.stream.wrappedOriginalStreamKey.orEmpty()
                true
            }
        )

        assertEquals("safe-release", selected?.selectedItem?.stream?.wrappedOriginalStreamKey)
        assertEquals(listOf("safe-release"), preflighted)
    }

    @Test
    fun `deterministic autoplay candidate selection preserves selected dv non dv fallback`() = runBlocking {
        val primary = scenarioCard(
            streamKey = "primary-dv",
            providerId = "RD",
            visualTags = listOf("DV"),
            quality = "WEB-DL"
        )
        val fallback = scenarioCard(
            streamKey = "fallback-hdr10",
            providerId = "PM",
            visualTags = listOf("HDR10"),
            quality = "WEB-DL"
        )
        val primaryDecision = remuxWinner(
            streamKey = "primary-dv",
            provider = DebridBenchmarkProvider.REAL_DEBRID,
            averageBitrateMbps = 35.0,
            filename = "Primary.DV.WEB-DL.mkv"
        )
        val fallbackDecision = remuxWinner(
            streamKey = "fallback-hdr10",
            provider = DebridBenchmarkProvider.PREMIUMIZE,
            averageBitrateMbps = 30.0,
            filename = "Fallback.HDR10.WEB-DL.mkv"
        )
        val event = autoplayDecisionEvent(
            winners = listOf(primaryDecision, fallbackDecision),
            selected = primaryDecision,
            selectedNonDolbyVisionFallback = fallbackDecision
        )

        val selected = selectDeterministicAutoplayCandidate(
            event = event,
            eligibleStreams = listOf(primary, fallback),
            maxCandidates = 3,
            isPlayable = { true }
        )

        assertEquals("primary-dv", selected?.selectedItem?.stream?.wrappedOriginalStreamKey)
        assertTrue(
            selected?.fallbackCandidateItems?.any {
                it.stream.wrappedOriginalStreamKey == "fallback-hdr10"
            } == true
        )
    }

    @Test
    fun `stremthru preflight detects static download failed redirect location`() {
        assertEquals(
            true,
            isStremThruDownloadFailedRedirectLocation(
                "https://stremthrufortheweebs.midnightignite.me/v0/store/_/static/download_failed.mp4"
            )
        )
        assertEquals(
            false,
            isStremThruDownloadFailedRedirectLocation("https://45-4.download.real-debrid.com/d/example.mkv")
        )
    }

    private fun buildViewModel(
        streamFlow: kotlinx.coroutines.flow.Flow<NetworkResult<List<AddonStreams>>>,
        cachedLink: CachedStreamLink? = null,
        shadowLogger: ShadowAutoPlayDecisionLogger = mockk(relaxed = true),
        playerSettings: PlayerSettings = PlayerSettings(
            playerPreference = PlayerPreference.INTERNAL,
            streamAutoPlayMode = StreamAutoPlayMode.FIRST_STREAM
        )
    ): StreamScreenViewModel {
        val context = mockk<Context>(relaxed = true)
        val streamRepository = mockk<StreamRepository>()
        val addonRepository = mockk<AddonRepository>()
        val metaRepository = mockk<MetaRepository>(relaxed = true)
        val playerSettingsDataStore = mockk<PlayerSettingsDataStore>()
        val streamLinkCacheDataStore = mockk<StreamLinkCacheDataStore>(relaxed = true).also {
            cachedStore = it
        }
        val debugSettingsDataStore = mockk<DebugSettingsDataStore>()
        val deviceCapabilityRepository = mockk<DeviceCapabilityRepository>()
        val shadowCollectionUploader = mockk<ShadowAutoplayCollectionUploader>(relaxed = true)

        every {
            playerSettingsDataStore.playerSettings
        } returns flowOf(playerSettings)
        coEvery { streamLinkCacheDataStore.getValid(any(), any()) } returns cachedLink
        coEvery { streamLinkCacheDataStore.invalidate(any()) } just runs
        every { debugSettingsDataStore.streamDiagnosticsEnabled } returns flowOf(false)
        coEvery { deviceCapabilityRepository.snapshotForAutoplay() } returns null
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

        return StreamScreenViewModel(
            context = context,
            streamRepository = streamRepository,
            addonRepository = addonRepository,
            metaRepository = metaRepository,
            playerSettingsDataStore = playerSettingsDataStore,
            streamLinkCacheDataStore = streamLinkCacheDataStore,
            debugSettingsDataStore = debugSettingsDataStore,
            deviceCapabilityRepository = deviceCapabilityRepository,
            benchmarkAwareStreamScorer = BenchmarkAwareStreamScorer(),
            shadowAutoPlayDecisionLogger = shadowLogger,
            shadowAutoplayCollectionUploader = shadowCollectionUploader,
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

    private fun scenarioCard(
        streamKey: String,
        providerId: String,
        visualTags: List<String> = listOf("HDR10"),
        quality: String = "BluRay",
        filename: String = "$streamKey.mkv"
    ) = BenchmarkAwareScoringScenarioStream(
        streamKey = streamKey,
        providerId = providerId,
        resolution = "2160p",
        quality = quality,
        encode = "HEVC",
        sizeBytes = 50L * 1024L * 1024L * 1024L,
        durationMs = 120L * 60_000L,
        visualTags = visualTags,
        filename = filename
    ).toStreamCardModel()

    private fun autoplayDecisionEvent(
        winners: List<ShadowStreamDecision>,
        selected: ShadowStreamDecision?,
        selectedNonDolbyVisionFallback: ShadowStreamDecision? = null
    ): ShadowAutoPlayDecisionEvent {
        return ShadowAutoPlayDecisionEvent(
            eventVersion = 1,
            eventType = "shadow_autoplay_decision",
            request = movieRequest(),
            benchmarksUsed = emptyList(),
            winners = winners,
            rejected = emptyList(),
            selected = selected,
            selectedNonDolbyVisionFallback = selectedNonDolbyVisionFallback
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
