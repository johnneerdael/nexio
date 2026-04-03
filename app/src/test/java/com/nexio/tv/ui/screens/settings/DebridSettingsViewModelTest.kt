package com.nexio.tv.ui.screens.settings

import com.nexio.tv.data.local.EasyDebridSettings
import com.nexio.tv.data.local.EasyDebridSettingsDataStore
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
import com.nexio.tv.data.repository.TorBoxAccountState
import com.nexio.tv.data.repository.TorBoxService
import com.nexio.tv.data.repository.benchmark.DebridBenchmarkProvider
import com.nexio.tv.data.repository.benchmark.DebridBenchmarkResult
import com.nexio.tv.data.repository.benchmark.DebridBenchmarkOutcome
import com.nexio.tv.data.repository.benchmark.DebridBenchmarkRuntimeState
import com.nexio.tv.data.repository.benchmark.DebridBenchmarkService
import com.nexio.tv.data.repository.benchmark.DebridBenchmarkSummary
import com.nexio.tv.data.repository.benchmark.DebridBenchmarkTerminationReason
import com.nexio.tv.data.repository.benchmark.DebridConfigBenchmarkOutcome
import com.nexio.tv.data.repository.benchmark.DebridConfigBenchmarkProfileResult
import com.nexio.tv.data.repository.benchmark.DebridConfigBenchmarkResult
import com.nexio.tv.data.repository.benchmark.DebridConfigBenchmarkRuntimeState
import com.nexio.tv.data.repository.benchmark.DebridConfigBenchmarkService
import com.nexio.tv.data.repository.benchmark.DebridConfigBenchmarkSessionSummary
import com.nexio.tv.data.repository.benchmark.DebridConfigBenchmarkStatus
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
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
class DebridSettingsViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
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
    fun `start benchmark updates row into measuring state`() = runTest(dispatcher) {
        val benchmarkState = MutableStateFlow<DebridBenchmarkRuntimeState>(
            DebridBenchmarkRuntimeState.Idle
        )
        val benchmarkService = mockk<DebridBenchmarkService>(relaxed = true)
        every { benchmarkService.activeState } returns benchmarkState
        every { benchmarkService.latestResult(DebridBenchmarkProvider.REAL_DEBRID) } returns flowOf(null)
        every { benchmarkService.latestResult(DebridBenchmarkProvider.PREMIUMIZE) } returns flowOf(null)
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
        every { benchmarkService.latestResult(DebridBenchmarkProvider.REAL_DEBRID) } returns flowOf(null)
        every { benchmarkService.latestResult(DebridBenchmarkProvider.PREMIUMIZE) } returns flowOf(null)
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
        every { benchmarkService.latestResult(DebridBenchmarkProvider.REAL_DEBRID) } returns flowOf(null)
        every { benchmarkService.latestResult(DebridBenchmarkProvider.PREMIUMIZE) } returns flowOf(null)
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
        every { benchmarkService.latestResult(DebridBenchmarkProvider.REAL_DEBRID) } returns flowOf(null)
        every { benchmarkService.latestResult(DebridBenchmarkProvider.PREMIUMIZE) } returns flowOf(null)
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
        every { benchmarkService.latestResult(DebridBenchmarkProvider.REAL_DEBRID) } returns flowOf(null)
        every { benchmarkService.latestResult(DebridBenchmarkProvider.PREMIUMIZE) } returns flowOf(null)
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

        assertEquals(3, viewModel.uiState.value.configBenchmarkResultDialog?.chunkGroups?.size)
    }

    @Test
    fun `failed config benchmark outcomes surface a user message instead of crashing`() = runTest(dispatcher) {
        val outcomes = MutableSharedFlow<DebridConfigBenchmarkOutcome>()
        val configBenchmarkService = mockk<DebridConfigBenchmarkService>(relaxed = true)
        every { configBenchmarkService.activeState } returns MutableStateFlow(DebridConfigBenchmarkRuntimeState.Idle)
        every { configBenchmarkService.latestResult(DebridBenchmarkProvider.REAL_DEBRID) } returns flowOf(null)
        every { configBenchmarkService.latestResult(DebridBenchmarkProvider.PREMIUMIZE) } returns flowOf(null)
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

    private fun buildViewModel(
        realDebridConnected: Boolean = false,
        premiumizeConnected: Boolean = false,
        latestRealDebridResult: DebridBenchmarkResult? = null,
        latestPremiumizeResult: DebridBenchmarkResult? = null,
        latestRealDebridConfigResult: DebridConfigBenchmarkResult? = null,
        latestPremiumizeConfigResult: DebridConfigBenchmarkResult? = null,
        benchmarkService: DebridBenchmarkService? = null,
        configBenchmarkService: DebridConfigBenchmarkService? = null
    ): DebridSettingsViewModel {
        val realDebridAuthDataStore = mockk<RealDebridAuthDataStore>()
        every { realDebridAuthDataStore.state } returns flowOf(
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
        )

        val premiumizeService = mockk<PremiumizeService>(relaxed = true)
        every { premiumizeService.observeAccountState() } returns flowOf(
            PremiumizeAccountState(
                isConnected = premiumizeConnected,
                customerId = if (premiumizeConnected) 42 else null
            )
        )

        val torBoxService = mockk<TorBoxService>(relaxed = true)
        every { torBoxService.observeAccountState() } returns flowOf(TorBoxAccountState())

        val easyDebridService = mockk<EasyDebridService>(relaxed = true)
        every { easyDebridService.observeAccountState() } returns flowOf(EasyDebridAccountState())

        val premiumizeSettingsDataStore = mockk<PremiumizeSettingsDataStore>()
        every { premiumizeSettingsDataStore.settings } returns flowOf(PremiumizeSettings())

        val torBoxSettingsDataStore = mockk<TorBoxSettingsDataStore>()
        every { torBoxSettingsDataStore.settings } returns flowOf(TorBoxSettings())

        val easyDebridSettingsDataStore = mockk<EasyDebridSettingsDataStore>()
        every { easyDebridSettingsDataStore.settings } returns flowOf(EasyDebridSettings())

        val resolvedBenchmarkService = benchmarkService ?: mockk<DebridBenchmarkService>(relaxed = true).also { service ->
            every { service.activeState } returns MutableStateFlow(DebridBenchmarkRuntimeState.Idle)
            every { service.latestResult(DebridBenchmarkProvider.REAL_DEBRID) } returns
                flowOf(latestRealDebridResult)
            every { service.latestResult(DebridBenchmarkProvider.PREMIUMIZE) } returns
                flowOf(latestPremiumizeResult)
            every { service.outcomes } returns MutableSharedFlow()
        }

        val resolvedConfigBenchmarkService = configBenchmarkService ?: mockk<DebridConfigBenchmarkService>(relaxed = true).also { service ->
            every { service.activeState } returns MutableStateFlow(DebridConfigBenchmarkRuntimeState.Idle)
            every { service.latestResult(DebridBenchmarkProvider.REAL_DEBRID) } returns
                flowOf(latestRealDebridConfigResult)
            every { service.latestResult(DebridBenchmarkProvider.PREMIUMIZE) } returns
                flowOf(latestPremiumizeConfigResult)
            every { service.outcomes } returns MutableSharedFlow<DebridConfigBenchmarkOutcome>()
        }

        return DebridSettingsViewModel(
            realDebridAuthService = mockk<RealDebridAuthService>(relaxed = true),
            realDebridAuthDataStore = realDebridAuthDataStore,
            premiumizeService = premiumizeService,
            premiumizeSettingsDataStore = premiumizeSettingsDataStore,
            torBoxService = torBoxService,
            torBoxSettingsDataStore = torBoxSettingsDataStore,
            easyDebridService = easyDebridService,
            easyDebridSettingsDataStore = easyDebridSettingsDataStore,
            debridBenchmarkService = resolvedBenchmarkService,
            debridConfigBenchmarkService = resolvedConfigBenchmarkService
        )
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
                chunkSizeMb = 16,
                status = DebridConfigBenchmarkStatus.SUCCESS,
                averageThroughputMbps = 620.0,
                transferredBytes = 1_000L,
                elapsedMs = 30_000L
            ),
            DebridConfigBenchmarkProfileResult(
                parallelConnectionCount = 4,
                chunkSizeMb = 24,
                status = DebridConfigBenchmarkStatus.UNSUPPORTED,
                unsupportedReason = "Exceeds safe memory budget"
            )
        )
        return DebridConfigBenchmarkResult(
            provider = provider,
            measuredAtMs = 9_999L,
            summary = DebridConfigBenchmarkSessionSummary(
                totalProfileCount = profiles.size,
                successfulProfileCount = 2,
                failedProfileCount = 0,
                unsupportedProfileCount = 1,
                bestProfile = profiles[1]
            ),
            profiles = profiles
        )
    }
}
