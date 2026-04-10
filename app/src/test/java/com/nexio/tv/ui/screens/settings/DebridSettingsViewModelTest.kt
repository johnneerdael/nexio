package com.nexio.tv.ui.screens.settings

import android.content.ContentResolver
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.util.Log
import com.nexio.tv.R
import com.nexio.tv.data.local.EasyDebridSettings
import com.nexio.tv.data.local.EasyDebridSettingsDataStore
import com.nexio.tv.data.local.PlayerSettings
import com.nexio.tv.data.local.PlayerSettingsDataStore
import com.nexio.tv.data.local.PremiumizeSettings
import com.nexio.tv.data.local.PremiumizeSettingsDataStore
import com.nexio.tv.data.local.RealDebridAuthDataStore
import com.nexio.tv.data.local.RealDebridAuthState
import com.nexio.tv.data.local.TorBoxSettings
import com.nexio.tv.data.local.TorBoxSettingsDataStore
import com.nexio.tv.data.repository.EasyDebridAccountState
import com.nexio.tv.data.repository.EasyDebridService
import com.nexio.tv.data.repository.PremiumizeAccountState
import com.nexio.tv.data.repository.PremiumizeService
import com.nexio.tv.data.repository.RealDebridAuthService
import com.nexio.tv.data.repository.RealDebridTokenPollResult
import com.nexio.tv.data.repository.TorBoxAccountState
import com.nexio.tv.data.repository.TorBoxService
import com.nexio.tv.data.repository.benchmark.DebridBenchmarkProvider
import com.nexio.tv.data.repository.benchmark.DebridBenchmarkResult
import com.nexio.tv.data.repository.benchmark.DebridBenchmarkOutcome
import com.nexio.tv.data.repository.benchmark.DebridBenchmarkRuntimeState
import com.nexio.tv.data.repository.benchmark.DebridBenchmarkService
import com.nexio.tv.data.repository.benchmark.CollectorPublicDashboardLinkProvider
import com.nexio.tv.data.repository.benchmark.CollectorPublicDashboardLinkResult
import com.nexio.tv.data.repository.benchmark.DebridBenchmarkSummary
import com.nexio.tv.data.repository.benchmark.DebridBenchmarkTerminationReason
import com.nexio.tv.data.repository.benchmark.DebridConfigBenchmarkOutcome
import com.nexio.tv.data.repository.benchmark.DebridConfigBenchmarkProfileResult
import com.nexio.tv.data.repository.benchmark.DebridConfigBenchmarkResult
import com.nexio.tv.data.repository.benchmark.DebridConfigBenchmarkRuntimeState
import com.nexio.tv.data.repository.benchmark.DebridConfigBenchmarkService
import com.nexio.tv.data.repository.benchmark.DebridConfigBenchmarkSessionSummary
import com.nexio.tv.data.repository.benchmark.DebridConfigBenchmarkStatus
import com.nexio.tv.instrumentation.PlaybackTracer
import com.nexio.tv.instrumentation.PlaybackTraceToggle
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.coVerify
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.io.path.createTempDirectory

