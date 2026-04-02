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

    private fun buildViewModel(
        realDebridConnected: Boolean = false,
        premiumizeConnected: Boolean = false,
        latestPremiumizeResult: DebridBenchmarkResult? = null,
        benchmarkService: DebridBenchmarkService? = null
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
            every { service.latestResult(DebridBenchmarkProvider.REAL_DEBRID) } returns flowOf(null)
            every { service.latestResult(DebridBenchmarkProvider.PREMIUMIZE) } returns
                flowOf(latestPremiumizeResult)
            every { service.outcomes } returns MutableSharedFlow()
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
            debridBenchmarkService = resolvedBenchmarkService
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
}
