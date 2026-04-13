package com.nexio.tv.ui.screens.settings

import android.content.Context
import com.nexio.tv.data.local.DebugSettingsDataStore
import com.nexio.tv.data.local.PlayerSettings
import com.nexio.tv.data.local.PlayerSettingsDataStore
import com.nexio.tv.data.local.TrailerSettings
import com.nexio.tv.data.local.TrailerSettingsDataStore
import com.nexio.tv.data.repository.TrackingProviderState
import com.nexio.tv.data.repository.TrackingProviderStateRepository
import com.nexio.tv.data.repository.benchmark.DebridBenchmarkProvider
import com.nexio.tv.data.repository.benchmark.DebridBenchmarkService
import com.nexio.tv.domain.repository.AddonRepository
import com.nexio.tv.ui.screens.player.spool.SpoolStorageProbeResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class PlaybackSettingsViewModelSpoolModeTest {
    @get:Rule
    val temp = TemporaryFolder()

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
    fun `failed disk spool storage probe clears persisted result`() {
        val playerSettingsDataStore = mockk<PlayerSettingsDataStore>(relaxed = true)
        val context = mockk<Context>(relaxed = true)
        val cacheFile = temp.newFile("cache-file")
        every { context.applicationContext } returns context
        every { context.cacheDir } returns cacheFile

        val viewModel = createViewModel(playerSettingsDataStore)

        viewModel.runDiskSpoolStorageProbe(context)

        coVerify(timeout = 5_000) {
            playerSettingsDataStore.setSpoolStorageProbeResult(null)
        }
    }

    @Test
    fun `superseded failed disk spool storage probe does not clear newer probe state`() {
        val playerSettingsDataStore = mockk<PlayerSettingsDataStore>(relaxed = true)
        val context = mockk<Context>(relaxed = true)
        val cacheDirectory = temp.newFolder("cache-dir")
        val firstProbeStarted = CountDownLatch(1)
        val releaseFirstProbe = CountDownLatch(1)
        val secondProbeStarted = CountDownLatch(1)
        val releaseSecondProbe = CountDownLatch(1)
        val invocation = AtomicInteger(0)
        every { context.applicationContext } returns context
        every { context.cacheDir } returns cacheDirectory

        val viewModel = createViewModel(playerSettingsDataStore).apply {
            diskSpoolStorageProbeRunnerForTesting = { directory, _ ->
                when (invocation.incrementAndGet()) {
                    1 -> {
                        firstProbeStarted.countDown()
                        assertTrue(releaseFirstProbe.await(5, TimeUnit.SECONDS))
                        throw IOException("stale probe failure")
                    }
                    2 -> {
                        secondProbeStarted.countDown()
                        assertTrue(releaseSecondProbe.await(5, TimeUnit.SECONDS))
                        passingProbeResult(directory)
                    }
                    else -> error("Unexpected probe invocation")
                }
            }
        }

        viewModel.runDiskSpoolStorageProbe(context)
        assertTrue(firstProbeStarted.await(5, TimeUnit.SECONDS))

        viewModel.runDiskSpoolStorageProbe(context)
        assertTrue(secondProbeStarted.await(5, TimeUnit.SECONDS))

        releaseFirstProbe.countDown()
        Thread.sleep(100)
        releaseSecondProbe.countDown()

        coVerify(timeout = 5_000, exactly = 2) {
            playerSettingsDataStore.setSpoolStorageProbeResult(null)
        }
        coVerify(timeout = 5_000, exactly = 1) {
            playerSettingsDataStore.setSpoolStorageProbeResult(match<SpoolStorageProbeResult> { true })
        }
    }

    @Test
    fun `older delayed disk spool storage probe commit cannot overwrite newer successful result`() {
        val playerSettingsDataStore = mockk<PlayerSettingsDataStore>(relaxed = true)
        val context = mockk<Context>(relaxed = true)
        val cacheDirectory = temp.newFolder("cache-dir")
        val staleClearEntered = CountDownLatch(1)
        val releaseStaleClear = CountDownLatch(1)
        val committedResults = CopyOnWriteArrayList<SpoolStorageProbeResult?>()
        val nullWrites = AtomicInteger(0)
        val invocation = AtomicInteger(0)
        val successResult = passingProbeResult(cacheDirectory)
        every { context.applicationContext } returns context
        every { context.cacheDir } returns cacheDirectory
        coEvery { playerSettingsDataStore.setSpoolStorageProbeResult(any()) } coAnswers {
            val result = firstArg<SpoolStorageProbeResult?>()
            if (result == null && nullWrites.incrementAndGet() == 2) {
                staleClearEntered.countDown()
                assertTrue(releaseStaleClear.await(5, TimeUnit.SECONDS))
            }
            committedResults += result
        }

        val viewModel = createViewModel(playerSettingsDataStore).apply {
            diskSpoolStorageProbeRunnerForTesting = { _, _ ->
                when (invocation.incrementAndGet()) {
                    1 -> throw IOException("stale probe failure")
                    2 -> successResult
                    else -> error("Unexpected probe invocation")
                }
            }
        }

        viewModel.runDiskSpoolStorageProbe(context)
        assertTrue(staleClearEntered.await(5, TimeUnit.SECONDS))

        viewModel.runDiskSpoolStorageProbe(context)
        Thread.sleep(200L)
        releaseStaleClear.countDown()

        coVerify(timeout = 5_000) {
            playerSettingsDataStore.setSpoolStorageProbeResult(successResult)
        }
        assertEquals(successResult, committedResults.last())
    }

    private fun createViewModel(
        playerSettingsDataStore: PlayerSettingsDataStore
    ): PlaybackSettingsViewModel {
        val trailerSettingsDataStore = mockk<TrailerSettingsDataStore>(relaxed = true)
        val debugSettingsDataStore = mockk<DebugSettingsDataStore>(relaxed = true)
        val addonRepository = mockk<AddonRepository>(relaxed = true)
        val debridBenchmarkService = mockk<DebridBenchmarkService>(relaxed = true)
        val trackingProviderStateRepository = mockk<TrackingProviderStateRepository>(relaxed = true)

        every { playerSettingsDataStore.playerSettings } returns flowOf(PlayerSettings())
        every { trailerSettingsDataStore.settings } returns flowOf(TrailerSettings())
        every { debugSettingsDataStore.streamDiagnosticsEnabled } returns flowOf(false)
        every { debugSettingsDataStore.startupPerfTelemetryEnabled } returns flowOf(false)
        every { addonRepository.getInstalledAddons() } returns flowOf(emptyList())
        every { trackingProviderStateRepository.state } returns flowOf(TrackingProviderState())
        every { debridBenchmarkService.latestResult(any<DebridBenchmarkProvider>()) } returns flowOf(null)

        return PlaybackSettingsViewModel(
            playerSettingsDataStore = playerSettingsDataStore,
            trailerSettingsDataStore = trailerSettingsDataStore,
            debugSettingsDataStore = debugSettingsDataStore,
            addonRepository = addonRepository,
            debridBenchmarkService = debridBenchmarkService,
            trackingProviderStateRepository = trackingProviderStateRepository
        )
    }

    private fun passingProbeResult(directory: File): SpoolStorageProbeResult {
        return SpoolStorageProbeResult(
            writeMbps = 180.0,
            readMbps = 180.0,
            combinedMbps = 360.0,
            p99ReadLatencyMs = 40L,
            maxReadStallMs = 70L,
            measuredAtMs = System.currentTimeMillis(),
            durationMs = 60_000L,
            bytesWritten = 1_350_000_000L,
            bytesRead = 1_350_000_000L,
            spoolDirectoryPath = directory.absolutePath
        )
    }
}