@OptIn(ExperimentalCoroutinesApi::class)
class DebridSettingsViewModelTest {

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
    fun `connected premiumize row exposes latest benchmark result`() = runTest(dispatcher) {
        val latestPremiumizeResult = sampleResult(DebridBenchmarkProvider.PREMIUMIZE)
        val viewModel = buildViewModel(
            premiumizeConnected = true,
            latestPremiumizeResult = latestPremiumizeResult
        )

        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.premiumizeBenchmark.canRun)
        assertEquals(
            latestPremiumizeResult,
            viewModel.uiState.value.premiumizeBenchmark.latestResult
        )
    }

    @Test
    fun `connected torbox row exposes latest benchmark result`() = runTest(dispatcher) {
        val latestTorBoxResult = sampleResult(DebridBenchmarkProvider.TORBOX)
        val viewModel = buildViewModel(
            torBoxConnected = true,
            latestTorBoxResult = latestTorBoxResult
        )

        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.torBoxBenchmark.canRun)
        assertEquals(
            latestTorBoxResult,
            viewModel.uiState.value.torBoxBenchmark.latestResult
        )
    }

    @Test
    fun `connected easydebrid row exposes latest benchmark result`() = runTest(dispatcher) {
        val latestEasyDebridResult = sampleResult(DebridBenchmarkProvider.EASY_DEBRID)
        val viewModel = buildViewModel(
            easyDebridConnected = true,
            latestEasyDebridResult = latestEasyDebridResult
        )

        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.easyDebridBenchmark.canRun)
        assertEquals(
            latestEasyDebridResult,
            viewModel.uiState.value.easyDebridBenchmark.latestResult
        )
    }

    @Test
    fun `start benchmark updates row into measuring state`() = runTest(dispatcher) {
        val benchmarkState = MutableStateFlow<DebridBenchmarkRuntimeState>(
            DebridBenchmarkRuntimeState.Idle
        )
        val benchmarkService = mockk<DebridBenchmarkService>(relaxed = true)
        every { benchmarkService.activeState } returns benchmarkState
        every { benchmarkService.latestResult(any()) } returns flowOf(null)
        every { benchmarkService.outcomes } returns MutableSharedFlow()
        coEvery { benchmarkService.start(DebridBenchmarkProvider.REAL_DEBRID) } answers {
            benchmarkState.value = DebridBenchmarkRuntimeState.Running(
                provider = DebridBenchmarkProvider.REAL_DEBRID,
                summary = DebridBenchmarkSummary(
                    startupTimeMs = 3_500L,
                    sustainedThroughputMbps = 82.4,
                    transferredBytes = 640L * 1024L * 1024L,
                    elapsedMs = 125_000L
                )
            )
            true
        }

        val viewModel = buildViewModel(
            realDebridConnected = true,
            benchmarkService = benchmarkService
        )

        advanceUntilIdle()

        viewModel.startBenchmark(DebridBenchmarkProvider.REAL_DEBRID)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.realDebridBenchmark.isRunning)
        assertEquals(
            640L * 1024L * 1024L,
            viewModel.uiState.value.realDebridBenchmark.activeSummary?.transferredBytes
        )
    }

    @Test
    fun `running benchmark without a summary does not expose zeroed progress`() = runTest(dispatcher) {
        val benchmarkState = MutableStateFlow<DebridBenchmarkRuntimeState>(
            DebridBenchmarkRuntimeState.Running(
                provider = DebridBenchmarkProvider.REAL_DEBRID,
                summary = null
            )
        )
        val benchmarkService = mockk<DebridBenchmarkService>(relaxed = true)
        every { benchmarkService.activeState } returns benchmarkState
        every { benchmarkService.latestResult(any()) } returns flowOf(null)
        every { benchmarkService.outcomes } returns MutableSharedFlow()

        val viewModel = buildViewModel(
            realDebridConnected = true,
            benchmarkService = benchmarkService
        )

        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.realDebridBenchmark.isRunning)
        assertEquals(null, viewModel.uiState.value.realDebridBenchmark.activeSummary)
    }

    @Test
    fun `no playable benchmark outcomes surface a user message`() = runTest(dispatcher) {
        val outcomes = MutableSharedFlow<DebridBenchmarkOutcome>()
        val benchmarkService = mockk<DebridBenchmarkService>(relaxed = true)
        every { benchmarkService.activeState } returns MutableStateFlow(DebridBenchmarkRuntimeState.Idle)
        every { benchmarkService.latestResult(any()) } returns flowOf(null)
        every { benchmarkService.outcomes } returns outcomes

        val viewModel = buildViewModel(
            realDebridConnected = true,
            benchmarkService = benchmarkService
        )
        advanceUntilIdle()

        val emittedMessage = async { viewModel.messages.first() }
        outcomes.emit(
            DebridBenchmarkOutcome(
                provider = DebridBenchmarkProvider.REAL_DEBRID,
                summary = DebridBenchmarkSummary(),
                terminationReason = DebridBenchmarkTerminationReason.NO_PLAYABLE_LIBRARY_ITEM
            )
        )

        assertEquals(
            "No playable Real-Debrid library item available for benchmarking",
            emittedMessage.await()
        )
    }

    @Test
    fun `no large download benchmark outcomes surface a user message`() = runTest(dispatcher) {
        val outcomes = MutableSharedFlow<DebridBenchmarkOutcome>()
        val benchmarkService = mockk<DebridBenchmarkService>(relaxed = true)
        every { benchmarkService.activeState } returns MutableStateFlow(DebridBenchmarkRuntimeState.Idle)
        every { benchmarkService.latestResult(any()) } returns flowOf(null)
        every { benchmarkService.outcomes } returns outcomes

        val viewModel = buildViewModel(
            premiumizeConnected = true,
            benchmarkService = benchmarkService
        )
        advanceUntilIdle()

        val emittedMessage = async { viewModel.messages.first() }
        outcomes.emit(
            DebridBenchmarkOutcome(
                provider = DebridBenchmarkProvider.PREMIUMIZE,
                summary = DebridBenchmarkSummary(),
                terminationReason = DebridBenchmarkTerminationReason.NO_LARGE_DOWNLOAD
            )
        )

        assertEquals(
            "No large Premiumize download found for benchmarking",
            emittedMessage.await()
        )
    }

    @Test
    fun `completed benchmark outcomes surface a result dialog`() = runTest(dispatcher) {
        val outcomes = MutableSharedFlow<DebridBenchmarkOutcome>()
        val benchmarkService = mockk<DebridBenchmarkService>(relaxed = true)
        every { benchmarkService.activeState } returns MutableStateFlow(DebridBenchmarkRuntimeState.Idle)
        every { benchmarkService.latestResult(any()) } returns flowOf(null)
        every { benchmarkService.outcomes } returns outcomes
        val result = sampleResult(DebridBenchmarkProvider.REAL_DEBRID)

        val viewModel = buildViewModel(
            realDebridConnected = true,
            benchmarkService = benchmarkService
        )
        advanceUntilIdle()

        outcomes.emit(
            DebridBenchmarkOutcome(
                provider = DebridBenchmarkProvider.REAL_DEBRID,
                summary = result.summary,
                terminationReason = DebridBenchmarkTerminationReason.COMPLETED,
                result = result
            )
        )
        advanceUntilIdle()

        assertEquals(
            DebridBenchmarkResultDialogUi(result = result),
            viewModel.uiState.value.benchmarkResultDialog
        )
    }

    @Test
    fun `open latest benchmark result exposes the saved dialog state`() = runTest(dispatcher) {
        val latestPremiumizeResult = sampleResult(DebridBenchmarkProvider.PREMIUMIZE)
        val viewModel = buildViewModel(
            premiumizeConnected = true,
            latestPremiumizeResult = latestPremiumizeResult
        )

        advanceUntilIdle()
        viewModel.openLatestBenchmarkResult(DebridBenchmarkProvider.PREMIUMIZE)

        assertEquals(
            DebridBenchmarkResultDialogUi(result = latestPremiumizeResult),
            viewModel.uiState.value.benchmarkResultDialog
        )
    }

    @Test
    fun `dismiss benchmark result clears the dialog state`() = runTest(dispatcher) {
        val viewModel = buildViewModel(
            premiumizeConnected = true,
            latestPremiumizeResult = sampleResult(DebridBenchmarkProvider.PREMIUMIZE)
        )

        advanceUntilIdle()
        viewModel.openLatestBenchmarkResult(DebridBenchmarkProvider.PREMIUMIZE)
        viewModel.dismissBenchmarkResultDialog()

        assertEquals(null, viewModel.uiState.value.benchmarkResultDialog)
    }

    @Test
    fun `connected provider row shows config benchmark action and latest best profile summary`() = runTest(dispatcher) {
        val latestConfigResult = sampleConfigResult(DebridBenchmarkProvider.REAL_DEBRID)
        val viewModel = buildViewModel(
            realDebridConnected = true,
            latestRealDebridConfigResult = latestConfigResult
        )

        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.realDebridConfigBenchmark.canRun)
        assertEquals(
            latestConfigResult,
            viewModel.uiState.value.realDebridConfigBenchmark.latestResult
        )
    }

    @Test
    fun `config benchmark completion dialog groups rows by chunk size`() = runTest(dispatcher) {
        val latestConfigResult = sampleConfigResult(DebridBenchmarkProvider.REAL_DEBRID)
        val viewModel = buildViewModel(
            realDebridConnected = true,
            latestRealDebridConfigResult = latestConfigResult
        )

        advanceUntilIdle()
        viewModel.openLatestConfigBenchmarkResult(DebridBenchmarkProvider.REAL_DEBRID)

        assertEquals(4, viewModel.uiState.value.configBenchmarkResultDialog?.chunkGroups?.size)
    }

    @Test
    fun `failed config benchmark outcomes surface a user message instead of crashing`() = runTest(dispatcher) {
        val outcomes = MutableSharedFlow<DebridConfigBenchmarkOutcome>()
        val configBenchmarkService = mockk<DebridConfigBenchmarkService>(relaxed = true)
        every { configBenchmarkService.activeState } returns MutableStateFlow(DebridConfigBenchmarkRuntimeState.Idle)
        every { configBenchmarkService.latestResult(any()) } returns flowOf(null)
        every { configBenchmarkService.outcomes } returns outcomes

        val viewModel = buildViewModel(
            realDebridConnected = true,
            configBenchmarkService = configBenchmarkService
        )
        advanceUntilIdle()

        val emittedMessage = async { viewModel.messages.first() }
        outcomes.emit(
            DebridConfigBenchmarkOutcome(
                provider = DebridBenchmarkProvider.REAL_DEBRID,
                terminationReason = DebridBenchmarkTerminationReason.FAILED
            )
        )

        assertEquals(
            "Real-Debrid config benchmark failed",
            emittedMessage.await()
        )
    }

    @Test
    fun `running config benchmark exposes live summary and profile label`() = runTest(dispatcher) {
        val configBenchmarkState = MutableStateFlow<DebridConfigBenchmarkRuntimeState>(
            DebridConfigBenchmarkRuntimeState.Running(
                provider = DebridBenchmarkProvider.REAL_DEBRID,
                currentProfile = com.nexio.tv.data.repository.benchmark.DebridConfigBenchmarkProfileMetadata(
                    parallelConnectionCount = 4,
                    chunkSizeMb = 16
                ),
                completedProfiles = 4,
                totalProfiles = 9,
                summary = DebridBenchmarkSummary(
                    startupTimeMs = 200L,
                    sustainedThroughputMbps = 612.4,
                    transferredBytes = 640L * 1024L * 1024L,
                    elapsedMs = 42_000L
                )
            )
        )
        val configBenchmarkService = mockk<DebridConfigBenchmarkService>(relaxed = true)
        every { configBenchmarkService.activeState } returns configBenchmarkState
        every { configBenchmarkService.latestResult(any()) } returns flowOf(null)
        every { configBenchmarkService.outcomes } returns MutableSharedFlow<DebridConfigBenchmarkOutcome>()

        val viewModel = buildViewModel(
            realDebridConnected = true,
            configBenchmarkService = configBenchmarkService
        )
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.realDebridConfigBenchmark.isRunning)
        assertTrue(
            viewModel.uiState.value.realDebridConfigBenchmark.activeProfileLabel?.contains("4x / 16 MB") == true
        )
        assertEquals(
            42_000L,
            viewModel.uiState.value.realDebridConfigBenchmark.activeSummary?.elapsedMs
        )
        assertEquals(
            612.4,
            viewModel.uiState.value.realDebridConfigBenchmark.activeSummary?.sustainedThroughputMbps
        )
    }

    @Test
    fun `deterministic autoplay is unavailable without any benchmark result`() = runTest(dispatcher) {
        val viewModel = buildViewModel(
            realDebridConnected = true,
            premiumizeConnected = true
        )

        advanceUntilIdle()

        assertEquals(false, viewModel.uiState.value.deterministicAutoplayAvailable)
        assertEquals(false, viewModel.uiState.value.deterministicAutoplayEnabled)
    }

    @Test
    fun `deterministic autoplay becomes available when any provider has benchmark result`() = runTest(dispatcher) {
        val viewModel = buildViewModel(
            realDebridConnected = true,
            latestRealDebridResult = sampleResult(DebridBenchmarkProvider.REAL_DEBRID),
            playerSettingsDataStore = mockk<PlayerSettingsDataStore>().also {
                every {
                    it.playerSettings
                } returns flowOf(PlayerSettings(serviceWrapEnabled = true))
            }
        )

        advanceUntilIdle()

        assertEquals(true, viewModel.uiState.value.deterministicAutoplayAvailable)
    }

    @Test
    fun `collector dashboard link is unavailable when provider reports base url is missing`() = runTest(dispatcher) {
        val viewModel = buildViewModel(
            collectorPublicDashboardLinkProvider = mockProvider(
                CollectorPublicDashboardLinkResult.Unavailable(
                    CollectorPublicDashboardLinkResult.Reason.BASE_URL_MISSING
                )
            )
        )

        advanceUntilIdle()

        assertEquals(
            false,
            viewModel.uiState.value.collectorDashboardAvailable
        )
        assertEquals(
            CollectorPublicDashboardLinkResult.Reason.BASE_URL_MISSING,
            viewModel.uiState.value.collectorDashboardUnavailableReason
        )
    }

    @Test
    fun `collector dashboard refreshes provider result`() = runTest(dispatcher) {
        val provider = mockProvider(
            CollectorPublicDashboardLinkResult.Available(
                token = "token",
                url = "https://collector.local/public/token"
            )
        )
        val viewModel = buildViewModel(
            collectorPublicDashboardLinkProvider = provider
        )

        viewModel.refreshPublicCollectorDashboardLink()
        advanceUntilIdle()

        assertEquals(true, viewModel.uiState.value.collectorDashboardAvailable)
        assertEquals("https://collector.local/public/token", viewModel.uiState.value.collectorDashboardUrl)
        assertEquals(null, viewModel.uiState.value.collectorDashboardUnavailableReason)
    }

    @Test
    fun `service wrap is unavailable when no provider is configured`() = runTest(dispatcher) {
        val viewModel = buildViewModel()

        advanceUntilIdle()

        assertEquals(false, viewModel.uiState.value.serviceWrapAvailable)
        assertEquals(false, viewModel.uiState.value.serviceWrapEnabled)
    }

    @Test
    fun `shadow autoplay data collection toggle reflects player settings`() = runTest(dispatcher) {
        val playerSettingsDataStore = mockk<PlayerSettingsDataStore>(relaxed = true)
        every { playerSettingsDataStore.playerSettings } returns flowOf(
            PlayerSettings(shadowAutoplayDataCollectionEnabled = true)
        )
        val viewModel = buildViewModel(playerSettingsDataStore = playerSettingsDataStore)

        advanceUntilIdle()

        assertEquals(true, viewModel.uiState.value.shadowAutoplayDataCollectionEnabled)
    }

    @Test
    fun `shadow autoplay data collection toggle updates settings store`() = runTest(dispatcher) {
        val playerSettingsDataStore = mockk<PlayerSettingsDataStore>(relaxed = true)
        every { playerSettingsDataStore.playerSettings } returns flowOf(PlayerSettings())
        val viewModel = buildViewModel(playerSettingsDataStore = playerSettingsDataStore)

        advanceUntilIdle()
        viewModel.setShadowAutoplayDataCollectionEnabled(true)
        advanceUntilIdle()

        coVerify { playerSettingsDataStore.setShadowAutoplayDataCollectionEnabled(true) }
    }

    @Test
    fun `benchmark data collection toggle reflects player settings`() = runTest(dispatcher) {
        val playerSettingsDataStore = mockk<PlayerSettingsDataStore>(relaxed = true)
        every { playerSettingsDataStore.playerSettings } returns flowOf(
            PlayerSettings(debridBenchmarkDataCollectionEnabled = true)
        )
        val viewModel = buildViewModel(playerSettingsDataStore = playerSettingsDataStore)

        advanceUntilIdle()

        assertEquals(true, viewModel.uiState.value.debridBenchmarkDataCollectionEnabled)
    }

    @Test
    fun `benchmark data collection toggle updates settings store`() = runTest(dispatcher) {
        val playerSettingsDataStore = mockk<PlayerSettingsDataStore>(relaxed = true)
        every { playerSettingsDataStore.playerSettings } returns flowOf(PlayerSettings())
        val viewModel = buildViewModel(playerSettingsDataStore = playerSettingsDataStore)

        advanceUntilIdle()
        viewModel.setDebridBenchmarkDataCollectionEnabled(true)
        advanceUntilIdle()

        coVerify { playerSettingsDataStore.setDebridBenchmarkDataCollectionEnabled(true) }
    }

    @Test
    fun `service wrap becomes available when any provider is connected`() = runTest(dispatcher) {
        val viewModel = buildViewModel(realDebridConnected = true)

        advanceUntilIdle()

        assertEquals(true, viewModel.uiState.value.serviceWrapAvailable)
    }

    @Test
    fun `deterministic autoplay stays unavailable when benchmark exists but service wrap is disabled`() = runTest(dispatcher) {
        val viewModel = buildViewModel(
            realDebridConnected = true,
            latestRealDebridResult = sampleResult(DebridBenchmarkProvider.REAL_DEBRID)
        )

        advanceUntilIdle()

        assertEquals(false, viewModel.uiState.value.serviceWrapEnabled)
        assertEquals(false, viewModel.uiState.value.deterministicAutoplayAvailable)
    }

    @Test
    fun `disabling service wrap turns off deterministic autoplay in settings store`() = runTest(dispatcher) {
        val playerSettingsDataStore = mockk<PlayerSettingsDataStore>(relaxed = true)
        every { playerSettingsDataStore.playerSettings } returns flowOf(
            PlayerSettings(
                deterministicAutoplayEnabled = true,
                serviceWrapEnabled = true
            )
        )
        val viewModel = buildViewModel(
            realDebridConnected = true,
            latestRealDebridResult = sampleResult(DebridBenchmarkProvider.REAL_DEBRID),
            playerSettingsDataStore = playerSettingsDataStore
        )

        advanceUntilIdle()
        viewModel.setServiceWrapEnabled(false)
        advanceUntilIdle()

        coVerify { playerSettingsDataStore.setServiceWrapEnabled(false) }
        coVerify { playerSettingsDataStore.setDeterministicAutoplayEnabled(false) }
    }

    @Test
    fun `playback trace toggle seeds from in-memory tracer before flow rehydrates`() = runTest(dispatcher) {
        PlaybackTracer.enabled = true
        try {
            val playbackTraceController =
                mockk<com.nexio.tv.instrumentation.PlaybackTraceController>(relaxed = true).also {
                    every { it.enabledFlow } returns emptyFlow()
                    every { it.statusFlow } returns MutableStateFlow(
                        com.nexio.tv.instrumentation.TraceStatus.EMPTY
                    )
                }
            val playbackTraceAdbControlToggle =
                mockk<com.nexio.tv.instrumentation.PlaybackTraceAdbControlToggle>(relaxed = true).also {
                    every { it.enabledFlow } returns flowOf(false)
                }

            val viewModel = buildViewModel(
                playbackTraceController = playbackTraceController,
                playbackTraceAdbControlToggle = playbackTraceAdbControlToggle
            )

            assertEquals(true, viewModel.uiState.value.playbackTraceEnabled)
        } finally {
            PlaybackTracer.enabled = false
        }
    }

    @Test
    fun `playback trace enabled follows status flow when status disagrees with enabled flow`() = runTest(dispatcher) {
        val statusFlow = MutableStateFlow(
            com.nexio.tv.instrumentation.TraceStatus.EMPTY.copy(enabled = true)
        )
        val playbackTraceController =
            mockk<com.nexio.tv.instrumentation.PlaybackTraceController>(relaxed = true).also {
                every { it.enabledFlow } returns emptyFlow()
                every { it.statusFlow } returns statusFlow
            }

        val viewModel = buildViewModel(playbackTraceController = playbackTraceController)

        advanceUntilIdle()

        assertEquals(true, viewModel.uiState.value.playbackTraceEnabled)
        assertEquals(true, viewModel.uiState.value.playbackTraceStatus.enabled)
    }

    @Test
    fun `playback trace state survives later combined ui recomputes`() = runTest(dispatcher) {
        val playerSettingsFlow = MutableStateFlow(PlayerSettings())
        val playerSettingsDataStore = mockk<PlayerSettingsDataStore>(relaxed = true).also {
            every { it.playerSettings } returns playerSettingsFlow
        }
        val statusFlow = MutableStateFlow(
            com.nexio.tv.instrumentation.TraceStatus.EMPTY.copy(enabled = true)
        )
        val playbackTraceController =
            mockk<com.nexio.tv.instrumentation.PlaybackTraceController>(relaxed = true).also {
                every { it.enabledFlow } returns emptyFlow()
                every { it.statusFlow } returns statusFlow
            }

        val viewModel = buildViewModel(
            playerSettingsDataStore = playerSettingsDataStore,
            playbackTraceController = playbackTraceController
        )

        advanceUntilIdle()
        assertEquals(true, viewModel.uiState.value.playbackTraceEnabled)

        playerSettingsFlow.value = PlayerSettings(serviceWrapEnabled = true)
        advanceUntilIdle()

        assertEquals(true, viewModel.uiState.value.playbackTraceEnabled)
        assertEquals(true, viewModel.uiState.value.playbackTraceStatus.enabled)
    }

    @Test
    fun `copy all traces zip delegates to controller refreshes status and emits success message`() = runTest(dispatcher) {
        val destination = Uri.parse("content://tests/playback-traces.zip")
        val appFilesDir = createTempDirectory("debrid-playback-trace-viewmodel-success").toFile()
        File(appFilesDir, "playback-traces").apply {
            mkdirs()
            resolve("session-a.jsonl").writeText("{\"type\":\"started\"}\n")
        }
        val destinationBytes = ByteArrayOutputStream()
        val contentResolver = mockk<ContentResolver>().also {
            every { it.openOutputStream(destination) } returns destinationBytes
        }
        val playbackTraceController =
            com.nexio.tv.instrumentation.PlaybackTraceController(
                appContext = object : ContextWrapper(mockk(relaxed = true)) {
                    override fun getFilesDir(): File = appFilesDir
                    override fun getCacheDir(): File =
                        createTempDirectory("debrid-playback-trace-viewmodel-success-cache").toFile()
                    override fun getContentResolver(): ContentResolver = contentResolver
                    override fun getPackageName(): String = "com.nexio.tv"
                },
                toggle = mockk<com.nexio.tv.instrumentation.PlaybackTraceToggle>(relaxed = true).also {
                    every { it.enabledFlow } returns emptyFlow()
                },
            )

        val viewModel = buildViewModel(playbackTraceController = playbackTraceController)
        advanceUntilIdle()

        val message = async { viewModel.messages.first() }

        viewModel.copyAllTracesZipToDestination(destination)
        advanceUntilIdle()

        assertTrue(destinationBytes.size() > 0)
        assertTrue(message.await().startsWith("Saved diagnostics zip ("))
    }

    @Test
    fun `copy all traces zip emits no trace message when nothing was written`() = runTest(dispatcher) {
        val destination = Uri.parse("content://tests/playback-traces.zip")
        val appFilesDir = createTempDirectory("debrid-playback-trace-viewmodel-empty").toFile()
        val playbackTraceController =
            com.nexio.tv.instrumentation.PlaybackTraceController(
                appContext = object : ContextWrapper(mockk(relaxed = true)) {
                    override fun getFilesDir(): File = appFilesDir
                    override fun getCacheDir(): File =
                        createTempDirectory("debrid-playback-trace-viewmodel-empty-cache").toFile()
                    override fun getContentResolver(): ContentResolver =
                        mockk(relaxed = true)
                    override fun getPackageName(): String = "com.nexio.tv"
                },
                toggle = mockk<com.nexio.tv.instrumentation.PlaybackTraceToggle>(relaxed = true).also {
                    every { it.enabledFlow } returns emptyFlow()
                },
            )

        val viewModel = buildViewModel(playbackTraceController = playbackTraceController)
        advanceUntilIdle()

        val message = async { viewModel.messages.first() }

        viewModel.copyAllTracesZipToDestination(destination)
        advanceUntilIdle()

        assertEquals("No playback traces to export", message.await())
    }

    @Test
    fun `copy all traces zip to downloads emits explicit success message`() = runTest(dispatcher) {
        val playbackTraceController = mockk<com.nexio.tv.instrumentation.PlaybackTraceController>(relaxed = true)
        every { playbackTraceController.enabledFlow } returns emptyFlow()
        every { playbackTraceController.statusFlow } returns MutableStateFlow(
            com.nexio.tv.instrumentation.TraceStatus.EMPTY
        )
        every { playbackTraceController.listTraces() } returns listOf(File("session-a.jsonl"))
        coEvery { playbackTraceController.copyAllToDownloads(any()) } returns "Downloads/playback-traces.zip"

        val viewModel = buildViewModel(playbackTraceController = playbackTraceController)
        advanceUntilIdle()

        val message = async { viewModel.messages.first() }

        viewModel.copyAllTracesZipToDownloads()
        advanceUntilIdle()

        assertEquals("Saved diagnostics zip to Downloads", message.await())
        coVerify { playbackTraceController.copyAllToDownloads(any()) }
    }

    @Test
    fun `copy all traces zip to downloads emits explicit failure message`() = runTest(dispatcher) {
        val playbackTraceController = mockk<com.nexio.tv.instrumentation.PlaybackTraceController>(relaxed = true)
        every { playbackTraceController.enabledFlow } returns emptyFlow()
        every { playbackTraceController.statusFlow } returns MutableStateFlow(
            com.nexio.tv.instrumentation.TraceStatus.EMPTY
        )
        every { playbackTraceController.listTraces() } returns listOf(File("session-a.jsonl"))
        coEvery { playbackTraceController.copyAllToDownloads(any()) } returns null

        val viewModel = buildViewModel(playbackTraceController = playbackTraceController)
        advanceUntilIdle()

        val message = async { viewModel.messages.first() }

        viewModel.copyAllTracesZipToDownloads()
        advanceUntilIdle()

        assertEquals("Failed to save diagnostics zip to Downloads", message.await())
        coVerify { playbackTraceController.copyAllToDownloads(any()) }
    }

    @Test
    fun `service wrap is disabled automatically when last provider disconnects`() = runTest(dispatcher) {
        val rdAuthState = MutableStateFlow(
            RealDebridAuthState(
                userClientId = "id",
                userClientSecret = "secret",
                accessToken = "token",
                refreshToken = "refresh"
            )
        )
        val playerSettingsDataStore = mockk<PlayerSettingsDataStore>(relaxed = true)
        every { playerSettingsDataStore.playerSettings } returns flowOf(
            PlayerSettings(serviceWrapEnabled = true)
        )

        buildViewModel(
            realDebridAuthStateFlow = rdAuthState,
            playerSettingsDataStore = playerSettingsDataStore
        )
        advanceUntilIdle()

        rdAuthState.value = RealDebridAuthState()
        advanceUntilIdle()

        coVerify { playerSettingsDataStore.setServiceWrapEnabled(false) }
    }

    @Test
    fun `auto-polling calls pollDeviceToken after 5 seconds when awaiting approval`() = runTest(dispatcher) {
        val rdAuthService = mockk<RealDebridAuthService>(relaxed = true)
        coEvery { rdAuthService.pollDeviceToken() } returns RealDebridTokenPollResult.Pending

        buildViewModel(
            realDebridAuthStateFlow = flowOf(
                RealDebridAuthState(deviceCode = "dev-code", userCode = "ABC123", verificationUrl = "https://real-debrid.com/device")
            ),
            realDebridAuthService = rdAuthService
        )
        advanceUntilIdle()

        testScheduler.advanceTimeBy(5_100)
        advanceUntilIdle()

        coVerify(atLeast = 1) { rdAuthService.pollDeviceToken() }
    }

    @Test
    fun `auto-polling continues silently on Pending and polls again after another interval`() = runTest(dispatcher) {
        val rdAuthService = mockk<RealDebridAuthService>(relaxed = true)
        coEvery { rdAuthService.pollDeviceToken() } returns RealDebridTokenPollResult.Pending

        val viewModel = buildViewModel(
            realDebridAuthStateFlow = flowOf(
                RealDebridAuthState(deviceCode = "dev-code", userCode = "ABC123", verificationUrl = "https://real-debrid.com/device")
            ),
            realDebridAuthService = rdAuthService
        )
        advanceUntilIdle()

        testScheduler.advanceTimeBy(10_100)
        advanceUntilIdle()

        coVerify(exactly = 2) { rdAuthService.pollDeviceToken() }
        assertEquals(DebridConnectionMode.AWAITING_APPROVAL, viewModel.uiState.value.realDebridMode)
    }

    @Test
    fun `auto-polling transitions to connected and emits success message on Approved`() = runTest(dispatcher) {
        val rdAuthState = MutableStateFlow(
            RealDebridAuthState(deviceCode = "dev-code", userCode = "ABC123", verificationUrl = "https://real-debrid.com/device")
        )
        val rdAuthService = mockk<RealDebridAuthService>(relaxed = true)
        coEvery { rdAuthService.pollDeviceToken() } coAnswers {
            rdAuthState.value = RealDebridAuthState(
                userClientId = "client-id",
                userClientSecret = "client-secret",
                accessToken = "token",
                refreshToken = "refresh",
                username = "rd-user"
            )
            RealDebridTokenPollResult.Approved("rd-user")
        }

        val viewModel = buildViewModel(
            realDebridAuthStateFlow = rdAuthState,
            realDebridAuthService = rdAuthService
        )
        advanceUntilIdle()

        val message = async { viewModel.messages.first() }
        testScheduler.advanceTimeBy(5_100)
        advanceUntilIdle()

        assertEquals("Connected to Real-Debrid as rd-user", message.await())
        assertEquals(DebridConnectionMode.CONNECTED, viewModel.uiState.value.realDebridMode)
    }

    @Test
    fun `auto-polling emits expired message and stops on Expired result`() = runTest(dispatcher) {
        val rdAuthService = mockk<RealDebridAuthService>(relaxed = true)
        coEvery { rdAuthService.pollDeviceToken() } returns RealDebridTokenPollResult.Expired

        val viewModel = buildViewModel(
            realDebridAuthStateFlow = flowOf(
                RealDebridAuthState(deviceCode = "dev-code", userCode = "ABC123", verificationUrl = "https://real-debrid.com/device")
            ),
            realDebridAuthService = rdAuthService
        )
        advanceUntilIdle()

        val message = async { viewModel.messages.first() }
        testScheduler.advanceTimeBy(5_100)
        advanceUntilIdle()

        assertEquals("Real-Debrid device code expired", message.await())
        // Only one poll fired — loop exited after Expired
        coVerify(exactly = 1) { rdAuthService.pollDeviceToken() }
    }

    private fun buildViewModel(
        realDebridConnected: Boolean = false,
        realDebridAuthService: RealDebridAuthService? = null,
        realDebridAuthStateFlow: kotlinx.coroutines.flow.Flow<RealDebridAuthState>? = null,
        premiumizeConnected: Boolean = false,
        torBoxConnected: Boolean = false,
        easyDebridConnected: Boolean = false,
        latestRealDebridResult: DebridBenchmarkResult? = null,
        latestPremiumizeResult: DebridBenchmarkResult? = null,
        latestTorBoxResult: DebridBenchmarkResult? = null,
        latestEasyDebridResult: DebridBenchmarkResult? = null,
        latestRealDebridConfigResult: DebridConfigBenchmarkResult? = null,
        latestPremiumizeConfigResult: DebridConfigBenchmarkResult? = null,
        latestTorBoxConfigResult: DebridConfigBenchmarkResult? = null,
        latestEasyDebridConfigResult: DebridConfigBenchmarkResult? = null,
        benchmarkService: DebridBenchmarkService? = null,
        configBenchmarkService: DebridConfigBenchmarkService? = null,
        playerSettingsDataStore: PlayerSettingsDataStore? = null,
        collectorPublicDashboardLinkProvider: CollectorPublicDashboardLinkProvider? = null,
        playbackTraceToggle: PlaybackTraceToggle? = null,
        playbackTraceController: com.nexio.tv.instrumentation.PlaybackTraceController? = null,
        playbackTraceAdbControlToggle: com.nexio.tv.instrumentation.PlaybackTraceAdbControlToggle? = null
    ): DebridSettingsViewModel {
        val appContext = mockk<Context>(relaxed = true).also {
            every { it.getString(R.string.playback_diagnostics_export_all_downloads_success) } returns
                "Saved diagnostics zip to Downloads"
            every { it.getString(R.string.playback_diagnostics_export_all_downloads_failure) } returns
                "Failed to save diagnostics zip to Downloads"
            every { it.getString(R.string.playback_diagnostics_export_all_downloads_empty) } returns
                "No playback traces to export"
        }
        val realDebridAuthDataStore = mockk<RealDebridAuthDataStore>()
        every { realDebridAuthDataStore.state } returns (realDebridAuthStateFlow ?: flowOf(
            if (realDebridConnected) {
                RealDebridAuthState(
                    userClientId = "client-id",
                    userClientSecret = "client-secret",
                    accessToken = "access-token",
                    refreshToken = "refresh-token",
                    username = "rd-user"
                )
            } else {
                RealDebridAuthState()
            }
        ))

        val premiumizeService = mockk<PremiumizeService>(relaxed = true)
        every { premiumizeService.observeAccountState() } returns flowOf(
            PremiumizeAccountState(
                isConnected = premiumizeConnected,
                customerId = if (premiumizeConnected) 42 else null
            )
        )

        val torBoxService = mockk<TorBoxService>(relaxed = true)
        every { torBoxService.observeAccountState() } returns flowOf(
            TorBoxAccountState(
                apiKey = if (torBoxConnected) "tb-key" else "",
                email = if (torBoxConnected) "tb@example.com" else null,
                plan = if (torBoxConnected) "premium" else null,
                isConnected = torBoxConnected
            )
        )

        val easyDebridService = mockk<EasyDebridService>(relaxed = true)
        every { easyDebridService.observeAccountState() } returns flowOf(
            EasyDebridAccountState(
                apiKey = if (easyDebridConnected) "ed-key" else "",
                userId = if (easyDebridConnected) "ed-user" else null,
                paidUntil = if (easyDebridConnected) "2099-01-01" else null,
                isConnected = easyDebridConnected
            )
        )

        val premiumizeSettingsDataStore = mockk<PremiumizeSettingsDataStore>()
        every { premiumizeSettingsDataStore.settings } returns flowOf(PremiumizeSettings())

        val torBoxSettingsDataStore = mockk<TorBoxSettingsDataStore>()
        every { torBoxSettingsDataStore.settings } returns flowOf(TorBoxSettings())

        val easyDebridSettingsDataStore = mockk<EasyDebridSettingsDataStore>()
        every { easyDebridSettingsDataStore.settings } returns flowOf(EasyDebridSettings())

        val resolvedPlayerSettingsDataStore = playerSettingsDataStore ?: mockk<PlayerSettingsDataStore>().also {
            every { it.playerSettings } returns flowOf(PlayerSettings())
        }

        val resolvedBenchmarkService = benchmarkService ?: mockk<DebridBenchmarkService>(relaxed = true).also { service ->
            every { service.activeState } returns MutableStateFlow(DebridBenchmarkRuntimeState.Idle)
            every { service.latestResult(any()) } answers {
                when (firstArg<DebridBenchmarkProvider>()) {
                    DebridBenchmarkProvider.REAL_DEBRID -> flowOf(latestRealDebridResult)
                    DebridBenchmarkProvider.PREMIUMIZE -> flowOf(latestPremiumizeResult)
                    DebridBenchmarkProvider.TORBOX -> flowOf(latestTorBoxResult)
                    DebridBenchmarkProvider.EASY_DEBRID -> flowOf(latestEasyDebridResult)
                }
            }
            every { service.outcomes } returns MutableSharedFlow()
        }

        val resolvedConfigBenchmarkService = configBenchmarkService ?: mockk<DebridConfigBenchmarkService>(relaxed = true).also { service ->
            every { service.activeState } returns MutableStateFlow(DebridConfigBenchmarkRuntimeState.Idle)
            every { service.latestResult(any()) } answers {
                when (firstArg<DebridBenchmarkProvider>()) {
                    DebridBenchmarkProvider.REAL_DEBRID -> flowOf(latestRealDebridConfigResult)
                    DebridBenchmarkProvider.PREMIUMIZE -> flowOf(latestPremiumizeConfigResult)
                    DebridBenchmarkProvider.TORBOX -> flowOf(latestTorBoxConfigResult)
                    DebridBenchmarkProvider.EASY_DEBRID -> flowOf(latestEasyDebridConfigResult)
                }
            }
            every { service.outcomes } returns MutableSharedFlow<DebridConfigBenchmarkOutcome>()
        }

        val resolvedCollectorPublicDashboardLinkProvider = collectorPublicDashboardLinkProvider
            ?: mockProvider(
                CollectorPublicDashboardLinkResult.Unavailable(
                    CollectorPublicDashboardLinkResult.Reason.BASE_URL_MISSING
                )
            )
        val resolvedPlaybackTraceToggle = playbackTraceToggle
            ?: mockk<PlaybackTraceToggle>(relaxed = true).also {
                every { it.enabledFlow } returns flowOf(false)
            }
        val resolvedPlaybackTraceController =
            playbackTraceController ?: mockk<com.nexio.tv.instrumentation.PlaybackTraceController>(relaxed = true).also {
                every { it.enabledFlow } returns flowOf(false)
                every { it.statusFlow } returns kotlinx.coroutines.flow.MutableStateFlow(
                    com.nexio.tv.instrumentation.TraceStatus.EMPTY
                )
            }
        val resolvedPlaybackTraceAdbControlToggle =
            playbackTraceAdbControlToggle ?: mockk<com.nexio.tv.instrumentation.PlaybackTraceAdbControlToggle>(relaxed = true).also {
                every { it.enabledFlow } returns flowOf(false)
            }

        return DebridSettingsViewModel(
            appContext = appContext,
            realDebridAuthService = realDebridAuthService ?: mockk<RealDebridAuthService>(relaxed = true),
            realDebridAuthDataStore = realDebridAuthDataStore,
            premiumizeService = premiumizeService,
            premiumizeSettingsDataStore = premiumizeSettingsDataStore,
            torBoxService = torBoxService,
            torBoxSettingsDataStore = torBoxSettingsDataStore,
            easyDebridService = easyDebridService,
            easyDebridSettingsDataStore = easyDebridSettingsDataStore,
            playerSettingsDataStore = resolvedPlayerSettingsDataStore,
            debridBenchmarkService = resolvedBenchmarkService,
            debridConfigBenchmarkService = resolvedConfigBenchmarkService,
            collectorPublicDashboardLinkProvider = resolvedCollectorPublicDashboardLinkProvider,
            playbackTraceToggle = resolvedPlaybackTraceToggle,
            playbackTraceController = resolvedPlaybackTraceController,
            playbackTraceAdbControlToggle = resolvedPlaybackTraceAdbControlToggle,
            playbackDiagnosticsUploader = mockk<com.nexio.tv.data.repository.benchmark.PlaybackDiagnosticsUploader>(relaxed = true),
        )
    }

    private fun mockProvider(result: CollectorPublicDashboardLinkResult): CollectorPublicDashboardLinkProvider {
        return mockk<CollectorPublicDashboardLinkProvider>(relaxed = true).also {
            every { it.resolvePublicDashboardLink() } returns result
        }
    }

    private fun sampleResult(provider: DebridBenchmarkProvider): DebridBenchmarkResult {
        return DebridBenchmarkResult(
            provider = provider,
            measuredAtMs = 1_234L,
            summary = DebridBenchmarkSummary(
                startupTimeMs = 4_000L,
                sustainedThroughputMbps = 78.25,
                transferredBytes = 700L * 1024L * 1024L,
                elapsedMs = 130_000L
            ),
            terminationReason = DebridBenchmarkTerminationReason.COMPLETED
        )
    }

    private fun sampleConfigResult(provider: DebridBenchmarkProvider): DebridConfigBenchmarkResult {
        val profiles = listOf(
            DebridConfigBenchmarkProfileResult(
                parallelConnectionCount = 2,
                chunkSizeMb = 8,
                status = DebridConfigBenchmarkStatus.SUCCESS,
                averageThroughputMbps = 410.0,
                transferredBytes = 1_000L,
                elapsedMs = 30_000L
            ),
            DebridConfigBenchmarkProfileResult(
                parallelConnectionCount = 3,
                chunkSizeMb = 8,
                status = DebridConfigBenchmarkStatus.FAILED,
                failureReason = "Connection reset"
            ),
            DebridConfigBenchmarkProfileResult(
                parallelConnectionCount = 4,
                chunkSizeMb = 8,
                status = DebridConfigBenchmarkStatus.SUCCESS,
                averageThroughputMbps = 595.4,
                transferredBytes = 1_000L,
                elapsedMs = 30_000L
            ),
            DebridConfigBenchmarkProfileResult(
                parallelConnectionCount = 2,
                chunkSizeMb = 16,
                status = DebridConfigBenchmarkStatus.SUCCESS,
                averageThroughputMbps = 463.6,
                transferredBytes = 1_000L,
                elapsedMs = 30_000L
            ),
            DebridConfigBenchmarkProfileResult(
                parallelConnectionCount = 3,
                chunkSizeMb = 16,
                status = DebridConfigBenchmarkStatus.SUCCESS,
                averageThroughputMbps = 620.0,
                transferredBytes = 1_000L,
                elapsedMs = 30_000L
            ),
            DebridConfigBenchmarkProfileResult(
                parallelConnectionCount = 4,
                chunkSizeMb = 16,
                status = DebridConfigBenchmarkStatus.SUCCESS,
                averageThroughputMbps = 633.9,
                transferredBytes = 1_000L,
                elapsedMs = 30_000L
            ),
            DebridConfigBenchmarkProfileResult(
                parallelConnectionCount = 2,
                chunkSizeMb = 24,
                status = DebridConfigBenchmarkStatus.SUCCESS,
                averageThroughputMbps = 562.2,
                transferredBytes = 1_000L,
                elapsedMs = 30_000L
            ),
            DebridConfigBenchmarkProfileResult(
                parallelConnectionCount = 3,
                chunkSizeMb = 24,
                status = DebridConfigBenchmarkStatus.SUCCESS,
                averageThroughputMbps = 756.6,
                transferredBytes = 1_000L,
                elapsedMs = 30_000L
            ),
            DebridConfigBenchmarkProfileResult(
                parallelConnectionCount = 4,
                chunkSizeMb = 24,
                status = DebridConfigBenchmarkStatus.UNSUPPORTED,
                unsupportedReason = "Exceeds safe memory budget"
            ),
            DebridConfigBenchmarkProfileResult(
                parallelConnectionCount = 2,
                chunkSizeMb = 32,
                status = DebridConfigBenchmarkStatus.SUCCESS,
                averageThroughputMbps = 610.0,
                transferredBytes = 1_000L,
                elapsedMs = 30_000L
            ),
            DebridConfigBenchmarkProfileResult(
                parallelConnectionCount = 3,
                chunkSizeMb = 32,
                status = DebridConfigBenchmarkStatus.SUCCESS,
                averageThroughputMbps = 701.2,
                transferredBytes = 1_000L,
                elapsedMs = 30_000L
            ),
            DebridConfigBenchmarkProfileResult(
                parallelConnectionCount = 4,
                chunkSizeMb = 32,
                status = DebridConfigBenchmarkStatus.FAILED,
                failureReason = "Failed to download chunk 173"
            )
        )
        return DebridConfigBenchmarkResult(
            provider = provider,
            measuredAtMs = 9_999L,
            summary = DebridConfigBenchmarkSessionSummary(
                totalProfileCount = profiles.size,
                successfulProfileCount = profiles.count { it.status == DebridConfigBenchmarkStatus.SUCCESS },
                failedProfileCount = profiles.count { it.status == DebridConfigBenchmarkStatus.FAILED },
                unsupportedProfileCount = profiles.count { it.status == DebridConfigBenchmarkStatus.UNSUPPORTED },
                bestProfile = profiles.first { it.averageThroughputMbps == 756.6 }
            ),
            profiles = profiles
        )
    }
}
